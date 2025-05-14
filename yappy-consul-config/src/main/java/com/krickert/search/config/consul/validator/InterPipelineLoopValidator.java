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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors; // For formatting cycle paths


@Singleton
public class InterPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(InterPipelineLoopValidator.class);
    private static final int MAX_CYCLES_TO_REPORT = 10; // Example limit

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping inter-pipeline loop validation.");
            return errors;
        }

        LOG.debug("Performing inter-pipeline loop validation for cluster: {}", clusterConfig.clusterName());

        if (clusterConfig.pipelineGraphConfig() == null ||
                clusterConfig.pipelineGraphConfig().pipelines() == null ||
                clusterConfig.pipelineGraphConfig().pipelines().isEmpty()) {
            LOG.debug("No pipeline graph or pipelines to validate for inter-pipeline loops in cluster: {}", clusterConfig.clusterName());
            return errors;
        }

        Map<String, PipelineConfig> pipelinesMap = clusterConfig.pipelineGraphConfig().pipelines();
        Graph<String, DefaultEdge> interPipelineGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

        // Add all valid pipeline names as vertices
        for (String pipelineName : pipelinesMap.keySet()) {
            if (pipelineName != null && !pipelineName.isBlank()) {
                interPipelineGraph.addVertex(pipelineName);
            } else {
                LOG.warn("A pipeline in cluster '{}' has a null or blank map key. Skipping for inter-pipeline loop detection.", clusterConfig.clusterName());
            }
        }

        // Determine edges based on Kafka topic publishing and listening
        for (Map.Entry<String, PipelineConfig> sourcePipelineEntry : pipelinesMap.entrySet()) {
            String sourcePipelineName = sourcePipelineEntry.getKey();
            PipelineConfig sourcePipeline = sourcePipelineEntry.getValue();

            if (sourcePipeline == null || sourcePipeline.pipelineSteps() == null || sourcePipelineName == null || sourcePipelineName.isBlank()) {
                continue;
            }

            Set<String> topicsPublishedBySourcePipeline = new HashSet<>();
            for (PipelineStepConfig sourceStep : sourcePipeline.pipelineSteps().values()) {
                if (sourceStep != null && sourceStep.transportType() == TransportType.KAFKA) {
                    KafkaTransportConfig kafkaConfig = sourceStep.kafkaConfig();
                    if (kafkaConfig != null && kafkaConfig.publishTopicPattern() != null && !kafkaConfig.publishTopicPattern().isBlank()) {
                        // For loop detection, we need concrete topic names.
                        // If publishTopicPattern is a direct name, add it.
                        // If it's a pattern, we need to resolve it to concrete names or have a convention.
                        // For simplicity here, we'll assume patterns that are direct names or can be resolved simply.
                        String resolvedPublishTopic = resolvePattern(kafkaConfig.publishTopicPattern(),
                                sourceStep, sourcePipelineName, clusterConfig.clusterName());
                        if (resolvedPublishTopic != null && !resolvedPublishTopic.isBlank()) {
                            topicsPublishedBySourcePipeline.add(resolvedPublishTopic);
                        }
                    }
                }
            }

            if (topicsPublishedBySourcePipeline.isEmpty()) {
                continue; // This source pipeline publishes to no relevant Kafka topics
            }

            // Check all other pipelines (including itself for self-loops if that's possible via Kafka)
            for (Map.Entry<String, PipelineConfig> targetPipelineEntry : pipelinesMap.entrySet()) {
                String targetPipelineName = targetPipelineEntry.getKey();
                PipelineConfig targetPipeline = targetPipelineEntry.getValue();

                if (targetPipeline == null || targetPipeline.pipelineSteps() == null || targetPipelineName == null || targetPipelineName.isBlank()) {
                    continue;
                }

                // Don't add self-loops at the pipeline graph level if already handled by intra-pipeline check,
                // unless a pipeline truly publishes to a topic it itself consumes as an entry point.
                // For inter-pipeline, we are interested if SourcePipeline -> TargetPipeline.
                // If sourcePipelineName.equals(targetPipelineName), this indicates a pipeline might publish to a topic
                // that one of its own steps listens to as an entry. This could be valid or a loop.
                // The graph will detect if adding an edge P1 -> P1 creates a cycle.

                for (PipelineStepConfig targetStep : targetPipeline.pipelineSteps().values()) {
                    if (targetStep != null && targetStep.transportType() == TransportType.KAFKA) {
                        KafkaTransportConfig kafkaConfig = targetStep.kafkaConfig();
                        if (kafkaConfig != null && kafkaConfig.listenTopics() != null) {
                            for (String listenedTopic : kafkaConfig.listenTopics()) {
                                if (listenedTopic != null && !listenedTopic.isBlank() && topicsPublishedBySourcePipeline.contains(listenedTopic)) {
                                    // Check if vertices exist before adding edge, though addVertex loop should cover it.
                                    if (!interPipelineGraph.containsVertex(sourcePipelineName) || !interPipelineGraph.containsVertex(targetPipelineName)) {
                                        LOG.warn("Skipping edge from {} to {} as one or both vertices not in graph.", sourcePipelineName, targetPipelineName);
                                        continue;
                                    }
                                    // Add edge if not already present (DefaultDirectedGraph handles multiple edges if allowed, but for cycles one is enough)
                                    if (!interPipelineGraph.containsEdge(sourcePipelineName, targetPipelineName)) {
                                        try {
                                            interPipelineGraph.addEdge(sourcePipelineName, targetPipelineName);
                                            LOG.trace("Added inter-pipeline edge from '{}' to '{}' via topic '{}'",
                                                    sourcePipelineName, targetPipelineName, listenedTopic);
                                        } catch (IllegalArgumentException e) { // e.g. if vertices are not in graph
                                            errors.add(String.format(
                                                    "Error building inter-pipeline graph for cluster '%s': Could not add edge between pipeline '%s' and '%s'. Error: %s",
                                                    clusterConfig.clusterName(), sourcePipelineName, targetPipelineName, e.getMessage()));
                                            LOG.warn("Error adding edge to inter-pipeline graph for cluster {}: {}", clusterConfig.clusterName(), e.getMessage());
                                        }
                                    }
                                    // Found a link, no need to check other listen topics of this targetStep for the same sourcePipeline link
                                    // or other publish topics of the sourcePipeline if multiple matched.
                                    // Break here or collect all linking topics if needed for more detailed error.
                                    // For cycle detection, one edge is sufficient.
                                }
                            }
                        }
                    }
                }
            }
        }

        // Find and report cycles
        if (interPipelineGraph.vertexSet().size() > 0) { // Ensure graph is not empty
            JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(interPipelineGraph);
            List<List<String>> cycles = cycleFinder.findSimpleCycles();

            if (!cycles.isEmpty()) {
                LOG.warn("Found {} simple inter-pipeline cycle(s) in cluster '{}'. Reporting up to {}.",
                        cycles.size(), clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT);

                for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                    List<String> cyclePath = cycles.get(i);
                    String pathString = cyclePath.stream().collect(Collectors.joining(" -> "));
                    if (!cyclePath.isEmpty()) { // Add the loop back to the start
                        pathString += " -> " + cyclePath.get(0);
                    }

                    String errorMessage = String.format(
                            "Inter-pipeline loop detected in Kafka data flow in cluster '%s'. Cycle path: [%s]",
                            clusterConfig.clusterName(), pathString);
                    errors.add(errorMessage);
                    LOG.warn(errorMessage); // Log each detected cycle
                }
                if (cycles.size() > MAX_CYCLES_TO_REPORT) {
                    String tooManyCyclesMessage = String.format(
                            "Cluster '%s' has more than %d inter-pipeline cycles (%d total). Only the first %d are reported.",
                            clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT, cycles.size(), MAX_CYCLES_TO_REPORT);
                    errors.add(tooManyCyclesMessage);
                    LOG.warn(tooManyCyclesMessage);
                }
            } else {
                LOG.debug("No inter-pipeline loops detected in cluster: {}", clusterConfig.clusterName());
            }
        } else {
            LOG.debug("Inter-pipeline graph is empty for cluster: {}. No loop detection performed.", clusterConfig.clusterName());
        }
        return errors;
    }

    /**
     * Resolves a publish topic pattern to a concrete topic name.
     * This is a basic implementation. More sophisticated resolution might be needed
     * if patterns are complex or rely on runtime data not available at validation.
     */
    private String resolvePattern(String pattern, PipelineStepConfig step, String pipelineName, String clusterName) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        // Simple replacement for known placeholders. Add more as needed.
        String resolved = pattern
                .replace("${pipelineId}", pipelineName)
                .replace("${stepId}", step.pipelineStepId());
        // Add more replacements if you use other variables like ${clusterId}, ${implementationId}, etc.

        if (resolved.contains("${")) { // If it still contains unresolved placeholders
            LOG.trace("Pattern '{}' for step '{}' still contains unresolved placeholders after basic resolution: '{}'. Treating as unresolved.",
                    pattern, step.pipelineStepId(), resolved);
            // Depending on policy, an unresolved pattern might be an error itself, or it might mean it cannot be validated
            // against an explicit list of allowed topic *names*.
            // For loop detection based on specific topic names, an unresolved pattern cannot form a concrete edge
            // unless the loop detector also understands patterns.
            return pattern; // Return original pattern if it can't be fully resolved to a concrete name.
            // Or return null if only concrete names are expected for this validation.
            // Let's return the original pattern for now, and it won't match concrete listen topics unless identical.
        }
        return resolved;
    }
}