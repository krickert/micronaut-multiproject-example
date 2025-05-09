package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;

import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class) // Integrates Mockito with JUnit 5
class DynamicConfigurationManagerImplTest {

    private static final String TEST_CLUSTER_NAME = "test-cluster";

    // Mocks for dependencies of DynamicConfigurationManagerImpl
    @Mock
    private ConsulConfigFetcher mockConsulConfigFetcher;
    @Mock
    private ConfigurationValidator mockConfigurationValidator;
    @Mock
    private CachedConfigHolder mockCachedConfigHolder;
    @Mock
    private ApplicationEventPublisher<ClusterConfigUpdateEvent> mockEventPublisher;

    // ArgumentCaptor to capture the callback passed to watchClusterConfig
    @Captor
    private ArgumentCaptor<Consumer<Optional<PipelineClusterConfig>>> watchCallbackCaptor;

    // ArgumentCaptor to capture the event published
    @Captor
    private ArgumentCaptor<ClusterConfigUpdateEvent> eventCaptor;


    // The class under test. Mocks will be injected into this instance.
    // Note: We are not using Micronaut's @Value for clusterName here,
    // as it would require a running Micronaut context for the unit test.
    // We'll pass it directly or simulate its injection if needed for more complex scenarios.
    // For this test, we will construct it manually with the cluster name.
    private DynamicConfigurationManagerImpl dynamicConfigurationManager;

    @BeforeEach
    void setUp() {
        // Manually construct the class under test, passing the cluster name and mocks.
        // This bypasses the need for @Value injection in a pure unit test.
        dynamicConfigurationManager = new DynamicConfigurationManagerImpl(
                TEST_CLUSTER_NAME,
                mockConsulConfigFetcher,
                mockConfigurationValidator,
                mockCachedConfigHolder,
                mockEventPublisher
        );
    }

    @Test
    void initialize_successfulInitialLoad_validatesAndCachesConfigAndStartsWatch() {
        // --- Arrange ---
        // 1. Create mock data
        SchemaReference schemaRef1 = new SchemaReference("moduleA-schema", 1);
        PipelineModuleConfiguration moduleAConfig = new PipelineModuleConfiguration("ModuleA", "moduleA_impl_id", schemaRef1);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("moduleA_impl_id", moduleAConfig));

        PipelineClusterConfig mockClusterConfig = new PipelineClusterConfig();
        mockClusterConfig.setClusterName(TEST_CLUSTER_NAME);
        mockClusterConfig.setPipelineModuleMap(moduleMap);
        // ... add other necessary fields to mockClusterConfig if validator checks them

        SchemaVersionData schemaVersionData1 = new SchemaVersionData(
                1L, schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\"}",
                com.krickert.search.config.schema.registry.model.SchemaType.JSON_SCHEMA, // Ensure full path if ambiguous
                com.krickert.search.config.schema.registry.model.SchemaCompatibility.NONE,
                java.time.Instant.now(), "Test Schema"
        );

        // 2. Define behavior for mockConsulConfigFetcher
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()))
                .thenReturn(Optional.of(schemaVersionData1));

        // 3. Define behavior for mockConfigurationValidator
        // We need to capture the schemaProvider function to test it, or ensure it works as expected.
        // For simplicity here, we assume if it's called with the right config and a working provider, it's valid.
        // The Function<SchemaReference, Optional<String>> is the schemaContentProvider
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any(Function.class)))
                .thenAnswer(invocation -> {
                    // You could even test the provided schemaProvider here if needed:
                    // Function<SchemaReference, Optional<String>> provider = invocation.getArgument(1);
                    // Optional<String> content = provider.apply(schemaRef1);
                    // assertTrue(content.isPresent());
                    // assertEquals(schemaVersionData1.getSchemaContent(), content.get());
                    return ValidationResult.valid();
                });


        // --- Act ---
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // Call the method directly for testing this unit

        // --- Assert ---
        // Verify interactions with mocks
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version());
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any(Function.class));

        // Verify that the cache was updated with the correct config and schema map
        ArgumentCaptor<Map<SchemaReference, String>> schemaCacheCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockCachedConfigHolder).updateConfiguration(eq(mockClusterConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionData1.getSchemaContent(), capturedSchemaMap.get(schemaRef1));

        // Verify that an event was published
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
        assertTrue(publishedEvent.oldConfig().isEmpty(), "Old config should be empty on initial load");
        assertEquals(mockClusterConfig, publishedEvent.newConfig(), "New config should be the loaded config");

        // Verify that the watch was started
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void initialize_consulReturnsEmptyConfig_logsErrorAndStartsWatch() {
        // --- Arrange ---
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty());

        // --- Act ---
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        // --- Assert ---
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        // Ensure no validation or caching happens if config is empty
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockEventPublisher, never()).publishEvent(any());
        // Crucially, verify that the watch is still started
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
        // You would also check your logs for the error message, though that's harder in pure unit tests
        // without special log appenders.
    }

    @Test
    void initialize_validationFails_doesNotUpdateCacheOrPublishEventAndStartsWatch() {
        // --- Arrange ---
        PipelineClusterConfig mockClusterConfig = new PipelineClusterConfig(); // simple config
        mockClusterConfig.setClusterName(TEST_CLUSTER_NAME);
        // No modules or schemas needed for this specific validation failure test path

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        // Simulate validation failure
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any(Function.class)))
                .thenReturn(ValidationResult.invalid(List.of("Test validation error")));

        // --- Act ---
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        // --- Assert ---
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        // Schema fetching might still happen before validation, depending on implementation order
        // verify(mockConsulConfigFetcher, times(X)).fetchSchemaVersionData(anyString(), anyInt());
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any(Function.class));
        // Ensure cache and event publisher are NOT interacted with if validation fails
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockEventPublisher, never()).publishEvent(any());
        // Verify watch is still started
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    // TODO: Add tests for handleConsulClusterConfigUpdate:
    // - Successful update
    // - Update leads to validation failure
    // - Config deleted in Consul
}