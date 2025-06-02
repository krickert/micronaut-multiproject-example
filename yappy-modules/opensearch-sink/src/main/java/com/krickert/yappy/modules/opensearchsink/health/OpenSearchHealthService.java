package com.krickert.yappy.modules.opensearchsink.health;

import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cluster.HealthRequest;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health service that manages gRPC health status for OpenSearch connectivity.
 * This service periodically checks OpenSearch cluster health and updates the gRPC health status accordingly.
 */
@Singleton
@Requires(beans = {OpenSearchClient.class, HealthStatusManager.class})
public class OpenSearchHealthService {
    
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchHealthService.class);
    private static final String SERVICE_NAME = "opensearch-sink";
    
    private final HealthStatusManager healthStatusManager;
    private final OpenSearchClient openSearchClient;
    
    @Inject
    public OpenSearchHealthService(@Nullable HealthStatusManager healthStatusManager,
                                   OpenSearchClient openSearchClient) {
        this.healthStatusManager = healthStatusManager;
        this.openSearchClient = openSearchClient;
        
        // Set initial status
        updateHealthStatus();
    }
    
    /**
     * Periodically check OpenSearch health and update gRPC health status.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = "30s", initialDelay = "10s")
    public void checkHealth() {
        updateHealthStatus();
    }
    
    /**
     * Update the gRPC health status based on OpenSearch cluster health.
     */
    private void updateHealthStatus() {
        try {
            HealthRequest healthRequest = HealthRequest.of(builder -> builder);
            HealthResponse healthResponse = openSearchClient.cluster().health(healthRequest);
            
            HealthCheckResponse.ServingStatus status;
            String logMessage;
            
            switch (healthResponse.status()) {
                case Green:
                case Yellow:
                    status = HealthCheckResponse.ServingStatus.SERVING;
                    logMessage = String.format("OpenSearch cluster healthy (%s) - %d nodes, %d active shards", 
                            healthResponse.status(), 
                            healthResponse.numberOfNodes(),
                            healthResponse.activeShards());
                    break;
                case Red:
                default:
                    status = HealthCheckResponse.ServingStatus.NOT_SERVING;
                    logMessage = String.format("OpenSearch cluster unhealthy (%s) - %d unassigned shards", 
                            healthResponse.status(),
                            healthResponse.unassignedShards());
                    break;
            }
            
            setStatus(SERVICE_NAME, status);
            LOG.debug(logMessage);
            
        } catch (Exception e) {
            LOG.warn("Failed to check OpenSearch health, marking as unhealthy", e);
            setStatus(SERVICE_NAME, HealthCheckResponse.ServingStatus.NOT_SERVING);
        }
    }
    
    /**
     * Set the health status for the service.
     * 
     * @param serviceName the service name
     * @param status the serving status
     */
    public void setStatus(@NonNull String serviceName, @NonNull HealthCheckResponse.ServingStatus status) {
        if (healthStatusManager != null) {
            healthStatusManager.setStatus(serviceName, status);
        }
    }
    
    /**
     * Force a health check when the application starts.
     */
    @EventListener
    public void onApplicationStarted(StartupEvent event) {
        LOG.info("Application started, performing initial OpenSearch health check");
        updateHealthStatus();
    }
}