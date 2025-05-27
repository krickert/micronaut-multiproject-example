package com.krickert.search.config.consul.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.*;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;

import io.micronaut.context.event.ApplicationEventPublisher;
// No Micronaut specific TaskExecutors needed here if we pass a general ExecutorService for tests
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors; // For a simple default executor in tests

public class TestDynamicConfigurationManagerFactory {

    public static DynamicConfigurationManagerImpl createDynamicConfigurationManager(
            String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher,
            // ConsulKvService consulKvService, // Not directly used by DCM constructor
            ConsulBusinessOperationsService consulBusinessOperationsService, // Needed for DefaultConfigurationSeeder
            ObjectMapper objectMapper,
            DefaultConfigurationSeeder seeder, // Pass the seeder instance (can be a mock or real for test)
            ExecutorService eventPublishingExecutor // 👈 ADDED ExecutorService
    ) {
        return new DynamicConfigurationManagerImpl(
                clusterName,
                consulConfigFetcher,
                configurationValidator,
                cachedConfigHolder,
                eventPublisher,
                consulBusinessOperationsService,
                objectMapper,
                seeder, // Pass the provided seeder
                eventPublishingExecutor // 👈 PASS IT
        );
    }

    // Overloaded version if you want to provide a default test executor
    public static DynamicConfigurationManagerImpl createDynamicConfigurationManager(
            String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper,
            DefaultConfigurationSeeder seeder
    ) {
        // Creates a simple executor for testing purposes if one isn't provided.
        // For more control, tests should ideally provide their own or a mock.
        ExecutorService defaultTestExecutor = Executors.newSingleThreadScheduledExecutor();
        return createDynamicConfigurationManager(clusterName, consulConfigFetcher, configurationValidator,
                cachedConfigHolder, eventPublisher, consulBusinessOperationsService, objectMapper,
                seeder, defaultTestExecutor);
    }


    // Example of how you might create a DefaultConfigurationSeeder for tests if you don't want to mock it fully:
    // public static DefaultConfigurationSeeder createTestSeeder(ConsulBusinessOperationsService mockConsulOps) {
    // return new DefaultConfigurationSeeder(mockConsulOps);
    // }
}