package com.krickert.yappy.modules.webcrawlerconnector.health;

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

/**
 * Health service that manages gRPC health status for Web Crawler functionality.
 * This service periodically checks Chrome/Chromium availability and system resources
 * and updates the gRPC health status accordingly.
 */
@Singleton
@Requires(beans = HealthStatusManager.class)
public class WebCrawlerHealthService {
    
    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlerHealthService.class);
    private static final String SERVICE_NAME = "web-crawler-connector";
    
    private final HealthStatusManager healthStatusManager;
    
    @Value("${web.crawler.config.headless:true}")
    private boolean headless;
    
    @Inject
    public WebCrawlerHealthService(@Nullable HealthStatusManager healthStatusManager) {
        this.healthStatusManager = healthStatusManager;
        
        // Set initial status
        updateHealthStatus();
    }
    
    /**
     * Periodically check Web Crawler health and update gRPC health status.
     * Runs every 60 seconds (less frequent since Chrome availability doesn't change often).
     */
    @Scheduled(fixedDelay = "60s", initialDelay = "10s")
    public void checkHealth() {
        updateHealthStatus();
    }
    
    /**
     * Update the gRPC health status based on Chrome availability and system resources.
     */
    private void updateHealthStatus() {
        try {
            boolean isHealthy = true;
            StringBuilder issues = new StringBuilder();
            
            // Check if Chrome/Chromium is available
            try {
                String chromeVersion = checkChromeAvailability();
                LOG.debug("Chrome available: {}", chromeVersion);
                
            } catch (Exception e) {
                isHealthy = false;
                issues.append("Chrome/Chromium not available: ").append(e.getMessage()).append("; ");
            }
            
            // Check system memory
            try {
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;
                
                double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
                if (memoryUsagePercent > 95) {
                    isHealthy = false;
                    issues.append("Critical memory usage: ").append(String.format("%.1f%%", memoryUsagePercent)).append("; ");
                }
                
            } catch (Exception e) {
                LOG.warn("System resource check failed", e);
                // Don't mark as unhealthy for system checks, just log
            }
            
            HealthCheckResponse.ServingStatus status = isHealthy ? 
                    HealthCheckResponse.ServingStatus.SERVING : 
                    HealthCheckResponse.ServingStatus.NOT_SERVING;
            
            String logMessage = isHealthy ? 
                    "Web Crawler service healthy - Chrome available and system resources sufficient" :
                    "Web Crawler service unhealthy: " + issues.toString();
            
            setStatus(SERVICE_NAME, status);
            LOG.debug(logMessage);
            
        } catch (Exception e) {
            LOG.warn("Failed to check Web Crawler health, marking as unhealthy", e);
            setStatus(SERVICE_NAME, HealthCheckResponse.ServingStatus.NOT_SERVING);
        }
    }
    
    /**
     * Check if Chrome/Chromium is available on the system.
     * 
     * @return Chrome version string if available
     * @throws Exception if Chrome is not available
     */
    private String checkChromeAvailability() throws Exception {
        String[] chromeCommands = {
            "google-chrome --version",
            "google-chrome-stable --version", 
            "chromium --version",
            "chromium-browser --version",
            "/usr/bin/google-chrome --version",
            "/usr/bin/chromium --version"
        };
        
        for (String command : chromeCommands) {
            try {
                Process process = Runtime.getRuntime().exec(command);
                process.waitFor();
                
                if (process.exitValue() == 0) {
                    // Successfully found Chrome/Chromium
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String version = reader.readLine();
                        if (version != null && !version.trim().isEmpty()) {
                            return version.trim();
                        }
                    }
                }
            } catch (Exception e) {
                // Continue trying other commands
                LOG.debug("Command '{}' failed: {}", command, e.getMessage());
            }
        }
        
        throw new Exception("Chrome/Chromium not found in system PATH or common locations");
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
        LOG.info("Application started, performing initial Web Crawler health check");
        updateHealthStatus();
    }
}