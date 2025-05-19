package com.krickert.search.config.consul.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.CachedConfigHolder;
import com.krickert.search.config.consul.ConfigurationValidator;
import com.krickert.search.config.consul.ConsulConfigFetcher;
import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import io.micronaut.context.event.ApplicationEventPublisher;

/**
 * Test-specific factory for creating DynamicConfigurationManager instances.
 * This factory is used in tests to create DynamicConfigurationManager instances
 * with mock dependencies.
 */
public class TestDynamicConfigurationManagerFactory {

    /**
     * Creates a new DynamicConfigurationManager with the specified dependencies.
     * This method is used in tests to create a DynamicConfigurationManager with mock dependencies.
     *
     * @param clusterName            the name of the cluster
     * @param consulConfigFetcher    the ConsulConfigFetcher to use
     * @param configurationValidator the ConfigurationValidator to use
     * @param cachedConfigHolder     the CachedConfigHolder to use
     * @param eventPublisher         the ApplicationEventPublisher to use
     * @param objectMapper           the ObjectMapper to use
     * @return a new DynamicConfigurationManager
     */
    public static DynamicConfigurationManagerImpl createDynamicConfigurationManager(
            String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher,
            com.krickert.search.config.consul.service.ConsulKvService consulKvService,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper
    ) {
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
