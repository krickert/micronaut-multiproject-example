package com.krickert.yappy.modules.s3connector.health;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

/**
 * Health service that manages gRPC health status for S3 connectivity.
 * This service periodically checks S3 connectivity and updates the gRPC health status accordingly.
 */
@Singleton
@Requires(beans = {S3Client.class, HealthStatusManager.class})
public class S3HealthService {
    
    private static final Logger LOG = LoggerFactory.getLogger(S3HealthService.class);
    private static final String SERVICE_NAME = "s3-connector";
    
    private final HealthStatusManager healthStatusManager;
    private final S3Client s3Client;
    
    @Inject
    public S3HealthService(@Nullable HealthStatusManager healthStatusManager,
                           S3Client s3Client) {
        this.healthStatusManager = healthStatusManager;
        this.s3Client = s3Client;
        
        // Set initial status
        updateHealthStatus();
    }
    
    /**
     * Periodically check S3 health and update gRPC health status.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = "30s", initialDelay = "10s")
    public void checkHealth() {
        updateHealthStatus();
    }
    
    /**
     * Update the gRPC health status based on S3 connectivity.
     */
    private void updateHealthStatus() {
        try {
            // Test S3 connectivity by listing buckets
            ListBucketsResponse listBucketsResponse = s3Client.listBuckets();
            
            HealthCheckResponse.ServingStatus status = HealthCheckResponse.ServingStatus.SERVING;
            String logMessage = String.format("S3 service healthy - %d buckets accessible", 
                    listBucketsResponse.buckets().size());
            
            setStatus(SERVICE_NAME, status);
            LOG.debug(logMessage);
            
        } catch (Exception e) {
            LOG.warn("Failed to check S3 health, marking as unhealthy", e);
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
        LOG.info("Application started, performing initial S3 health check");
        updateHealthStatus();
    }
}