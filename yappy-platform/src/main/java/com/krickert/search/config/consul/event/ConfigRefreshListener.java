package com.krickert.search.config.consul.event;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener for configuration change events.
 * This listener triggers a refresh of @Refreshable beans when configuration changes.
 */
@Singleton
@Requires(property = "consul.client.config.enabled", value = "true")
public class ConfigRefreshListener implements ApplicationEventListener<ConfigChangeEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigRefreshListener.class);
    
    private final ApplicationEventPublisher eventPublisher;
    
    /**
     * Creates a new ConfigRefreshListener with the specified event publisher.
     *
     * @param eventPublisher the publisher for application events
     */
    public ConfigRefreshListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        LOG.info("ConfigRefreshListener initialized");
    }
    
    /**
     * Handles a ConfigChangeEvent by publishing a RefreshEvent.
     *
     * @param event the configuration change event
     */
    @Override
    public void onApplicationEvent(ConfigChangeEvent event) {
        LOG.debug("Received ConfigChangeEvent for key prefix: {}", event.getKeyPrefix());
        
        // Publish a RefreshEvent to refresh @Refreshable beans
        eventPublisher.publishEvent(new RefreshEvent());
        LOG.debug("Published RefreshEvent in response to ConfigChangeEvent");
    }
}