package com.krickert.yappy.modules.wikicrawler.health;

import io.micronaut.context.annotation.Value;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Health indicator for WikiCrawler connectivity and file system access.
 * Checks if the storage directory is accessible and Wikipedia dumps API is reachable.
 */
@Singleton
public class WikiCrawlerHealthIndicator implements HealthIndicator {
    
    private static final Logger LOG = LoggerFactory.getLogger(WikiCrawlerHealthIndicator.class);
    
    @Value("${wikicrawler.base-storage-path:downloaded_wikidumps}")
    private String baseStoragePath;
    
    @Value("${file.download.base-url:https://dumps.wikimedia.org}")
    private String baseDownloadUrl;
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            boolean isHealthy = true;
            StringBuilder issues = new StringBuilder();
            
            // Check file system access
            try {
                Path storagePath = Paths.get(baseStoragePath);
                
                // Create directory if it doesn't exist
                if (!Files.exists(storagePath)) {
                    Files.createDirectories(storagePath);
                    LOG.debug("Created storage directory: {}", storagePath);
                }
                
                // Test write permissions
                Path testFile = storagePath.resolve(".health-check-test");
                Files.write(testFile, "health check".getBytes());
                Files.delete(testFile);
                
                details.put("storage_path", storagePath.toAbsolutePath().toString());
                details.put("storage_writable", true);
                details.put("storage_readable", Files.isReadable(storagePath));
                
            } catch (Exception e) {
                LOG.warn("File system health check failed", e);
                isHealthy = false;
                issues.append("File system access failed: ").append(e.getMessage()).append("; ");
                details.put("storage_error", e.getMessage());
                details.put("storage_writable", false);
            }
            
            // Check Wikipedia API connectivity
            try {
                URL url = new URL(baseDownloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                int responseCode = connection.getResponseCode();
                boolean apiReachable = responseCode >= 200 && responseCode < 400;
                
                details.put("wikipedia_api_url", baseDownloadUrl);
                details.put("wikipedia_api_reachable", apiReachable);
                details.put("wikipedia_api_response_code", responseCode);
                
                if (!apiReachable) {
                    isHealthy = false;
                    issues.append("Wikipedia API not reachable (HTTP ").append(responseCode).append("); ");
                }
                
            } catch (IOException e) {
                LOG.warn("Wikipedia API connectivity check failed", e);
                isHealthy = false;
                issues.append("Wikipedia API connectivity failed: ").append(e.getMessage()).append("; ");
                details.put("wikipedia_api_error", e.getMessage());
                details.put("wikipedia_api_reachable", false);
            }
            
            HealthStatus healthStatus = isHealthy ? HealthStatus.UP : HealthStatus.DOWN;
            String message = isHealthy ? 
                    "WikiCrawler service is healthy - file system accessible and Wikipedia API reachable" :
                    "WikiCrawler service has issues: " + issues.toString();
            
            LOG.debug("WikiCrawler health check completed: {} - {}", healthStatus, message);
            
            return HealthResult.builder("wikicrawler", healthStatus)
                    .details(details)
                    .build();
        });
    }
}