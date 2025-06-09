package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Helper class for creating isolated test clusters with unique identifiers.
 * Each test gets its own cluster namespace to prevent conflicts.
 */
public class TestClusterHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(TestClusterHelper.class);
    private static final ConcurrentMap<String, String> TEST_CLUSTERS = new ConcurrentHashMap<>();
    
    /**
     * Creates a unique cluster name for a test.
     * @param baseName the base name for the cluster (e.g., "module-test")
     * @return a unique cluster name with UUID suffix
     */
    public static String createTestCluster(String baseName) {
        String clusterId = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = baseName + "-" + clusterId;
        TEST_CLUSTERS.put(clusterName, clusterId);
        logger.info("Created test cluster: {}", clusterName);
        return clusterName;
    }
    
    /**
     * Registers a service in a specific test cluster.
     * @param consulClient the Consul client
     * @param clusterName the cluster name
     * @param serviceName the service name
     * @param serviceId the unique service instance ID
     * @param host the service host
     * @param port the service port
     */
    public static void registerServiceInCluster(
            ConsulClient consulClient,
            String clusterName,
            String serviceName,
            String serviceId,
            String host,
            int port) {
        registerServiceInCluster(consulClient, clusterName, serviceName, serviceId, host, port, null);
    }
    
    /**
     * Registers a service in a specific test cluster with metadata.
     * @param consulClient the Consul client
     * @param clusterName the cluster name
     * @param serviceName the service name
     * @param serviceId the unique service instance ID
     * @param host the service host
     * @param port the service port
     * @param metadata additional metadata for the service
     */
    public static void registerServiceInCluster(
            ConsulClient consulClient,
            String clusterName,
            String serviceName,
            String serviceId,
            String host,
            int port,
            java.util.Map<String, String> metadata) {
        
        NewService service = new NewService();
        service.setId(clusterName + "-" + serviceId);
        service.setName(clusterName + "-" + serviceName);
        service.setAddress(host);
        service.setPort(port);
        service.setTags(List.of(
            "cluster:" + clusterName,
            "test-service",
            "grpc"
        ));
        
        // Add cluster-specific metadata
        service.setMeta(new ConcurrentHashMap<>());
        service.getMeta().put("cluster", clusterName);
        service.getMeta().put("service-type", serviceName);
        
        // Add any additional metadata provided
        if (metadata != null) {
            service.getMeta().putAll(metadata);
        }
        
        // Add health check
        NewService.Check check = new NewService.Check();
        check.setGrpc(host + ":" + port);
        check.setInterval("10s");
        service.setCheck(check);
        
        consulClient.agentServiceRegister(service);
        logger.info("Registered service {} in cluster {}", serviceId, clusterName);
    }
    
    /**
     * Cleans up all services in a test cluster.
     * @param consulClient the Consul client
     * @param clusterName the cluster name to clean up
     */
    public static void cleanupTestCluster(ConsulClient consulClient, String clusterName) {
        try {
            // Get all services
            consulClient.getAgentServices().getValue().forEach((id, service) -> {
                // Check if service belongs to this cluster
                if (id.startsWith(clusterName + "-") || 
                    (service.getMeta() != null && clusterName.equals(service.getMeta().get("cluster")))) {
                    consulClient.agentServiceDeregister(id);
                    logger.info("Deregistered service {} from cluster {}", id, clusterName);
                }
            });
            
            TEST_CLUSTERS.remove(clusterName);
            logger.info("Cleaned up test cluster: {}", clusterName);
        } catch (Exception e) {
            logger.error("Error cleaning up cluster {}: {}", clusterName, e.getMessage(), e);
        }
    }
    
    /**
     * Cleans up all test clusters (useful for test suite teardown).
     * @param consulClient the Consul client
     */
    public static void cleanupAllTestClusters(ConsulClient consulClient) {
        TEST_CLUSTERS.keySet().forEach(clusterName -> cleanupTestCluster(consulClient, clusterName));
    }
}