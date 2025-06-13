package com.krickert.search.engine.core;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Helper class for creating isolated test clusters with unique identifiers.
 * Each test gets its own cluster namespace to prevent conflicts.
 */
@Singleton
public class TestClusterHelper {
    
    private static final Logger logger = LoggerFactory.getLogger(TestClusterHelper.class);
    private final ConcurrentMap<String, String> testClusters = new ConcurrentHashMap<>();
    private final BusinessOperationsService businessOpsService;
    
    @Inject
    public TestClusterHelper(BusinessOperationsService businessOpsService) {
        this.businessOpsService = businessOpsService;
    }
    
    /**
     * Creates a unique cluster name for a test.
     * @param baseName the base name for the cluster (e.g., "module-test")
     * @return a unique cluster name with UUID suffix
     */
    public String createTestCluster(String baseName) {
        String clusterId = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = baseName + "-" + clusterId;
        testClusters.put(clusterName, clusterId);
        logger.info("Created test cluster: {}", clusterName);
        return clusterName;
    }
    
    /**
     * Registers a service in a specific test cluster.
     * @param clusterName the cluster name
     * @param serviceName the service name
     * @param serviceId the unique service instance ID
     * @param host the service host
     * @param port the service port
     * @return Mono<Void> indicating completion
     */
    public Mono<Void> registerServiceInCluster(
            String clusterName,
            String serviceName,
            String serviceId,
            String host,
            int port) {
        return registerServiceInCluster(clusterName, serviceName, serviceId, host, port, null);
    }
    
    /**
     * Registers a service in a specific test cluster with metadata.
     * @param clusterName the cluster name
     * @param serviceName the service name
     * @param serviceId the unique service instance ID
     * @param host the service host
     * @param port the service port
     * @param metadata additional metadata for the service
     * @return Mono<Void> indicating completion
     */
    public Mono<Void> registerServiceInCluster(
            String clusterName,
            String serviceName,
            String serviceId,
            String host,
            int port,
            Map<String, String> metadata) {
        
        // Create registration using Kiwiproject model
        ImmutableRegistration.Builder registrationBuilder = ImmutableRegistration.builder()
            .id(clusterName + "-" + serviceId)
            .name(clusterName + "-" + serviceName)
            .address(host)
            .port(port)
            .tags(List.of(
                "cluster:" + clusterName,
                "test-service",
                "grpc"
            ));
        
        // Add cluster-specific metadata
        Map<String, String> meta = new ConcurrentHashMap<>();
        meta.put("cluster", clusterName);
        meta.put("service-type", serviceName);
        
        // Add any additional metadata provided
        if (metadata != null) {
            meta.putAll(metadata);
        }
        registrationBuilder.meta(meta);
        
        // Don't add health check for testing - we'll modify the test to use getServiceInstances
        // instead of getHealthyServiceInstances
        // registrationBuilder.check(healthCheck);
        
        Registration registration = registrationBuilder.build();
        
        return businessOpsService.registerService(registration)
            .doOnSuccess(v -> logger.info("Registered service {} in cluster {}", serviceId, clusterName))
            .then();
    }
    
    /**
     * Cleans up all services in a test cluster.
     * @param clusterName the cluster name to clean up
     * @return Mono<Void> indicating completion
     */
    public Mono<Void> cleanupTestCluster(String clusterName) {
        return businessOpsService.listServices()
            .flatMapMany(services -> {
                // Get all possible service types for this cluster
                return Flux.merge(
                    businessOpsService.getServiceInstances(clusterName + "-chunker"),
                    businessOpsService.getServiceInstances(clusterName + "-tika-parser"),
                    businessOpsService.getServiceInstances(clusterName + "-embedder"),
                    businessOpsService.getServiceInstances(clusterName + "-opensearch-sink"),
                    businessOpsService.getServiceInstances(clusterName + "-s3-connector")
                );
            })
            .flatMap(catalogServices -> Flux.fromIterable(catalogServices))
            .flatMap(service -> {
                String serviceId = service.getServiceId();
                if (serviceId.startsWith(clusterName + "-")) {
                    return businessOpsService.deregisterService(serviceId)
                        .doOnSuccess(v -> logger.info("Deregistered service {} from cluster {}", serviceId, clusterName));
                }
                return Mono.empty();
            })
            .then()
            .doOnSuccess(v -> {
                testClusters.remove(clusterName);
                logger.info("Cleaned up test cluster: {}", clusterName);
            })
            .onErrorContinue((error, obj) -> 
                logger.error("Error cleaning up cluster {}: {}", clusterName, error.getMessage(), error));
    }
    
    /**
     * Cleans up all test clusters (useful for test suite teardown).
     * @return Mono<Void> indicating completion
     */
    public Mono<Void> cleanupAllTestClusters() {
        return Mono.whenDelayError(
            testClusters.keySet().stream()
                .map(this::cleanupTestCluster)
                .toArray(Mono[]::new)
        );
    }
}