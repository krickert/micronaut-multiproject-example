package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph; // JGraphT specific
import org.jgrapht.alg.cycle.CycleDetector; // JGraphT specific
import org.jgrapht.graph.DefaultDirectedGraph; // JGraphT specific
import org.jgrapht.graph.DefaultEdge; // JGraphT specific
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Singleton
public class InterPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(InterPipelineLoopValidator.class);

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

        // 1. Add all pipeline names as vertices
        for (String pipelineName : pipelinesMap.keySet()) {
            if (pipelineName != null && !pipelineName.isBlank()) {
                interPipelineGraph.addVertex(pipelineName);
            } else {
                // This should be caught by ReferentialIntegrityValidator, but good to log if encountered
                LOG.warn("A pipeline in cluster '{}' has a null or blank map key. Skipping for inter-pipeline loop detection.", clusterConfig.clusterName());
            }
        }

        // 2. Determine edges between pipelines based on shared Kafka topics
        for (Map.Entry<String, PipelineConfig> sourcePipelineEntry : pipelinesMap.entrySet()) {
            String sourcePipelineName = sourcePipelineEntry.getKey();
            PipelineConfig sourcePipeline = sourcePipelineEntry.getValue();

            if (sourcePipeline == null || sourcePipeline.pipelineSteps() == null || sourcePipelineName == null || sourcePipelineName.isBlank()) {
                continue; // Skip invalid or empty source pipelines
            }

            // Collect all topics published by the sourcePipeline
            Set<String> topicsPublishedBySource = new HashSet<>();
            for (PipelineStepConfig sourceStep : sourcePipeline.pipelineSteps().values()) {
                if (sourceStep != null && sourceStep.kafkaPublishTopics() != null) {
                    for (KafkaPublishTopic pubTopic : sourceStep.kafkaPublishTopics()) {
                        if (pubTopic != null && pubTopic.topic() != null && !pubTopic.topic().isBlank()) {
                            topicsPublishedBySource.add(pubTopic.topic());
                        }
                    }
                }
            }

            if (topicsPublishedBySource.isEmpty()) {
                continue; // Source pipeline publishes no topics, cannot initiate an inter-pipeline link
            }

            // Now, check against all other pipelines (including itself, for pipeline-level self-loops via topics)
            for (Map.Entry<String, PipelineConfig> targetPipelineEntry : pipelinesMap.entrySet()) {
                String targetPipelineName = targetPipelineEntry.getKey();
                PipelineConfig targetPipeline = targetPipelineEntry.getValue();

                if (targetPipeline == null || targetPipeline.pipelineSteps() == null || targetPipelineName == null || targetPipelineName.isBlank()) {
                    continue; // Skip invalid or empty target pipelines
                }

                for (PipelineStepConfig targetStep : targetPipeline.pipelineSteps().values()) {
                    if (targetStep != null && targetStep.kafkaListenTopics() != null) {
                        for (String listenedTopic : targetStep.kafkaListenTopics()) {
                            if (listenedTopic != null && !listenedTopic.isBlank() && topicsPublishedBySource.contains(listenedTopic)) {
                                // Edge: sourcePipelineName -> targetPipelineName via listenedTopic
                                try {
                                    // addEdge returns the new edge if added, or null if the edge already exists (for this graph type)
                                    // or throws if vertices not present. Vertices should be present from step 1.
                                    DefaultEdge addedEdge = interPipelineGraph.addEdge(sourcePipelineName, targetPipelineName);
                                    if (addedEdge != null) { // Edge was newly added
                                         LOG.trace("Added inter-pipeline edge from '{}' to '{}' via topic '{}'",
                                                sourcePipelineName, targetPipelineName, listenedTopic);
                                    }
                                    // We only need one such link to establish dependency for cycle detection.
                                    // If multiple steps/topics link the same two pipelines, JGraphT DefaultDirectedGraph
                                    // won't add parallel edges unless configured. This is fine for cycle detection.
                                } catch (IllegalArgumentException e) {
                                    // Should not happen if vertices (pipeline names) were added correctly
                                    errors.add(String.format(
                                        "Error building inter-pipeline graph for cluster '%s': Could not add edge between pipeline '%s' and '%s'. Error: %s",
                                        clusterConfig.clusterName(), sourcePipelineName, targetPipelineName, e.getMessage()));
                                    LOG.warn("Error adding edge to inter-pipeline graph for cluster {}: {}", clusterConfig.clusterName(), e.getMessage());
                                }
                                // Once a link is found for this listenedTopic from sourcePipelineName to targetPipelineName,
                                // we could theoretically break from the inner loops for *this specific topic*,
                                // but continuing ensures all potential topic links contribute if needed for other analysis,
                                // though for cycle detection, one edge is enough.
                                // The current loop structure is fine.
                            }
                        }
                    }
                }
            }
        }

        // 3. Detect cycles in the inter-pipeline graph
        CycleDetector<String, DefaultEdge> cycleDetector = new CycleDetector<>(interPipelineGraph);
        if (cycleDetector.detectCycles()) {
            Set<String> cycleVertices = cycleDetector.findCycles(); // These are pipeline names involved in cycles
            String cyclePipelinesStr = String.join(", ", cycleVertices);

            String errorMessage = String.format(
                    "Inter-pipeline loop detected in Kafka data flow in cluster '%s'. Pipelines involved in a cycle: [%s]",
                    clusterConfig.clusterName(), cyclePipelinesStr);
            errors.add(errorMessage);
            LOG.warn("{} Inter-pipeline graph structure (edges): {}", errorMessage, interPipelineGraph.edgeSet());
        } else {
            LOG.debug("No inter-pipeline loops detected in cluster: {}", clusterConfig.clusterName());
        }
        return errors;
    }
}