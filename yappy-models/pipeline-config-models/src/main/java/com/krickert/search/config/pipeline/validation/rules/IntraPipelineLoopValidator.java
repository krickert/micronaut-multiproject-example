package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.*;

/**
 * Validates that there are no circular dependencies within a pipeline across ALL transport types.
 * This handles complex flows like: gRPC -> Kafka -> gRPC -> Kafka -> gRPC
 * 
 * The validator builds a complete dependency graph considering:
 * - gRPC direct connections (step -> step)
 * - Kafka pub/sub connections (step publishes topic -> step consumes topic)
 * - Mixed transport paths
 */
public class IntraPipelineLoopValidator implements StructuralValidationRule {
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            return errors; // No steps to validate
        }
        
        // Build complete dependency graph including all transport types
        Map<String, Set<Connection>> dependencyGraph = buildCompleteDependencyGraph(config.pipelineSteps(), pipelineId);
        
        // Detect cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String stepId : config.pipelineSteps().keySet()) {
            if (!visited.contains(stepId)) {
                List<Connection> cyclePath = new ArrayList<>();
                if (hasCycleDFS(stepId, dependencyGraph, visited, recursionStack, cyclePath)) {
                    // Format cycle path for error message
                    Collections.reverse(cyclePath);
                    String cycleDescription = formatCyclePath(cyclePath);
                    errors.add("Circular dependency detected in pipeline flow: " + cycleDescription);
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Builds a complete dependency graph considering all transport types.
     */
    private Map<String, Set<Connection>> buildCompleteDependencyGraph(Map<String, PipelineStepConfig> steps, String pipelineId) {
        Map<String, Set<Connection>> graph = new HashMap<>();
        
        // First, map Kafka topics to their consumers
        Map<String, Set<String>> topicConsumers = buildTopicConsumerMap(steps, pipelineId);
        
        // Now build the complete graph
        for (var entry : steps.entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step != null && step.outputs() != null) {
                Set<Connection> connections = new HashSet<>();
                
                for (var outputEntry : step.outputs().entrySet()) {
                    var output = outputEntry.getValue();
                    if (output == null) continue;
                    
                    if (output.transportType() == TransportType.GRPC && 
                        output.grpcTransport() != null && 
                        output.grpcTransport().serviceName() != null) {
                        
                        String targetService = output.grpcTransport().serviceName();
                        // Only add if it's an internal reference (exists in steps)
                        if (steps.containsKey(targetService)) {
                            connections.add(new Connection(targetService, TransportType.GRPC, null));
                        }
                        
                    } else if (output.transportType() == TransportType.KAFKA && 
                               output.kafkaTransport() != null && 
                               output.kafkaTransport().topic() != null) {
                        
                        String topic = resolveTopicPattern(output.kafkaTransport().topic(), stepId, pipelineId);
                        
                        // Find all steps that consume this topic
                        Set<String> consumers = topicConsumers.get(topic);
                        if (consumers != null) {
                            for (String consumer : consumers) {
                                connections.add(new Connection(consumer, TransportType.KAFKA, topic));
                            }
                        }
                    }
                }
                
                if (!connections.isEmpty()) {
                    graph.put(stepId, connections);
                }
            }
        }
        
        return graph;
    }
    
    /**
     * Builds a map of Kafka topics to the steps that consume them.
     */
    private Map<String, Set<String>> buildTopicConsumerMap(Map<String, PipelineStepConfig> steps, String pipelineId) {
        Map<String, Set<String>> topicConsumers = new HashMap<>();
        
        for (var entry : steps.entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step != null && step.kafkaInputs() != null) {
                for (KafkaInputDefinition input : step.kafkaInputs()) {
                    if (input != null && input.listenTopics() != null) {
                        for (String topicPattern : input.listenTopics()) {
                            if (topicPattern != null && !topicPattern.isBlank()) {
                                // For structural validation, we do simple pattern resolution
                                String topic = resolveTopicPattern(topicPattern, stepId, pipelineId);
                                topicConsumers.computeIfAbsent(topic, k -> new HashSet<>()).add(stepId);
                            }
                        }
                    }
                }
            }
        }
        
        return topicConsumers;
    }
    
    /**
     * Simple topic pattern resolution for structural validation.
     * In real validation, this would be more sophisticated.
     */
    private String resolveTopicPattern(String pattern, String stepName, String pipelineName) {
        if (pattern == null) return null;
        
        return pattern
            .replace("${stepName}", stepName)
            .replace("${pipelineName}", pipelineName)
            .replace("${clusterName}", "default"); // Assume default for structural validation
    }
    
    /**
     * DFS helper to detect cycles with connection tracking.
     */
    private boolean hasCycleDFS(String node, Map<String, Set<Connection>> graph, 
                               Set<String> visited, Set<String> recursionStack, 
                               List<Connection> cyclePath) {
        visited.add(node);
        recursionStack.add(node);
        
        Set<Connection> connections = graph.get(node);
        if (connections != null) {
            for (Connection conn : connections) {
                if (!visited.contains(conn.targetStep)) {
                    if (hasCycleDFS(conn.targetStep, graph, visited, recursionStack, cyclePath)) {
                        cyclePath.add(new Connection(node, conn.transport, conn.topic));
                        return true;
                    }
                } else if (recursionStack.contains(conn.targetStep)) {
                    // Found a cycle
                    cyclePath.add(new Connection(conn.targetStep, conn.transport, conn.topic));
                    cyclePath.add(new Connection(node, conn.transport, conn.topic));
                    return true;
                }
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
    
    /**
     * Formats a cycle path showing transport types.
     */
    private String formatCyclePath(List<Connection> cyclePath) {
        if (cyclePath.isEmpty()) {
            return "[empty cycle]";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        for (int i = 0; i < cyclePath.size(); i++) {
            Connection conn = cyclePath.get(i);
            
            if (i > 0) {
                Connection prevConn = cyclePath.get(i - 1);
                sb.append(" -");
                sb.append(prevConn.transport.name());
                if (prevConn.transport == TransportType.KAFKA && prevConn.topic != null) {
                    sb.append("(").append(prevConn.topic).append(")");
                }
                sb.append("-> ");
            }
            
            sb.append(conn.targetStep);
        }
        
        // Complete the cycle
        if (!cyclePath.isEmpty()) {
            Connection lastConn = cyclePath.get(cyclePath.size() - 1);
            Connection firstConn = cyclePath.get(0);
            sb.append(" -");
            sb.append(lastConn.transport.name());
            if (lastConn.transport == TransportType.KAFKA && lastConn.topic != null) {
                sb.append("(").append(lastConn.topic).append(")");
            }
            sb.append("-> ");
            sb.append(firstConn.targetStep);
        }
        
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Represents a connection between steps with transport information.
     */
    private static class Connection {
        final String targetStep;
        final TransportType transport;
        final String topic; // For Kafka connections
        
        Connection(String targetStep, TransportType transport, String topic) {
            this.targetStep = targetStep;
            this.transport = transport;
            this.topic = topic;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Connection that = (Connection) o;
            return Objects.equals(targetStep, that.targetStep) &&
                   transport == that.transport &&
                   Objects.equals(topic, that.topic);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(targetStep, transport, topic);
        }
    }
}