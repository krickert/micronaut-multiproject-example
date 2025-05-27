package com.krickert.search.config.consul.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.*;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskExecutors; // Import
import jakarta.inject.Inject;               // Import
import jakarta.inject.Named;                // Import
import jakarta.inject.Singleton;

import java.util.concurrent.ExecutorService; // Import

/**
 * Factory for creating DynamicConfigurationManager instances.
 */
@Singleton
public class DynamicConfigurationManagerFactory {

    private final ConsulConfigFetcher consulConfigFetcher;
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    private final ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;
    private final DefaultConfigurationSeeder defaultConfigurationSeeder;
    private final ExecutorService eventPublishingExecutor; // 👈 ADDED

    @Inject // Ensure @Inject is on the constructor
    public DynamicConfigurationManagerFactory(
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper,
            DefaultConfigurationSeeder defaultConfigurationSeeder,
            @Named(TaskExecutors.SCHEDULED) ExecutorService eventPublishingExecutor // 👈 INJECT IT
    ) {
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.objectMapper = objectMapper;
        this.defaultConfigurationSeeder = defaultConfigurationSeeder;
        this.eventPublishingExecutor = eventPublishingExecutor; // 👈 STORE IT
    }

    public DynamicConfigurationManager createDynamicConfigurationManager(String clusterName) {
        return new DynamicConfigurationManagerImpl(
                clusterName, // This is effectively the @Value("${app.config.cluster-name}") for this instance
                consulConfigFetcher,
                configurationValidator,
                cachedConfigHolder,
                eventPublisher,
                consulBusinessOperationsService,
                objectMapper,
                defaultConfigurationSeeder,
                eventPublishingExecutor // 👈 PASS IT
        );
    }
}