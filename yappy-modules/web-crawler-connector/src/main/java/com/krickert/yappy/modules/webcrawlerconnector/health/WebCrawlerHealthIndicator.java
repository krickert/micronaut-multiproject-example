package com.krickert.yappy.modules.webcrawlerconnector.health;

import io.micronaut.context.annotation.Value;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Health indicator for Web Crawler functionality.
 * Checks if Chrome/Chromium is available and can be used for headless browsing.
 */
@Singleton
public class WebCrawlerHealthIndicator implements HealthIndicator {
    
    private static final Logger LOG = LoggerFactory.getLogger(WebCrawlerHealthIndicator.class);
    
    @Value("${web.crawler.config.headless:true}")
    private boolean headless;
    
    @Value("${web.crawler.config.timeout-seconds:30}")
    private int timeoutSeconds;
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            boolean isHealthy = true;
            StringBuilder issues = new StringBuilder();
            
            // Check if Chrome/Chromium is available in the system
            try {
                // Try to find Chrome executable
                String chromeVersion = checkChromeAvailability();
                
                details.put("chrome_available", true);
                details.put("chrome_version", chromeVersion);
                details.put("headless_mode", headless);
                details.put("timeout_seconds", timeoutSeconds);
                
            } catch (Exception e) {
                LOG.warn("Chrome/Chromium availability check failed", e);
                isHealthy = false;
                issues.append("Chrome/Chromium not available: ").append(e.getMessage()).append("; ");
                details.put("chrome_available", false);
                details.put("chrome_error", e.getMessage());
            }
            
            // Check system resources for web crawling
            try {
                Runtime runtime = Runtime.getRuntime();
                long maxMemory = runtime.maxMemory();
                long freeMemory = runtime.freeMemory();
                long totalMemory = runtime.totalMemory();
                long usedMemory = totalMemory - freeMemory;
                
                details.put("max_memory_mb", maxMemory / (1024 * 1024));
                details.put("used_memory_mb", usedMemory / (1024 * 1024));
                details.put("free_memory_mb", freeMemory / (1024 * 1024));
                
                // Warn if memory usage is very high (>90%)
                double memoryUsagePercent = (double) usedMemory / maxMemory * 100;
                if (memoryUsagePercent > 90) {
                    issues.append("High memory usage: ").append(String.format("%.1f%%", memoryUsagePercent)).append("; ");
                }
                
            } catch (Exception e) {
                LOG.warn("System resource check failed", e);
                details.put("system_check_error", e.getMessage());
            }
            
            HealthStatus healthStatus = isHealthy ? HealthStatus.UP : HealthStatus.DOWN;
            String message = isHealthy ? 
                    "Web Crawler service is healthy - Chrome available and system resources sufficient" :
                    "Web Crawler service has issues: " + issues.toString();
            
            LOG.debug("Web Crawler health check completed: {} - {}", healthStatus, message);
            
            return HealthResult.builder("webcrawler", healthStatus)
                    .details(details)
                    .build();
        });
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
}