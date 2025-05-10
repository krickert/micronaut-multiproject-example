package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Singleton
public class IntraPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(IntraPipelineLoopValidator.class);

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        if (clusterConfig == null) {
            // This check is more for robustness of the rule itself;
            // DefaultConfigurationValidator should ideally handle null clusterConfig.
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

            // Build the graph for the current pipeline
            // Nodes are step IDs. Edges represent potential data flow via Kafka topics.
            Graph<String, DefaultEdge> pipelineStepGraph = new DefaultDirectedGraph<>(DefaultEdge.class);

            // Add all steps as nodes
            for (String stepId : pipeline.pipelineSteps().keySet()) {
                if (stepId != null && !stepId.isBlank()) { // Ensure valid stepId before adding
                    pipelineStepGraph.addVertex(stepId);
                } else {
                    // This should ideally be caught by ReferentialIntegrityValidator
                    LOG.warn("Pipeline '{}' contains a step with a null or blank ID in the map key. Skipping this entry for loop detection.", pipelineName);
                }
            }

            // Determine edges based on Kafka publish/listen topics within this pipeline
            for (PipelineStepConfig publishingStep : pipeline.pipelineSteps().values()) {
                if (publishingStep == null || publishingStep.pipelineStepId() == null || publishingStep.pipelineStepId().isBlank()) {
                    continue; // Invalid step, should be caught by referential integrity
                }
                if (publishingStep.kafkaPublishTopics() == null || publishingStep.kafkaPublishTopics().isEmpty()) {
                    continue; // No topics to publish to, so no outgoing edges from this step via Kafka
                }

                for (KafkaPublishTopic pubTopic : publishingStep.kafkaPublishTopics()) {
                    if (pubTopic == null || pubTopic.topic() == null || pubTopic.topic().isBlank()) {
                        continue; // Invalid publish topic entry
                    }
                    String topicName = pubTopic.topic();

                    // Find other steps in the *same pipeline* that listen to this topic
                    for (PipelineStepConfig listeningStep : pipeline.pipelineSteps().values()) {
                        if (listeningStep == null || listeningStep.pipelineStepId() == null || listeningStep.pipelineStepId().isBlank()) {
                            continue; // Invalid step
                        }
                        if (listeningStep.kafkaListenTopics() != null && listeningStep.kafkaListenTopics().contains(topicName)) {
                            // Add a directed edge from publishingStep to listeningStep
                            // JGraphT's addEdge is fine even if vertices are already present.
                            // DefaultDirectedGraph allows self-loops by default.
                            try {
                                pipelineStepGraph.addEdge(publishingStep.pipelineStepId(), listeningStep.pipelineStepId());
                                LOG.trace("Added edge from {} to {} via topic {} in pipeline {}",
                                        publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), topicName, pipelineName);
                            } catch (IllegalArgumentException e) {
                                // This might happen if a stepId was null/blank and not added as a vertex,
                                // or if there's some other issue with the graph.
                                errors.add(String.format(
                                    "Error building graph for pipeline '%s': Could not add edge between '%s' and '%s'. Ensure step IDs are valid. Error: %s",
                                    pipelineName, publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), e.getMessage()));
                                LOG.warn("Error adding edge to graph for pipeline {}: {}", pipelineName, e.getMessage());
                            }
                        }
                    }
                }
            }

            // Check for cycles in the constructed graph for this pipeline
            CycleDetector<String, DefaultEdge> cycleDetector = new CycleDetector<>(pipelineStepGraph);
            if (cycleDetector.detectCycles()) {
                // JGraphT's CycleDetector can find the specific nodes involved in cycles
                Set<String> cycleVertices = cycleDetector.findCycles();
                String cycleNodesStr = String.join(", ", cycleVertices);

                String errorMessage = String.format(
                        "Loop detected in Kafka data flow within pipeline '%s' in cluster '%s'. Steps involved in the cycle: [%s]",
                        pipelineName, clusterConfig.clusterName(), cycleNodesStr);
                errors.add(errorMessage);
                LOG.warn(errorMessage);
            } else {
                LOG.debug("No intra-pipeline loops detected in pipeline: {}", pipelineName);
            }
        }
        return errors;
    }
}
