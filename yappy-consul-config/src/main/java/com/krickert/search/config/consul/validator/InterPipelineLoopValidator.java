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

        for (String pipelineName : pipelinesMap.keySet()) {
            if (pipelineName != null && !pipelineName.isBlank()) {
                interPipelineGraph.addVertex(pipelineName);
            } else {
                LOG.warn("A pipeline in cluster '{}' has a null or blank map key. Skipping for inter-pipeline loop detection.", clusterConfig.clusterName());
            }
        }

        for (Map.Entry<String, PipelineConfig> sourcePipelineEntry : pipelinesMap.entrySet()) {
            String sourcePipelineName = sourcePipelineEntry.getKey();
            PipelineConfig sourcePipeline = sourcePipelineEntry.getValue();

            if (sourcePipeline == null || sourcePipeline.pipelineSteps() == null || sourcePipelineName == null || sourcePipelineName.isBlank()) {
                continue;
            }

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
                continue;
            }

            for (Map.Entry<String, PipelineConfig> targetPipelineEntry : pipelinesMap.entrySet()) {
                String targetPipelineName = targetPipelineEntry.getKey();
                PipelineConfig targetPipeline = targetPipelineEntry.getValue();

                if (targetPipeline == null || targetPipeline.pipelineSteps() == null || targetPipelineName == null || targetPipelineName.isBlank()) {
                    continue;
                }

                for (PipelineStepConfig targetStep : targetPipeline.pipelineSteps().values()) {
                    if (targetStep != null && targetStep.kafkaListenTopics() != null) {
                        for (String listenedTopic : targetStep.kafkaListenTopics()) {
                            if (listenedTopic != null && !listenedTopic.isBlank() && topicsPublishedBySource.contains(listenedTopic)) {
                                try {
                                    DefaultEdge addedEdge = interPipelineGraph.addEdge(sourcePipelineName, targetPipelineName);
                                    if (addedEdge != null) {
                                        LOG.trace("Added inter-pipeline edge from '{}' to '{}' via topic '{}'",
                                                sourcePipelineName, targetPipelineName, listenedTopic);
                                    }
                                } catch (IllegalArgumentException e) {
                                    errors.add(String.format(
                                            "Error building inter-pipeline graph for cluster '%s': Could not add edge between pipeline '%s' and '%s'. Error: %s",
                                            clusterConfig.clusterName(), sourcePipelineName, targetPipelineName, e.getMessage()));
                                    LOG.warn("Error adding edge to inter-pipeline graph for cluster {}: {}", clusterConfig.clusterName(), e.getMessage());
                                }
                            }
                        }
                    }
                }
            }
        }

        JohnsonSimpleCycles<String, DefaultEdge> cycleFinder = new JohnsonSimpleCycles<>(interPipelineGraph);
        List<List<String>> cycles = cycleFinder.findSimpleCycles();

        if (!cycles.isEmpty()) {
            LOG.warn("Found {} simple inter-pipeline cycle(s) in cluster '{}'. Reporting up to {}.",
                    cycles.size(), clusterConfig.clusterName(), MAX_CYCLES_TO_REPORT);

            for (int i = 0; i < Math.min(cycles.size(), MAX_CYCLES_TO_REPORT); i++) {
                List<String> cyclePath = cycles.get(i);
                String pathString = cyclePath.stream().collect(Collectors.joining(" -> "));
                if (!cyclePath.isEmpty()) {
                    pathString += " -> " + cyclePath.get(0);
                }

                String errorMessage = String.format(
                        "Inter-pipeline loop detected in Kafka data flow in cluster '%s'. Cycle path: [%s]",
                        clusterConfig.clusterName(), pathString);
                errors.add(errorMessage);
                LOG.warn(errorMessage);
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
        return errors;
    }
}