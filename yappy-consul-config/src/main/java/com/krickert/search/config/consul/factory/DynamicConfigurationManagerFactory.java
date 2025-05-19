package com.krickert.search.config.consul.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.CachedConfigHolder;
import com.krickert.search.config.consul.ConfigurationValidator;
import com.krickert.search.config.consul.ConsulConfigFetcher;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
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
    private final ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher;
    private final ConsulKvService consulKvService;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new DynamicConfigurationManagerFactory with the specified dependencies.
     *
     * @param consulConfigFetcher the ConsulConfigFetcher to use
     * @param configurationValidator the ConfigurationValidator to use
     * @param cachedConfigHolder the CachedConfigHolder to use
     * @param eventPublisher the ApplicationEventPublisher to use
     * @param consulKvService the ConsulKvService to use
     * @param objectMapper the ObjectMapper to use
     */
    public DynamicConfigurationManagerFactory(
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher,
            ConsulKvService consulKvService,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper
    ) {
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        this.consulKvService = consulKvService;
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
                eventPublisher,
                consulKvService,
                consulBusinessOperationsService,
                objectMapper
        );
    }
}
