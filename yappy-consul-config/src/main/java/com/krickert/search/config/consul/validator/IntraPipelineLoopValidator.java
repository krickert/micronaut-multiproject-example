package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.jgrapht.Graph;
// Import an algorithm that finds all simple cycles
import org.jgrapht.alg.cycle.JohnsonSimpleCycles; // Or TarjanSimpleCycles, etc.
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
// Removed import for org.jgrapht.alg.cycle.CycleDetector; as it's being replaced
// Removed import for java.util.Set; if only used by CycleDetector's result
import java.util.function.Function;
import java.util.stream.Collectors; // For formatting cycle paths

@Singleton
public class IntraPipelineLoopValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(IntraPipelineLoopValidator.class);

    // You could make N configurable, e.g., via application properties
    private static final int MAX_CYCLES_TO_REPORT = 10; // Example limit

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

            for (String stepId : pipeline.pipelineSteps().keySet()) {
                if (stepId != null && !stepId.isBlank()) {
                    pipelineStepGraph.addVertex(stepId);
                } else {
                    LOG.warn("Pipeline '{}' contains a step with a null or blank ID in the map key. Skipping this entry for loop detection.", pipelineName);
                }
            }

            for (PipelineStepConfig publishingStep : pipeline.pipelineSteps().values()) {
                if (publishingStep == null || publishingStep.pipelineStepId() == null || publishingStep.pipelineStepId().isBlank()) {
                    continue;
                }
                if (publishingStep.kafkaPublishTopics() == null || publishingStep.kafkaPublishTopics().isEmpty()) {
                    continue;
                }

                for (KafkaPublishTopic pubTopic : publishingStep.kafkaPublishTopics()) {
                    if (pubTopic == null || pubTopic.topic() == null || pubTopic.topic().isBlank()) {
                        continue;
                    }
                    String topicName = pubTopic.topic();

                    for (PipelineStepConfig listeningStep : pipeline.pipelineSteps().values()) {
                        if (listeningStep == null || listeningStep.pipelineStepId() == null || listeningStep.pipelineStepId().isBlank()) {
                            continue;
                        }
                        if (listeningStep.kafkaListenTopics() != null && listeningStep.kafkaListenTopics().contains(topicName)) {
                            try {
                                pipelineStepGraph.addEdge(publishingStep.pipelineStepId(), listeningStep.pipelineStepId());
                                LOG.trace("Added edge from {} to {} via topic {} in pipeline {}",
                                        publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), topicName, pipelineName);
                            } catch (IllegalArgumentException e) {
                                errors.add(String.format(
                                    "Error building graph for pipeline '%s': Could not add edge between '%s' and '%s'. Ensure step IDs are valid. Error: %s",
                                    pipelineName, publishingStep.pipelineStepId(), listeningStep.pipelineStepId(), e.getMessage()));
                                LOG.warn("Error adding edge to graph for pipeline {}: {}", pipelineName, e.getMessage());
                            }
                        }
                    }
                }
            }

            // Use an algorithm that finds all simple cycles
            JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(pipelineStepGraph);
            List<List<String>> cycles = cycleFinder.findSimpleCycles();

            if (!cycles.isEmpty()) {
                LOG.warn("Found {} simple cycle(s) in pipeline '{}' in cluster '{}'. Reporting up to {}.",
                         cycles.size(), pipelineName, clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT);

                for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                    List<String> cyclePath = cycles.get(i);
                    // Format the cycle path for readability, e.g., A -> B -> C -> A
                    String pathString = cyclePath.stream().collect(Collectors.joining(" -> "));
                    if (!cyclePath.isEmpty()) { // Add the first element at the end to show the loop closure
                        pathString += " -> " + cyclePath.get(0);
                    }

                    String errorMessage = String.format(
                            "Intra-pipeline loop detected in Kafka data flow within pipeline '%s' (cluster '%s'). Cycle path: [%s]",
                            pipelineName, clusterConfig.clusterName(), pathString);
                    errors.add(errorMessage);
                    LOG.warn(errorMessage); // Log each distinct cycle path found
                }
                if (cycles.size() > MAX_CYCLES_TO_REPORT) {
                    String tooManyCyclesMessage = String.format(
                        "Pipeline '%s' (cluster '%s') has more than %d cycles (%d total). Only the first %d are reported.",
                        pipelineName, clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT, cycles.size(), MAX_CYCLES_TO_REPORT);
                    errors.add(tooManyCyclesMessage);
                    LOG.warn(tooManyCyclesMessage);
                }
            } else {
                LOG.debug("No intra-pipeline loops detected in pipeline: {}", pipelineName);
            }
        }
        return errors;
    }
}