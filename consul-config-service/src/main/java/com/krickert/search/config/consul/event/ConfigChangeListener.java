package com.krickert.search.config.consul.event;

import com.krickert.search.config.consul.service.ConfigurationService;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener for configuration change events.
 * This class listens for ConfigChangeEvent and delegates to ConfigurationService.
 */
@Singleton
public class ConfigChangeListener implements ApplicationEventListener<ConfigChangeEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigChangeListener.class);
    
    private final ConfigurationService configurationService;
    
    /**
     * Constructor with ConfigurationService.
     *
     * @param configurationService the service for managing configuration
     */
    public ConfigChangeListener(ConfigurationService configurationService) {
        this.configurationService = configurationService;
        LOG.info("ConfigChangeListener initialized");
    }
    
    /**
     * Handles ConfigChangeEvent by delegating to ConfigurationService.
     *
     * @param event the configuration change event
     */
    @Override
    public void onApplicationEvent(ConfigChangeEvent event) {
        LOG.debug("Received ConfigChangeEvent for key prefix: {}", event.getKeyPrefix());
        configurationService.onConfigChange(event);
    }
}