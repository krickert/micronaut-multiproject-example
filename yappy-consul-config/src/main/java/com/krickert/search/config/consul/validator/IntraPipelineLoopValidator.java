package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles; // Or TarjanSimpleCycles, etc.
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors; // For formatting cycle paths

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

        LOG.debug("Performing intra-pipeline loop validation for cluster: {}", clusterConfig.clusterName());

        if (clusterConfig.pipelineGraphConfig() == null || clusterConfig.pipelineGraphConfig().pipelines() == null) {
            LOG.debug("No pipeline graph or pipelines to validate for loops in cluster: {}", clusterConfig.clusterName());
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

            // Add all valid step IDs as vertices for the current pipeline
            for (String stepId : pipeline.pipelineSteps().keySet()) {
                if (stepId != null && !stepId.isBlank()) {
                    pipelineStepGraph.addVertex(stepId);
                } else {
                    // This case should ideally be caught by PipelineConfig validation
                    LOG.warn("Pipeline '{}' contains a step with a null or blank ID in the map key. Skipping this entry for loop detection.", pipelineName);
                }
            }

            // Build edges based on Kafka publish/listen patterns within the same pipeline
            for (PipelineStepConfig publishingStep : pipeline.pipelineSteps().values()) {
                if (publishingStep == null || publishingStep.pipelineStepId() == null || publishingStep.pipelineStepId().isBlank()) {
                    continue;
                }

                // Only consider Kafka steps as publishers for this Kafka-based loop detection
                if (publishingStep.transportType() == TransportType.KAFKA) {
                    KafkaTransportConfig pubKafkaConfig = publishingStep.kafkaConfig();
                    if (pubKafkaConfig == null || pubKafkaConfig.publishTopicPattern() == null || pubKafkaConfig.publishTopicPattern().isBlank()) {
                        continue; // This step doesn't publish to a Kafka topic via pattern
                    }

                    // For intra-pipeline loops, we need to know the concrete topic name this step publishes to.
                    // Resolve the pattern using the context of the publishing step itself.
                    String publishedTopicName = resolvePattern(
                            pubKafkaConfig.publishTopicPattern(),
                            publishingStep,
                            pipelineName, // Current pipeline name
                            clusterConfig.clusterName()
                    );

                    if (publishedTopicName == null || publishedTopicName.isBlank()) {
                        continue; // Pattern couldn't be resolved to a usable topic name
                    }

                    // Now check all other steps in the *same* pipeline to see if they listen to this topic
                    for (PipelineStepConfig listeningStep : pipeline.pipelineSteps().values()) {
                        if (listeningStep == null || listeningStep.pipelineStepId() == null || listeningStep.pipelineStepId().isBlank()) {
                            continue;
                        }

                        // Only consider Kafka steps as listeners
                        if (listeningStep.transportType() == TransportType.KAFKA) {
                            KafkaTransportConfig listenKafkaConfig = listeningStep.kafkaConfig();
                            if (listenKafkaConfig != null && listenKafkaConfig.listenTopics() != null &&
                                    listenKafkaConfig.listenTopics().contains(publishedTopicName)) {

                                // Ensure vertices exist before adding edge
                                if (!pipelineStepGraph.containsVertex(publishingStep.pipelineStepId()) ||
                                        !pipelineStepGraph.containsVertex(listeningStep.pipelineStepId())) {
                                    LOG.warn("Vertex missing for intra-pipeline edge: {} -> {} in pipeline {}",
                                            publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), pipelineName);
                                    continue;
                                }

                                try {
                                    // Add edge from publishing step to listening step
                                    // if it doesn't already exist (to keep graph simple for cycle detection)
                                    if (!pipelineStepGraph.containsEdge(publishingStep.pipelineStepId(), listeningStep.pipelineStepId())) {
                                        pipelineStepGraph.addEdge(publishingStep.pipelineStepId(), listeningStep.pipelineStepId());
                                        LOG.trace("Added intra-pipeline edge from {} to {} via topic {} in pipeline {}",
                                                publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), publishedTopicName, pipelineName);
                                    }
                                } catch (IllegalArgumentException e) { // e.g., if vertices are not in graph (should be caught above)
                                    errors.add(String.format(
                                            "Error building graph for pipeline '%s': Could not add edge between '%s' and '%s'. Error: %s",
                                            pipelineName, publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), e.getMessage()));
                                    LOG.warn("Error adding edge to graph for pipeline {}: {}", pipelineName, e.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            // Detect cycles in the graph for the current pipeline
            if (pipelineStepGraph.vertexSet().size() > 0) { // Ensure graph is not empty
                JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(pipelineStepGraph);
                List<List<String>> cycles = cycleFinder.findSimpleCycles();

                if (!cycles.isEmpty()) {
                    LOG.warn("Found {} simple intra-pipeline cycle(s) in pipeline '{}' (cluster '{}'). Reporting up to {}.",
                            cycles.size(), pipelineName, clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT);

                    for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                        List<String> cyclePath = cycles.get(i);
                        String pathString = cyclePath.stream().collect(Collectors.joining(" -> "));
                        if (!cyclePath.isEmpty()) { // Add the first element at the end to show the loop closure
                            pathString += " -> " + cyclePath.get(0);
                        }
                        String errorMessage = String.format(
                                "Intra-pipeline loop detected in Kafka data flow within pipeline '%s' (cluster '%s'). Cycle path: [%s]",
                                pipelineName, clusterConfig.clusterName(), pathString);
                        errors.add(errorMessage);
                        LOG.warn(errorMessage);
                    }
                    if (cycles.size() > MAX_CYCLES_TO_REPORT) {
                        String tooManyCyclesMessage = String.format(
                                "Pipeline '%s' (cluster '%s') has more than %d intra-pipeline cycles (%d total). Only the first %d are reported.",
                                pipelineName, clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT, cycles.size(), MAX_CYCLES_TO_REPORT);
                        errors.add(tooManyCyclesMessage);
                        LOG.warn(tooManyCyclesMessage);
                    }
                } else {
                    LOG.debug("No intra-pipeline Kafka loops detected in pipeline: {}", pipelineName);
                }
            } else {
                LOG.debug("Intra-pipeline step graph is empty for pipeline: {}. No loop detection performed.", pipelineName);
            }
        }
        return errors;
    }

    /**
     * Resolves a publish topic pattern to a concrete topic name.
     * This is a basic implementation. More sophisticated resolution might be needed.
     */
    private String resolvePattern(String pattern, PipelineStepConfig step, String pipelineName, String clusterName) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String resolved = pattern
                .replace("${pipelineId}", pipelineName)
                .replace("${stepId}", step.pipelineStepId())
                .replace("${clusterId}", clusterName); // Example

        if (resolved.contains("${")) {
            LOG.trace("Pattern '{}' for step '{}' in pipeline '{}' could not be fully resolved: '{}'. " +
                            "For intra-pipeline loop detection, this pattern will only match if a listener uses the exact same unresolved string.",
                    pattern, step.pipelineStepId(), pipelineName, resolved);
            return pattern; // Return original pattern if not fully resolved, to allow exact string match
        }
        return resolved;
    }
}