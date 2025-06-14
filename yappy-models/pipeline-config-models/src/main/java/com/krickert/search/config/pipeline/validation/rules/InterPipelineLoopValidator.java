package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.validation.ClusterStructuralValidationRule;

import java.util.*;

/**
 * Validates that there are no circular dependencies between pipelines via Kafka topics.
 * This handles cases where:
 * - Pipeline A publishes to topic X
 * - Pipeline B consumes from topic X and publishes to topic Y  
 * - Pipeline A (or C) consumes from topic Y, creating a loop
 * 
 * This validator requires the full cluster configuration to analyze cross-pipeline dependencies.
 */
public class InterPipelineLoopValidator implements ClusterStructuralValidationRule {
    
    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig) {
        List<String> errors = new ArrayList<>();
        
        if (clusterConfig.pipelineGraphConfig() == null || 
            clusterConfig.pipelineGraphConfig().pipelines() == null ||
            clusterConfig.pipelineGraphConfig().pipelines().isEmpty()) {
            return errors;
        }
        
        // Build a graph of pipeline dependencies through Kafka topics
        Map<String, Set<PipelineConnection>> pipelineGraph = buildPipelineDependencyGraph(clusterConfig);
        
        // Detect cycles in the pipeline graph
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String pipelineId : clusterConfig.pipelineGraphConfig().pipelines().keySet()) {
            if (!visited.contains(pipelineId)) {
                List<PipelineConnection> cyclePath = new ArrayList<>();
                if (hasCycleDFS(pipelineId, pipelineGraph, visited, recursionStack, cyclePath)) {
                    Collections.reverse(cyclePath);
                    String cycleDescription = formatCyclePath(cyclePath);
                    errors.add("Inter-pipeline circular dependency detected via Kafka topics: " + cycleDescription);
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Builds a dependency graph between pipelines based on Kafka topic pub/sub.
     */
    private Map<String, Set<PipelineConnection>> buildPipelineDependencyGraph(PipelineClusterConfig clusterConfig) {
        Map<String, Set<PipelineConnection>> graph = new HashMap<>();
        
        // First, collect all topics published by each pipeline
        Map<String, Set<TopicPublisher>> topicPublishers = new HashMap<>();
        
        for (var pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineId = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();
            
            if (pipeline.pipelineSteps() != null) {
                for (var stepEntry : pipeline.pipelineSteps().entrySet()) {
                    String stepId = stepEntry.getKey();
                    PipelineStepConfig step = stepEntry.getValue();
                    
                    if (step != null && step.outputs() != null) {
                        for (var output : step.outputs().values()) {
                            if (output != null && 
                                output.transportType() == TransportType.KAFKA &&
                                output.kafkaTransport() != null &&
                                output.kafkaTransport().topic() != null) {
                                
                                String topic = resolveTopicPattern(
                                    output.kafkaTransport().topic(),
                                    stepId,
                                    pipelineId,
                                    clusterConfig.clusterName()
                                );
                                
                                topicPublishers.computeIfAbsent(topic, k -> new HashSet<>())
                                    .add(new TopicPublisher(pipelineId, stepId));
                            }
                        }
                    }
                }
            }
        }
        
        // Now find pipeline connections through Kafka consumption
        for (var pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String consumingPipeline = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();
            
            Set<PipelineConnection> connections = new HashSet<>();
            
            if (pipeline.pipelineSteps() != null) {
                for (var stepEntry : pipeline.pipelineSteps().entrySet()) {
                    String stepId = stepEntry.getKey();
                    PipelineStepConfig step = stepEntry.getValue();
                    
                    if (step != null && step.kafkaInputs() != null) {
                        for (KafkaInputDefinition input : step.kafkaInputs()) {
                            if (input != null && input.listenTopics() != null) {
                                for (String topicPattern : input.listenTopics()) {
                                    String topic = resolveTopicPattern(
                                        topicPattern,
                                        stepId,
                                        consumingPipeline,
                                        clusterConfig.clusterName()
                                    );
                                    
                                    // Find all pipelines that publish to this topic
                                    Set<TopicPublisher> publishers = topicPublishers.get(topic);
                                    if (publishers != null) {
                                        for (TopicPublisher publisher : publishers) {
                                            // Don't add self-connections (intra-pipeline connections)
                                            if (!publisher.pipelineId.equals(consumingPipeline)) {
                                                connections.add(new PipelineConnection(
                                                    publisher.pipelineId,
                                                    topic,
                                                    publisher.stepId,
                                                    stepId
                                                ));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (!connections.isEmpty()) {
                graph.put(consumingPipeline, connections);
            }
        }
        
        return graph;
    }
    
    /**
     * Simple topic pattern resolution for structural validation.
     */
    private String resolveTopicPattern(String pattern, String stepName, String pipelineName, String clusterName) {
        if (pattern == null || pattern.isBlank()) {
            return pattern;
        }
        
        return pattern
            .replace("${stepName}", stepName)
            .replace("${pipelineName}", pipelineName)
            .replace("${clusterName}", clusterName);
    }
    
    /**
     * DFS to detect cycles in pipeline dependencies.
     */
    private boolean hasCycleDFS(String pipeline, Map<String, Set<PipelineConnection>> graph,
                               Set<String> visited, Set<String> recursionStack,
                               List<PipelineConnection> cyclePath) {
        visited.add(pipeline);
        recursionStack.add(pipeline);
        
        Set<PipelineConnection> connections = graph.get(pipeline);
        if (connections != null) {
            for (PipelineConnection conn : connections) {
                if (!visited.contains(conn.targetPipeline)) {
                    if (hasCycleDFS(conn.targetPipeline, graph, visited, recursionStack, cyclePath)) {
                        cyclePath.add(new PipelineConnection(pipeline, conn.topic, conn.publishingStep, conn.consumingStep));
                        return true;
                    }
                } else if (recursionStack.contains(conn.targetPipeline)) {
                    // Found a cycle
                    cyclePath.add(new PipelineConnection(conn.targetPipeline, conn.topic, conn.publishingStep, conn.consumingStep));
                    cyclePath.add(new PipelineConnection(pipeline, conn.topic, conn.publishingStep, conn.consumingStep));
                    return true;
                }
            }
        }
        
        recursionStack.remove(pipeline);
        return false;
    }
    
    /**
     * Formats a cycle path for error reporting.
     */
    private String formatCyclePath(List<PipelineConnection> cyclePath) {
        if (cyclePath.isEmpty()) {
            return "[empty cycle]";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        for (int i = 0; i < cyclePath.size(); i++) {
            PipelineConnection conn = cyclePath.get(i);
            
            if (i > 0) {
                PipelineConnection prevConn = cyclePath.get(i - 1);
                sb.append(" --Kafka(").append(prevConn.topic).append(")--> ");
            }
            
            sb.append(conn.targetPipeline);
        }
        
        // Complete the cycle
        if (!cyclePath.isEmpty()) {
            PipelineConnection lastConn = cyclePath.get(cyclePath.size() - 1);
            sb.append(" --Kafka(").append(lastConn.topic).append(")--> ");
            sb.append(cyclePath.get(0).targetPipeline);
        }
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Represents which pipeline/step publishes to a topic.
     */
    private static class TopicPublisher {
        final String pipelineId;
        final String stepId;
        
        TopicPublisher(String pipelineId, String stepId) {
            this.pipelineId = pipelineId;
            this.stepId = stepId;
        }
    }
    
    /**
     * Represents a connection between pipelines via Kafka.
     */
    private static class PipelineConnection {
        final String targetPipeline;
        final String topic;
        final String publishingStep;
        final String consumingStep;
        
        PipelineConnection(String targetPipeline, String topic, String publishingStep, String consumingStep) {
            this.targetPipeline = targetPipeline;
            this.topic = topic;
            this.publishingStep = publishingStep;
            this.consumingStep = consumingStep;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PipelineConnection that = (PipelineConnection) o;
            return Objects.equals(targetPipeline, that.targetPipeline) &&
                   Objects.equals(topic, that.topic);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(targetPipeline, topic);
        }
    }
}