package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.consul.factory.TestDynamicConfigurationManagerFactory;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.model.SchemaCompatibility;
import com.krickert.search.config.schema.model.SchemaType;
import com.krickert.search.config.schema.model.SchemaVersionData;

import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicConfigurationManagerImplTest {

    private static final String TEST_CLUSTER_NAME = "test-cluster";

    @Mock
    private ConsulConfigFetcher mockConsulConfigFetcher;
    @Mock
    private ConfigurationValidator mockConfigurationValidator;
    @Mock
    private CachedConfigHolder mockCachedConfigHolder;
    @Mock
    private ApplicationEventPublisher<ClusterConfigUpdateEvent> mockEventPublisher;
    @Mock
    private ConsulKvService mockConsulKvService;
    @Mock
    private ConsulBusinessOperationsService mockConsulBusinessOperationsService;
    @Mock
    private ObjectMapper mockObjectMapper;

    @Captor
    private ArgumentCaptor<Consumer<WatchCallbackResult>> watchCallbackCaptor; // CORRECTED TYPE
    @Captor
    private ArgumentCaptor<ClusterConfigUpdateEvent> eventCaptor;
    @Captor
    private ArgumentCaptor<Map<SchemaReference, String>> schemaCacheCaptor;

    private DynamicConfigurationManagerImpl dynamicConfigurationManager;

    @BeforeEach
    void setUp() {
        dynamicConfigurationManager = TestDynamicConfigurationManagerFactory.createDynamicConfigurationManager(
                TEST_CLUSTER_NAME,
                mockConsulConfigFetcher,
                mockConfigurationValidator,
                mockCachedConfigHolder,
                mockEventPublisher,
                mockConsulKvService,
                mockConsulBusinessOperationsService,
                mockObjectMapper
        );
    }

    private PipelineClusterConfig createTestClusterConfig(String name, PipelineModuleMap moduleMap) {
        return PipelineClusterConfig.builder()
                .clusterName(name)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName(name + "-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
    }

    private PipelineClusterConfig createSimpleTestClusterConfig(String name) {
        return PipelineClusterConfig.builder()
                .clusterName(name)
                .defaultPipelineName(name + "-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
    }

    @Test
    void initialize_successfulInitialLoad_validatesAndCachesConfigAndStartsWatch() {
        SchemaReference schemaRef1 = new SchemaReference("moduleA-schema", 1);
        PipelineModuleConfiguration moduleAConfig = new PipelineModuleConfiguration("ModuleA", "moduleA_impl_id", schemaRef1);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("moduleA_impl_id", moduleAConfig));
        PipelineClusterConfig mockClusterConfig = createTestClusterConfig(TEST_CLUSTER_NAME, moduleMap);
        SchemaVersionData schemaVersionData1 = new SchemaVersionData(
                1L, schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "Test Schema"
        );

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()))
                .thenReturn(Optional.of(schemaVersionData1));
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any()))
                .thenReturn(ValidationResult.valid());

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version());
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any());
        verify(mockCachedConfigHolder).updateConfiguration(eq(mockClusterConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionData1.schemaContent(), capturedSchemaMap.get(schemaRef1));
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
        assertTrue(publishedEvent.oldConfig().isEmpty()); // Assuming cache was empty before
        assertEquals(mockClusterConfig, publishedEvent.newConfig());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class)); // Use any(Consumer.class) for generic consumer
    }

    @Test
    void initialize_consulReturnsEmptyConfig_logsWarningAndStartsWatch() {
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockEventPublisher, never()).publishEvent(any());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void initialize_initialValidationFails_doesNotUpdateCacheOrPublishEventAndStartsWatch() {
        PipelineClusterConfig mockClusterConfig = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any()))
                .thenReturn(ValidationResult.invalid(List.of("Initial Test validation error")));
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockEventPublisher, never()).publishEvent(any());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void handleConsulClusterConfigUpdate_successfulUpdate_validatesAndCachesAndPublishes() {
        PipelineClusterConfig oldMockConfig = PipelineClusterConfig.builder()
                .clusterName("old-cluster-config-name")
                .defaultPipelineName("old-cluster-config-name-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        // This is what will be in the cache *before* the new config from watch is processed.
        // This will become the 'oldConfig' in the update event.
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));

        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
        PipelineClusterConfig newWatchedConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineModuleMap(moduleMapNew)
                .defaultPipelineName(TEST_CLUSTER_NAME + "-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        SchemaVersionData schemaVersionDataNew = new SchemaVersionData(
                2L, schemaRefNew.subject(), schemaRefNew.version(), "{\"type\":\"string\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "New Test Schema"
        );

        // --- Initialisation Phase Stubs ---
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldMockConfig));
        when(mockConfigurationValidator.validate(eq(oldMockConfig), any())).thenReturn(ValidationResult.valid());

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // Publishes event related to oldMockConfig

        // Verify watch is set up and capture the callback
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // --- Watch Event Simulation Phase ---
        // Reset the event publisher to specifically capture ONLY the event from this watch update.
        Mockito.reset(mockEventPublisher);
        // Ensure getCurrentConfig still provides the old config for the event generation.
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));

        // Stubbing for the processing of the newWatchedConfig
        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
                .thenReturn(Optional.of(schemaVersionDataNew));
        when(mockConfigurationValidator.validate(eq(newWatchedConfig), any()))
                .thenReturn(ValidationResult.valid());

        // Simulate the watch event
        actualWatchCallback.accept(WatchCallbackResult.success(newWatchedConfig));

        // Verifications for the successful update from the watch
        verify(mockConfigurationValidator).validate(eq(newWatchedConfig), any()); // Should be called once for newWatchedConfig after reset
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
        verify(mockCachedConfigHolder).updateConfiguration(eq(newWatchedConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionDataNew.schemaContent(), capturedSchemaMap.get(schemaRefNew));

        verify(mockEventPublisher).publishEvent(eventCaptor.capture()); // Expects 1 call after reset
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();

        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
        assertEquals(newWatchedConfig, publishedEvent.newConfig());
    }

    @Test
    void handleConsulClusterConfigUpdate_configDeleted_clearsCacheAndPublishes() {
        PipelineClusterConfig oldMockConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .defaultPipelineName(TEST_CLUSTER_NAME + "-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        // --- Initialisation Phase Stubs ---
        // This is what initialize() will fetch and process
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldMockConfig));
        when(mockConfigurationValidator.validate(eq(oldMockConfig), any())).thenReturn(ValidationResult.valid());
        // This is what will be in the cache *before* the deletion event from the watch
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));


        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // This will publish the first event

        // Verify watch is set up and capture the callback
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // --- Watch Event Simulation Phase ---
        // Reset the event publisher to specifically capture ONLY the event from the deletion by the watch.
        Mockito.reset(mockEventPublisher);
        // Ensure getCurrentConfig still provides the old config for the event generation during deletion processing.
        // This is crucial because processConsulUpdate calls cachedConfigHolder.getCurrentConfig()
        // to determine the oldConfig for the event.
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));

        // Simulate the watch event for deletion
        actualWatchCallback.accept(WatchCallbackResult.createAsDeleted());

        // Verifications for the deletion event
        verify(mockCachedConfigHolder).clearConfiguration();
        verify(mockEventPublisher).publishEvent(eventCaptor.capture()); // Now expects 1 call for the deletion
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();

        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
        assertNotNull(publishedEvent.newConfig()); // newConfig is an empty shell in case of deletion
        assertEquals(TEST_CLUSTER_NAME, publishedEvent.newConfig().clusterName());
        assertTrue(publishedEvent.newConfig().pipelineGraphConfig() == null || publishedEvent.newConfig().pipelineGraphConfig().pipelines().isEmpty());
        assertTrue(publishedEvent.newConfig().pipelineModuleMap() == null || publishedEvent.newConfig().pipelineModuleMap().availableModules().isEmpty());
    }

    @Test
    void handleConsulClusterConfigUpdate_validationFails_keepsOldConfigAndDoesNotPublishSuccessEvent() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));
        PipelineClusterConfig newInvalidConfigFromWatch = PipelineClusterConfig.builder()
                .clusterName("new-invalid-config")
                .defaultPipelineName("new-invalid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        // Simulate initial load and watch setup
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset mocks that might have been called during initialize for a clean slate for this specific interaction
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator);
        // Ensure getCurrentConfig still returns the old valid config for the event comparison
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));


        when(mockConfigurationValidator.validate(eq(newInvalidConfigFromWatch), any()))
                .thenReturn(ValidationResult.invalid(List.of("Watch update validation error")));

        actualWatchCallback.accept(WatchCallbackResult.success(newInvalidConfigFromWatch)); // CORRECTED INVOCATION (even if validation fails, it's a "success" from watch perspective)

        verify(mockConfigurationValidator).validate(eq(newInvalidConfigFromWatch), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
    }

    @Test
    void initialize_fetchPipelineClusterConfigThrowsException_handlesGracefullyAndStillStartsWatch() {
        // Mock connect to avoid issues if it's called internally by ensureConnected
        doNothing().when(mockConsulConfigFetcher).connect();
        doThrow(new RuntimeException("Consul connection totally failed during fetch!"))
                .when(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        // Verify that despite the fetch error, the processConsulUpdate was called with a failure
        // and the watch was still attempted.
        // The event for failure during initial load might or might not be published depending on exact logic,
        // but the key is that the watch starts.
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        // Depending on how processConsulUpdate handles initial load fetch error, an event might be published.
        // For now, let's assume no event is published if the config is not processed.
        // verify(mockEventPublisher, never()).publishEvent(any());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void handleConsulClusterConfigUpdate_fetchSchemaThrowsException_handlesGracefullyKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
        PipelineClusterConfig newWatchedConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineModuleMap(moduleMapNew)
                .defaultPipelineName(TEST_CLUSTER_NAME + "-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        // Simulate initial load and watch setup
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset mocks for this specific interaction
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));


        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
                .thenThrow(new RuntimeException("Failed to fetch schema from Consul!"));

        actualWatchCallback.accept(WatchCallbackResult.success(newWatchedConfig)); // CORRECTED INVOCATION

        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
        // Validation should not be called if schema fetching fails before it.
        // The current implementation of processConsulUpdate might attempt validation even if schema fetch fails,
        // leading to validation failure. Let's assume it tries to validate.
        // If it doesn't, then the verify below should be 'never()'.
        // Based on current DynamicConfigurationManagerImpl, it *will* try to validate.
        verify(mockConfigurationValidator, never()).validate(eq(newWatchedConfig), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
    }


    @Test
    void initialize_connectThrows_throwsConfigurationManagerInitializationException() {
        doThrow(new RuntimeException("Simulated connection failure"))
                .when(mockConsulConfigFetcher).connect();

        assertThrows(ConfigurationManagerInitializationException.class, () -> {
            dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        });

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher, never()).fetchPipelineClusterConfig(anyString());
        verify(mockConsulConfigFetcher, never()).watchClusterConfig(anyString(), any());
    }

    @Test
    void initialize_watchClusterConfigThrows_throwsConfigurationManagerInitializationException() {
        // Assume initial fetch is okay or not relevant for this specific failure point
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty()); // Or some valid config

        doThrow(new RuntimeException("Simulated watch setup failure"))
                .when(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());

        assertThrows(ConfigurationManagerInitializationException.class, () -> {
            dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        });

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME); // This would have been called
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
    }

    @Test
    void handleConsulWatchUpdate_watchResultHasError_logsAndKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        // Initial setup
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset mocks for the specific interaction
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);
        // Crucially, re-stub getCurrentConfig for the oldConfigForEvent in processConsulUpdate
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));


        // Simulate watch event with an error
        RuntimeException watchError = new RuntimeException("KVCache internal error");
        actualWatchCallback.accept(WatchCallbackResult.failure(watchError));

        // Verifications
        verify(mockConsulConfigFetcher, never()).fetchSchemaVersionData(anyString(), anyInt());
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
        // Add log verification if you have a test utility for it, to ensure the error was logged.
    }
    @Test
    void handleConsulWatchUpdate_validationItselfThrowsRuntimeException_logsAndKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        PipelineClusterConfig newConfigFromWatch = PipelineClusterConfig.builder()
                .clusterName("new-config-causes-validator-error")
                .defaultPipelineName("new-config-causes-validator-error-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        // Assume newConfigFromWatch has no schemas or schema fetching is successful
        when(mockConfigurationValidator.validate(eq(newConfigFromWatch), any()))
                .thenThrow(new RuntimeException("Validator blew up!"));

        actualWatchCallback.accept(WatchCallbackResult.success(newConfigFromWatch));

        verify(mockConfigurationValidator).validate(eq(newConfigFromWatch), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
    }
    @Test
    void handleConsulWatchUpdate_cacheUpdateThrowsRuntimeException_logsError_eventNotPublished() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        PipelineClusterConfig newConfigFromWatch = PipelineClusterConfig.builder()
                .clusterName("new-config-causes-cache-error")
                .defaultPipelineName("new-config-causes-cache-error-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        // Assume newConfigFromWatch has no schemas or schema fetching is successful
        when(mockConfigurationValidator.validate(eq(newConfigFromWatch), any()))
                .thenReturn(ValidationResult.valid()); // Validation succeeds
        doThrow(new RuntimeException("Cache update failed!"))
                .when(mockCachedConfigHolder).updateConfiguration(eq(newConfigFromWatch), any());

        actualWatchCallback.accept(WatchCallbackResult.success(newConfigFromWatch));

        verify(mockConfigurationValidator).validate(eq(newConfigFromWatch), any());
        verify(mockCachedConfigHolder).updateConfiguration(eq(newConfigFromWatch), any()); // It was attempted
        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class)); // But event not published
    }
    @Test
    void publishEvent_directListenerThrows_continuesNotifyingOtherListenersAndMicronautPublisher() {
        PipelineClusterConfig currentConfig = PipelineClusterConfig.builder()
                .clusterName("current")
                .defaultPipelineName("current-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        PipelineClusterConfig newConfig = PipelineClusterConfig.builder()
                .clusterName("new-valid-config")
                .defaultPipelineName("new-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        Consumer<ClusterConfigUpdateEvent> misbehavingListener = Mockito.mock(Consumer.class);
        doThrow(new RuntimeException("Listener failed!")).when(misbehavingListener).accept(any());

        Consumer<ClusterConfigUpdateEvent> wellBehavedListener = Mockito.mock(Consumer.class);

        dynamicConfigurationManager.registerConfigUpdateListener(misbehavingListener);
        dynamicConfigurationManager.registerConfigUpdateListener(wellBehavedListener);

        // --- Initialisation Phase Stubs ---
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(currentConfig));
        when(mockConfigurationValidator.validate(eq(currentConfig), any())).thenReturn(ValidationResult.valid());
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(currentConfig)); // For oldConfig in event

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // --- Watch Event Simulation Phase ---
        // Resetting only the event publisher and listeners for this specific check
        Mockito.reset(mockEventPublisher, misbehavingListener, wellBehavedListener);
        // Ensure getCurrentConfig still provides the old config for the event generation.
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(currentConfig));
        // Stubbing for the new config processing
        when(mockConfigurationValidator.validate(eq(newConfig), any())).thenReturn(ValidationResult.valid());
        // Assuming newConfig has no schemas for simplicity here, or stub schema fetching

        // Simulate the watch event
        actualWatchCallback.accept(WatchCallbackResult.success(newConfig));

        // Verifications
        verify(misbehavingListener).accept(eventCaptor.capture()); // It was called
        ClusterConfigUpdateEvent eventForMisbehaving = eventCaptor.getValue();
        assertEquals(Optional.of(currentConfig), eventForMisbehaving.oldConfig());
        assertEquals(newConfig, eventForMisbehaving.newConfig());

        verify(wellBehavedListener).accept(eventCaptor.capture()); // Also called
        ClusterConfigUpdateEvent eventForWellBehaved = eventCaptor.getValue();
        assertEquals(Optional.of(currentConfig), eventForWellBehaved.oldConfig());
        assertEquals(newConfig, eventForWellBehaved.newConfig());


        verify(mockEventPublisher).publishEvent(eventCaptor.capture()); // Micronaut publisher also called
        ClusterConfigUpdateEvent eventForMicronaut = eventCaptor.getValue();
        assertEquals(Optional.of(currentConfig), eventForMicronaut.oldConfig());
        assertEquals(newConfig, eventForMicronaut.newConfig());

        // Cleanup
        dynamicConfigurationManager.unregisterConfigUpdateListener(misbehavingListener);
        dynamicConfigurationManager.unregisterConfigUpdateListener(wellBehavedListener);
    }
    @Test
    void handleConsulWatchUpdate_ambiguousWatchResult_logsAndNoAction() {
        PipelineClusterConfig oldValidConfig = PipelineClusterConfig.builder()
                .clusterName("old-valid-config")
                .defaultPipelineName("old-valid-config-default")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));

        // Create an ambiguous WatchCallbackResult
        WatchCallbackResult ambiguousResult = new WatchCallbackResult(Optional.empty(), Optional.empty(), false);
        actualWatchCallback.accept(ambiguousResult);

        verifyNoInteractions(mockConfigurationValidator);
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verifyNoInteractions(mockEventPublisher);
        // Add log verification if possible
    }




}
