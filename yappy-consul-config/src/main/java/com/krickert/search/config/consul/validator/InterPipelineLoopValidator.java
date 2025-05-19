package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

@Singleton
public class InterPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(InterPipelineLoopValidator.class);
    private static final int MAX_CYCLES_TO_REPORT = 10;

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping inter-pipeline loop validation.");
            return errors;
        }
        final String currentClusterName = clusterConfig.clusterName();

        LOG.debug("Performing inter-pipeline loop validation for cluster: {}", currentClusterName);

        if (clusterConfig.pipelineGraphConfig() == null ||
                clusterConfig.pipelineGraphConfig().pipelines() == null ||
                clusterConfig.pipelineGraphConfig().pipelines().isEmpty()) {
            LOG.debug("No pipeline graph or pipelines to validate for inter-pipeline loops in cluster: {}", currentClusterName);
            return errors;
        }

        Map<String, PipelineConfig> pipelinesMap = clusterConfig.pipelineGraphConfig().pipelines();
        Graph<String, DefaultEdge> interPipelineGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

        for (String pipelineMapKey : pipelinesMap.keySet()) {
            PipelineConfig pipeline = pipelinesMap.get(pipelineMapKey);
            if (pipeline != null && pipeline.name() != null && !pipeline.name().isBlank()) {
                if (!pipelineMapKey.equals(pipeline.name())) {
                    errors.add(String.format("Cluster '%s': Pipeline map key '%s' does not match pipeline name '%s'.",
                            currentClusterName, pipelineMapKey, pipeline.name()));
                }
                interPipelineGraph.addVertex(pipeline.name());
            } else {
                errors.add(String.format("A pipeline in cluster '%s' has a null/blank map key, null pipeline object, or null/blank name. Map key: '%s'. Skipping for inter-pipeline loop detection graph.",
                        currentClusterName, pipelineMapKey));
            }
        }

        // Map to track which topics are published by which pipeline
        Map<String, Set<String>> topicsPublishedByPipeline = new HashMap<>();

        // First pass: collect all topics published by each pipeline
        for (Map.Entry<String, PipelineConfig> sourcePipelineEntry : pipelinesMap.entrySet()) {
            String sourcePipelineKey = sourcePipelineEntry.getKey();
            PipelineConfig sourcePipeline = sourcePipelineEntry.getValue();

            if (sourcePipeline == null || sourcePipeline.pipelineSteps() == null || sourcePipeline.name() == null || !sourcePipelineKey.equals(sourcePipeline.name())) {
                continue;
            }
            String sourcePipelineName = sourcePipeline.name();

            Set<String> topicsPublishedBySourcePipeline = new HashSet<>();
            for (PipelineStepConfig sourceStep : sourcePipeline.pipelineSteps().values()) {
                if (sourceStep == null || sourceStep.outputs() == null) {
                    continue;
                }
                for (PipelineStepConfig.OutputTarget outputTarget : sourceStep.outputs().values()) {
                    if (outputTarget != null && outputTarget.transportType() == TransportType.KAFKA && outputTarget.kafkaTransport() != null) {
                        KafkaTransportConfig kafkaConfig = outputTarget.kafkaTransport();
                        if (kafkaConfig.topic() != null && !kafkaConfig.topic().isBlank()) {
                            String resolvedPublishTopic = resolvePattern(kafkaConfig.topic(),
                                    sourceStep, sourcePipelineName, currentClusterName);
                            if (resolvedPublishTopic != null && !resolvedPublishTopic.isBlank()) {
                                topicsPublishedBySourcePipeline.add(resolvedPublishTopic);
                            }
                        }
                    }
                }
            }

            if (!topicsPublishedBySourcePipeline.isEmpty()) {
                topicsPublishedByPipeline.put(sourcePipelineName, topicsPublishedBySourcePipeline);
            }
        }

        // Second pass: build the graph based on topic connections
        for (Map.Entry<String, Set<String>> sourceEntry : topicsPublishedByPipeline.entrySet()) {
            String sourcePipelineName = sourceEntry.getKey();
            Set<String> publishedTopics = sourceEntry.getValue();

            for (Map.Entry<String, PipelineConfig> targetPipelineEntry : pipelinesMap.entrySet()) {
                String targetPipelineKey = targetPipelineEntry.getKey();
                PipelineConfig targetPipeline = targetPipelineEntry.getValue();

                if (targetPipeline == null || targetPipeline.pipelineSteps() == null || targetPipeline.name() == null || !targetPipelineKey.equals(targetPipeline.name())) {
                    continue;
                }
                String targetPipelineName = targetPipeline.name();

                // Don't skip self-loops for the same pipeline - we want to detect when a pipeline
                // publishes to and listens from the same topic
                if (sourcePipelineName.equals(targetPipelineName)) {
                    LOG.debug("Checking self-loop for pipeline '{}' in cluster '{}'",
                            sourcePipelineName, currentClusterName);
                    // continue; - removed to allow self-loop detection
                }

                boolean linkFoundForTargetPipeline = false;
                for (PipelineStepConfig targetStep : targetPipeline.pipelineSteps().values()) {
                    if (targetStep == null || targetStep.kafkaInputs() == null) {
                        continue;
                    }
                    for (KafkaInputDefinition inputDef : targetStep.kafkaInputs()) {
                        if (inputDef.listenTopics() != null) {
                            for (String listenTopicPattern : inputDef.listenTopics()) {
                                String resolvedListenTopic = resolvePattern(
                                        listenTopicPattern,
                                        targetStep, // context of the listening step in the target pipeline
                                        targetPipelineName,
                                        currentClusterName
                                );
                                if (resolvedListenTopic != null && publishedTopics.contains(resolvedListenTopic)) {
                                    if (interPipelineGraph.containsVertex(sourcePipelineName) && interPipelineGraph.containsVertex(targetPipelineName)) {
                                        if (!interPipelineGraph.containsEdge(sourcePipelineName, targetPipelineName)) {
                                            try {
                                                interPipelineGraph.addEdge(sourcePipelineName, targetPipelineName);
                                                LOG.trace("Added inter-pipeline edge from '{}' to '{}' via topic '{}'",
                                                        sourcePipelineName, targetPipelineName, resolvedListenTopic);
                                            } catch (IllegalArgumentException e) {
                                                errors.add(String.format(
                                                        "Error building inter-pipeline graph for cluster '%s': Could not add edge between pipeline '%s' and '%s'. Error: %s",
                                                        currentClusterName, sourcePipelineName, targetPipelineName, e.getMessage()));
                                            }
                                        }
                                        linkFoundForTargetPipeline = true; // Edge added or already exists
                                        break; // Found a linking topic for this input definition
                                    }
                                }
                            }
                        }
                        if (linkFoundForTargetPipeline) break; // Found a link for this target step
                    }
                    if (linkFoundForTargetPipeline) break; // Found a link for this target pipeline
                }
            }
        }

        if (interPipelineGraph.vertexSet().size() > 0 && !interPipelineGraph.edgeSet().isEmpty()) {
            JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(interPipelineGraph);
            List<List<String>> cycles = cycleFinder.findSimpleCycles();

            if (!cycles.isEmpty()) {
                LOG.warn("Found {} simple inter-pipeline cycle(s) in cluster '{}'. Reporting up to {}.",
                        cycles.size(), currentClusterName, MAX_CYCLES_TO_REPORT);
                for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                    List<String> cyclePath = cycles.get(i);
                    String pathString = String.join(" -> ", cyclePath);
                    if (!cyclePath.isEmpty()) {
                        pathString += " -> " + cyclePath.get(0);
                    }
                    errors.add(String.format(
                            "Inter-pipeline loop detected in Kafka data flow in cluster '%s'. Cycle path: [%s].",
                            currentClusterName, pathString));
                }
                if (cycles.size() > MAX_CYCLES_TO_REPORT) {
                    errors.add(String.format(
                            "Cluster '%s' has more than %d inter-pipeline cycles (%d total). Only the first %d are reported.",
                            currentClusterName, MAX_CYCLES_TO_REPORT, cycles.size(), MAX_CYCLES_TO_REPORT));
                }
            } else {
                LOG.debug("No inter-pipeline loops detected in cluster: {}", currentClusterName);
            }
        } else {
            LOG.debug("Inter-pipeline graph for cluster '{}' is empty or has no edges. No loop detection performed.", currentClusterName);
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
            LOG.trace("Topic string '{}' for step '{}' in pipeline '{}', cluster '{}' could not be fully resolved: '{}'.",
                    topicStringInConfig, stepNameForResolve, pipelineName, clusterName, resolved);
            return resolved;
        }
        return resolved;
    }
}
