package com.krickert.search.config.consul.validation;

import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.ServiceConfigurationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Validator for pipeline configurations.
 * This class provides methods to validate pipeline configurations, such as detecting loops.
 */
public class PipelineValidator {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineValidator.class);

    /**
     * Checks if adding or updating a service would create a loop in the pipeline.
     *
     * @param pipeline the pipeline configuration
     * @param serviceConfig the service configuration to add or update
     * @return true if adding or updating the service would create a loop, false otherwise
     */
    public static boolean hasLoop(PipelineConfigDto pipeline, ServiceConfigurationDto serviceConfig) {
        // Build a graph of service dependencies
        Map<String, Set<String>> graph = buildDependencyGraph(pipeline, serviceConfig);
        
        // Check for loops using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        // Start DFS from the service being added or updated
        String serviceName = serviceConfig.getName();
        if (serviceName == null) {
            LOG.warn("Service name is null, cannot check for loops");
            return false;
        }
        
        return hasCycle(graph, serviceName, visited, recursionStack);
    }
    
    /**
     * Builds a graph of service dependencies.
     * The graph is represented as a map where the key is a service name and the value is a set of service names
     * that the key service depends on (i.e., services that publish to topics that the key service listens to).
     *
     * @param pipeline the pipeline configuration
     * @param newServiceConfig the service configuration to add or update
     * @return a map representing the dependency graph
     */
    private static Map<String, Set<String>> buildDependencyGraph(PipelineConfigDto pipeline, ServiceConfigurationDto newServiceConfig) {
        Map<String, Set<String>> graph = new HashMap<>();
        
        // Create a map of topics to services that publish to them
        Map<String, Set<String>> topicPublishers = new HashMap<>();
        
        // Add existing services to the graph
        for (ServiceConfigurationDto service : pipeline.getServices().values()) {
            String serviceName = service.getName();
            if (serviceName == null) {
                continue;
            }
            
            // Initialize the graph entry for this service
            graph.putIfAbsent(serviceName, new HashSet<>());
            
            // Add topics this service publishes to
            if (service.getKafkaPublishTopics() != null) {
                for (String topic : service.getKafkaPublishTopics()) {
                    topicPublishers.computeIfAbsent(topic, k -> new HashSet<>()).add(serviceName);
                }
            }
        }
        
        // Add the new service to the graph
        String newServiceName = newServiceConfig.getName();
        if (newServiceName != null) {
            graph.putIfAbsent(newServiceName, new HashSet<>());
            
            // Add topics this service publishes to
            if (newServiceConfig.getKafkaPublishTopics() != null) {
                for (String topic : newServiceConfig.getKafkaPublishTopics()) {
                    topicPublishers.computeIfAbsent(topic, k -> new HashSet<>()).add(newServiceName);
                }
            }
        }
        
        // Add dependencies based on Kafka topics
        for (ServiceConfigurationDto service : pipeline.getServices().values()) {
            String serviceName = service.getName();
            if (serviceName == null) {
                continue;
            }
            
            // Add dependencies based on topics this service listens to
            if (service.getKafkaListenTopics() != null) {
                for (String topic : service.getKafkaListenTopics()) {
                    Set<String> publishers = topicPublishers.get(topic);
                    if (publishers != null) {
                        graph.get(serviceName).addAll(publishers);
                    }
                }
            }
        }
        
        // Add dependencies for the new service
        if (newServiceName != null && newServiceConfig.getKafkaListenTopics() != null) {
            for (String topic : newServiceConfig.getKafkaListenTopics()) {
                Set<String> publishers = topicPublishers.get(topic);
                if (publishers != null) {
                    graph.get(newServiceName).addAll(publishers);
                }
            }
        }
        
        // Add dependencies based on gRPC forwarding
        for (ServiceConfigurationDto service : pipeline.getServices().values()) {
            String serviceName = service.getName();
            if (serviceName == null) {
                continue;
            }
            
            // Add dependencies based on services this service forwards to via gRPC
            if (service.getGrpcForwardTo() != null) {
                for (String forwardTo : service.getGrpcForwardTo()) {
                    graph.get(serviceName).add(forwardTo);
                }
            }
        }
        
        // Add dependencies for the new service
        if (newServiceName != null && newServiceConfig.getGrpcForwardTo() != null) {
            for (String forwardTo : newServiceConfig.getGrpcForwardTo()) {
                graph.get(newServiceName).add(forwardTo);
            }
        }
        
        return graph;
    }
    
    /**
     * Checks if there is a cycle in the graph using DFS.
     *
     * @param graph the dependency graph
     * @param node the current node
     * @param visited the set of visited nodes
     * @param recursionStack the set of nodes in the current recursion stack
     * @return true if there is a cycle, false otherwise
     */
    private static boolean hasCycle(Map<String, Set<String>> graph, String node, Set<String> visited, Set<String> recursionStack) {
        // Mark the current node as visited and add to recursion stack
        visited.add(node);
        recursionStack.add(node);
        
        // Visit all the neighbors
        Set<String> neighbors = graph.get(node);
        if (neighbors != null) {
            for (String neighbor : neighbors) {
                // If the neighbor is not visited, recursively check for cycles
                if (!visited.contains(neighbor)) {
                    if (hasCycle(graph, neighbor, visited, recursionStack)) {
                        return true;
                    }
                } 
                // If the neighbor is in the recursion stack, there is a cycle
                else if (recursionStack.contains(neighbor)) {
                    LOG.warn("Detected loop in pipeline: {} -> {}", node, neighbor);
                    return true;
                }
            }
        }
        
        // Remove the current node from the recursion stack
        recursionStack.remove(node);
        return false;
    }
    
    /**
     * Gets all services that depend on a given service.
     * A service depends on another service if it listens to a topic that the other service publishes to,
     * or if it is forwarded to by the other service via gRPC.
     *
     * @param pipeline the pipeline configuration
     * @param serviceName the name of the service
     * @return a set of service names that depend on the given service
     */
    public static Set<String> getDependentServices(PipelineConfigDto pipeline, String serviceName) {
        Set<String> dependentServices = new HashSet<>();
        ServiceConfigurationDto service = pipeline.getServices().get(serviceName);
        
        if (service == null) {
            return dependentServices;
        }
        
        // Find services that listen to topics this service publishes to
        if (service.getKafkaPublishTopics() != null) {
            for (String topic : service.getKafkaPublishTopics()) {
                for (ServiceConfigurationDto otherService : pipeline.getServices().values()) {
                    if (otherService.getKafkaListenTopics() != null && 
                        otherService.getKafkaListenTopics().contains(topic) &&
                        !otherService.getName().equals(serviceName)) {
                        dependentServices.add(otherService.getName());
                    }
                }
            }
        }
        
        // Find services that this service forwards to via gRPC
        if (service.getGrpcForwardTo() != null) {
            dependentServices.addAll(service.getGrpcForwardTo());
        }
        
        return dependentServices;
    }
}