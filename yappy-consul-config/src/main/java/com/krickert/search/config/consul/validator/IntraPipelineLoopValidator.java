package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Singleton
public class IntraPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(IntraPipelineLoopValidator.class);
    private static final int MAX_CYCLES_TO_REPORT = 10;

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping intra-pipeline loop validation.");
            return errors;
        }
        final String currentClusterName = clusterConfig.clusterName();

        LOG.debug("Performing intra-pipeline loop validation for cluster: {}", currentClusterName);

        if (clusterConfig.pipelineGraphConfig() == null || clusterConfig.pipelineGraphConfig().pipelines() == null) {
            LOG.debug("No pipeline graph or pipelines to validate for loops in cluster: {}", currentClusterName);
            return errors;
        }

        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();

            if (pipeline == null || pipeline.pipelineSteps() == null || pipeline.pipelineSteps().isEmpty()) {
                LOG.debug("Pipeline '{}' is null, has no steps, or steps map is null. Skipping loop detection for it.", pipelineName);
                continue;
            }

            Graph<String, DefaultEdge> pipelineStepGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

            for (Map.Entry<String, PipelineStepConfig> stepMapEntry : pipeline.pipelineSteps().entrySet()) {
                String stepKey = stepMapEntry.getKey();
                PipelineStepConfig step = stepMapEntry.getValue();
                if (step != null && step.stepName() != null && !step.stepName().isBlank()) {
                    if (!stepKey.equals(step.stepName())) {
                        errors.add(String.format("Pipeline '%s', Step key '%s' does not match stepName '%s'.",
                                pipelineName, stepKey, step.stepName()));
                    }
                    pipelineStepGraph.addVertex(step.stepName());
                } else {
                    errors.add(String.format("Pipeline '%s' contains a step with a null/blank ID or a null step object for key '%s'. Skipping for loop detection graph.", pipelineName, stepKey));
                }
            }

            for (PipelineStepConfig publishingStep : pipeline.pipelineSteps().values()) {
                if (publishingStep == null || publishingStep.stepName() == null || publishingStep.stepName().isBlank() || publishingStep.outputs() == null) {
                    continue;
                }

                for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : publishingStep.outputs().entrySet()) {
                    PipelineStepConfig.OutputTarget outputTarget = outputEntry.getValue();

                    if (outputTarget != null && outputTarget.transportType() == TransportType.KAFKA && outputTarget.kafkaTransport() != null) {
                        KafkaTransportConfig pubKafkaConfig = outputTarget.kafkaTransport();
                        if (pubKafkaConfig.topic() == null || pubKafkaConfig.topic().isBlank()) {
                            continue;
                        }

                        String publishedTopicName = resolvePattern(
                                pubKafkaConfig.topic(),
                                publishingStep,
                                pipelineName,
                                currentClusterName
                        );

                        if (publishedTopicName == null || publishedTopicName.isBlank()) {
                            continue;
                        }

                        for (PipelineStepConfig listeningStep : pipeline.pipelineSteps().values()) {
                            if (listeningStep == null || listeningStep.stepName() == null || listeningStep.stepName().isBlank() || listeningStep.kafkaInputs() == null) {
                                continue;
                            }

                            // Check if listeningStep consumes the publishedTopicName
                            boolean listensToPublishedTopic = false;
                            for (KafkaInputDefinition inputDef : listeningStep.kafkaInputs()) {
                                if (inputDef.listenTopics() != null) {
                                    for (String listenTopicPattern : inputDef.listenTopics()) {
                                        String resolvedListenTopic = resolvePattern(
                                            listenTopicPattern,
                                            listeningStep, // context of the listening step for resolution
                                            pipelineName,
                                            currentClusterName
                                        );
                                        if (publishedTopicName.equals(resolvedListenTopic)) {
                                            listensToPublishedTopic = true;
                                            break;
                                        }
                                    }
                                }
                                if (listensToPublishedTopic) break;
                            }

                            if (listensToPublishedTopic) {
                                if (!pipelineStepGraph.containsVertex(publishingStep.stepName()) ||
                                    !pipelineStepGraph.containsVertex(listeningStep.stepName())) {
                                    LOG.warn("Vertex missing for intra-pipeline edge: {} -> {} in pipeline {}. This should not happen if vertices were added correctly.",
                                            publishingStep.stepName(), listeningStep.stepName(), pipelineName);
                                    continue;
                                }
                                try {
                                    if (!pipelineStepGraph.containsEdge(publishingStep.stepName(), listeningStep.stepName())) {
                                        pipelineStepGraph.addEdge(publishingStep.stepName(), listeningStep.stepName());
                                        LOG.trace("Added intra-pipeline edge from '{}' to '{}' via topic '{}' in pipeline '{}'",
                                                publishingStep.stepName(), listeningStep.stepName(), publishedTopicName, pipelineName);
                                    }
                                } catch (IllegalArgumentException e) {
                                    errors.add(String.format(
                                            "Error building graph for pipeline '%s': Could not add edge between '%s' and '%s'. Error: %s",
                                            pipelineName, publishingStep.stepName(), listeningStep.stepName(), e.getMessage()));
                                    LOG.warn("Error adding edge to graph for pipeline {}: {}", pipelineName, e.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            if (pipelineStepGraph.vertexSet().size() > 0 && !pipelineStepGraph.edgeSet().isEmpty()) {
                JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(pipelineStepGraph);
                List<List<String>> cycles = cycleFinder.findSimpleCycles();

                if (!cycles.isEmpty()) {
                    LOG.warn("Found {} simple intra-pipeline cycle(s) in pipeline '{}' (cluster '{}'). Reporting up to {}.",
                            cycles.size(), pipelineName, currentClusterName, MAX_CYCLES_TO_REPORT);
                    for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                        List<String> cyclePath = cycles.get(i);
                        String pathString = String.join(" -> ", cyclePath);
                        if (!cyclePath.isEmpty()) {
                            pathString += " -> " + cyclePath.get(0);
                        }
                        errors.add(String.format(
                                "Intra-pipeline loop detected in Kafka data flow within pipeline '%s' (cluster '%s'). Cycle path: [%s].",
                                pipelineName, currentClusterName, pathString));
                    }
                    if (cycles.size() > MAX_CYCLES_TO_REPORT) {
                        errors.add(String.format(
                                "Pipeline '%s' (cluster '%s') has more than %d intra-pipeline cycles (%d total). Only the first %d are reported.",
                                pipelineName, currentClusterName, MAX_CYCLES_TO_REPORT, cycles.size(), MAX_CYCLES_TO_REPORT));
                    }
                } else {
                    LOG.debug("No intra-pipeline Kafka loops detected in pipeline: {}", pipelineName);
                }
            } else {
                LOG.debug("Intra-pipeline step graph for pipeline '{}' is empty or has no edges. No loop detection performed.", pipelineName);
            }
        }
        return errors;
    }

    private String resolvePattern(String topicStringInConfig, PipelineStepConfig step, String pipelineName, String clusterName) {
        if (topicStringInConfig == null || topicStringInConfig.isBlank()) {
            return null;
        }
        String stepNameForResolve = (step != null && step.stepName() != null) ? step.stepName() : "unknown-step";

        String resolved = topicStringInConfig
                .replace("${pipelineName}", pipelineName)
                .replace("${stepName}", stepNameForResolve)
                .replace("${clusterName}", clusterName);

        if (resolved.contains("${")) {
            LOG.trace("Topic string '{}' for step '{}' in pipeline '{}' could not be fully resolved: '{}'.",
                    topicStringInConfig, stepNameForResolve, pipelineName, resolved);
            return resolved;
        }
        return resolved;
    }
}