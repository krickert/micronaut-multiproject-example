package com.krickert.search.config.consul.event;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for notifying about configuration changes.
 * This service publishes events when configuration is changed in Consul KV store.
 */
@Singleton
public class ConfigChangeNotifier {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigChangeNotifier.class);
    
    private final ApplicationEventPublisher eventPublisher;
    private final String configPath;
    
    /**
     * Creates a new ConfigChangeNotifier with the specified event publisher.
     *
     * @param eventPublisher the publisher for application events
     * @param configPath the base path for configuration in Consul KV store
     */
    public ConfigChangeNotifier(ApplicationEventPublisher eventPublisher,
                               @Value("${consul.client.config.path:config/pipeline}") String configPath) {
        this.eventPublisher = eventPublisher;
        this.configPath = configPath;
        LOG.info("ConfigChangeNotifier initialized with config path: {}", configPath);
    }
    
    /**
     * Notifies about a configuration change.
     * This method publishes a ConfigChangeEvent and a RefreshEvent.
     *
     * @param keyPath the path of the key that was changed
     */
    public void notifyConfigChange(String keyPath) {
        LOG.debug("Notifying about configuration change for key: {}", keyPath);
        
        // Extract the key prefix (e.g., "pipeline.configs.pipeline1")
        String keyPrefix = extractKeyPrefix(keyPath);
        
        // Publish a ConfigChangeEvent
        ConfigChangeEvent event = new ConfigChangeEvent(keyPrefix);
        eventPublisher.publishEvent(event);
        LOG.debug("Published ConfigChangeEvent for key prefix: {}", keyPrefix);
        
        // Also publish a RefreshEvent to refresh @Refreshable beans
        eventPublisher.publishEvent(new RefreshEvent());
        LOG.debug("Published RefreshEvent");
    }
    
    /**
     * Extracts the key prefix from a full key path.
     * For example, "config/pipeline/pipeline.configs.pipeline1.service.chunker" -> "pipeline.configs.pipeline1"
     *
     * @param keyPath the full key path
     * @return the key prefix
     */
    private String extractKeyPrefix(String keyPath) {
        // Remove the config path prefix if present
        String key = keyPath;
        if (keyPath.startsWith(configPath)) {
            key = keyPath.substring(configPath.length());
            // Remove leading slash if present
            if (key.startsWith("/")) {
                key = key.substring(1);
            }
        }
        
        // Extract the prefix (up to the third dot)
        String[] parts = key.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        } else {
            // If there are fewer than 3 parts, return the whole key
            return key;
        }
    }
}