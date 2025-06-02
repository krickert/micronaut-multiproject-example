package com.krickert.yappy.modules.wikicrawler.health;

import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Health service that manages gRPC health status for WikiCrawler functionality.
 * This service periodically checks file system access and Wikipedia API connectivity
 * and updates the gRPC health status accordingly.
 */
@Singleton
@Requires(beans = HealthStatusManager.class)
public class WikiCrawlerHealthService {
    
    private static final Logger LOG = LoggerFactory.getLogger(WikiCrawlerHealthService.class);
    private static final String SERVICE_NAME = "wikicrawler-connector";
    
    private final HealthStatusManager healthStatusManager;
    
    @Value("${wikicrawler.base-storage-path:downloaded_wikidumps}")
    private String baseStoragePath;
    
    @Value("${file.download.base-url:https://dumps.wikimedia.org}")
    private String baseDownloadUrl;
    
    @Inject
    public WikiCrawlerHealthService(@Nullable HealthStatusManager healthStatusManager) {
        this.healthStatusManager = healthStatusManager;
        
        // Set initial status
        updateHealthStatus();
    }
    
    /**
     * Periodically check WikiCrawler health and update gRPC health status.
     * Runs every 60 seconds (less frequent since file system and API don't change often).
     */
    @Scheduled(fixedDelay = "60s", initialDelay = "10s")
    public void checkHealth() {
        updateHealthStatus();
    }
    
    /**
     * Update the gRPC health status based on file system access and Wikipedia API connectivity.
     */
    private void updateHealthStatus() {
        try {
            boolean isHealthy = true;
            StringBuilder issues = new StringBuilder();
            
            // Check file system access
            try {
                Path storagePath = Paths.get(baseStoragePath);
                
                // Create directory if it doesn't exist
                if (!Files.exists(storagePath)) {
                    Files.createDirectories(storagePath);
                }
                
                // Test write permissions
                Path testFile = storagePath.resolve(".health-check-test");
                Files.write(testFile, "health check".getBytes());
                Files.delete(testFile);
                
            } catch (Exception e) {
                isHealthy = false;
                issues.append("File system access failed: ").append(e.getMessage()).append("; ");
            }
            
            // Check Wikipedia API connectivity (quick HEAD request)
            try {
                URL url = new URL(baseDownloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    isHealthy = false;
                    issues.append("Wikipedia API not reachable (HTTP ").append(responseCode).append("); ");
                }
                
            } catch (IOException e) {
                isHealthy = false;
                issues.append("Wikipedia API connectivity failed: ").append(e.getMessage()).append("; ");
            }
            
            HealthCheckResponse.ServingStatus status = isHealthy ? 
                    HealthCheckResponse.ServingStatus.SERVING : 
                    HealthCheckResponse.ServingStatus.NOT_SERVING;
            
            String logMessage = isHealthy ? 
                    "WikiCrawler service healthy - file system accessible and Wikipedia API reachable" :
                    "WikiCrawler service unhealthy: " + issues.toString();
            
            setStatus(SERVICE_NAME, status);
            LOG.debug(logMessage);
            
        } catch (Exception e) {
            LOG.warn("Failed to check WikiCrawler health, marking as unhealthy", e);
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
        LOG.info("Application started, performing initial WikiCrawler health check");
        updateHealthStatus();
    }
}