package com.krickert.search.config.consul.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.*;
// import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent; // Old event, no longer needed here
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent; // Import the new event
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Singleton;

/**
 * Factory for creating DynamicConfigurationManager instances.
 * This factory encapsulates the creation of ConsulKvService and other dependencies
 * required by DynamicConfigurationManager.
 */
@Singleton
public class DynamicConfigurationManagerFactory {

    private final ConsulConfigFetcher consulConfigFetcher;
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    // Update the type of the event publisher
    private final ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new DynamicConfigurationManagerFactory with the specified dependencies.
     *
     * @param consulConfigFetcher           the ConsulConfigFetcher to use
     * @param configurationValidator        the ConfigurationValidator to use
     * @param cachedConfigHolder            the CachedConfigHolder to use
     * @param eventPublisher                the ApplicationEventPublisher to use (for the new event type)
     * @param consulBusinessOperationsService the ConsulBusinessOperationsService to use
     * @param objectMapper                  the ObjectMapper to use
     */
    public DynamicConfigurationManagerFactory(
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher, // Updated type
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper
    ) {
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher; // Assign the correctly typed publisher
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new DynamicConfigurationManager with the specified cluster name.
     *
     * @param clusterName the name of the cluster
     * @return a new DynamicConfigurationManager
     */
    public DynamicConfigurationManager createDynamicConfigurationManager(String clusterName) {
        return new DynamicConfigurationManagerImpl(
                clusterName,
                consulConfigFetcher,
                configurationValidator,
                cachedConfigHolder,
                eventPublisher, // This will now correctly pass the ApplicationEventPublisher<PipelineClusterConfigChangeEvent>
                consulBusinessOperationsService,
                objectMapper
        );
    }
}