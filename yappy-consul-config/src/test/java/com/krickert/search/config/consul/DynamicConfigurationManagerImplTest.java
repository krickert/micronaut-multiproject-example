package com.krickert.search.config.consul;

import com.fasterxml.jackson.databind.ObjectMapper;
// Keep the import for ClusterConfigUpdateEvent because the direct listeners still use it
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.consul.factory.TestDynamicConfigurationManagerFactory;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent; // Ensure this is imported
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.model.SchemaCompatibility;
import com.krickert.search.config.schema.model.SchemaType;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Inject;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private ApplicationEventPublisher<PipelineClusterConfigChangeEvent> mockEventPublisher;
    @Mock
    private ConsulKvService mockConsulKvService;
    @Mock
    private ConsulBusinessOperationsService mockConsulBusinessOperationsService;
    @Mock
    private ObjectMapper mockObjectMapper; // Kept for now, though not directly used by SUT constructor
    @Mock private DefaultConfigurationSeeder mockDefaultConfigurationSeeder;
    @Mock private ExecutorService mockEventPublishingExecutor;

    // REMOVED @Inject - This test does not run in a Micronaut context
    private ObjectMapper realObjectMapper = new ObjectMapper(); // Instantiate directly for use in helper

    @Captor
    private ArgumentCaptor<Consumer<WatchCallbackResult>> watchCallbackCaptor;
    @Captor
    private ArgumentCaptor<PipelineClusterConfigChangeEvent> eventCaptor; // For Micronaut events
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
                mockConsulBusinessOperationsService,
                new ObjectMapper(),
                mockDefaultConfigurationSeeder,
                mockEventPublishingExecutor // Pass the mock executor
        );

        // Tell Mockito what to do when mockEventPublishingExecutor.execute() is called:
        // Capture the Runnable and run it immediately.
        // Use lenient() to avoid "unnecessary stubbing" errors when this stubbing is not used in all tests
        lenient().doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            if (task != null) {
                task.run(); // Execute the task (which calls publishMicronautEvent on mockEventPublisher)
            }
            return null; // execute(Runnable) is void
        }).when(mockEventPublishingExecutor).execute(any(Runnable.class));

        // Your general lenient stubs
        lenient().when(mockConsulConfigFetcher.fetchPipelineClusterConfig(anyString())).thenReturn(Optional.empty());
        lenient().when(mockConfigurationValidator.validate(any(), any())).thenReturn(ValidationResult.valid());
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

    private PipelineClusterConfig withTopics(PipelineClusterConfig original, Set<String> newTopics) {
        return new PipelineClusterConfig(
                original.clusterName(),
                original.pipelineGraphConfig(),
                original.pipelineModuleMap(),
                original.defaultPipelineName(),
                newTopics,
                original.allowedGrpcServices()
        );
    }

    @Test
    void initialize_successfulInitialLoad_validatesAndCachesConfigAndStartsWatch() {
        // ... (your Arrange section: mockClusterConfig, schemaVersionData1, etc.) ...
        SchemaReference schemaRef1 = new SchemaReference("moduleA-schema", 1);
        PipelineModuleConfiguration moduleAConfig = new PipelineModuleConfiguration("ModuleA", "moduleA_impl_id", schemaRef1);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("moduleA_impl_id", moduleAConfig));
        PipelineClusterConfig mockClusterConfig = createTestClusterConfig(TEST_CLUSTER_NAME, moduleMap);
        SchemaVersionData schemaVersionData1 = new SchemaVersionData(
                1L, schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "Test Schema"
        );

        doNothing().when(mockConsulConfigFetcher).connect();
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()))
                .thenReturn(Optional.of(schemaVersionData1));
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any()))
                .thenReturn(ValidationResult.valid());

        // Act
       dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        // Assert
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version());
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any());
        verify(mockCachedConfigHolder).updateConfiguration(eq(mockClusterConfig), schemaCacheCaptor.capture());
        // ... (your assertions for schemaCacheCaptor.getValue()) ...
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionData1.schemaContent(), capturedSchemaMap.get(schemaRef1));

        // Instead of verifying that the event was published, we'll verify that it was queued for publication
        // This is because the event is published in the postConstructInitialize method, which we don't want to call
        // in the test because it would call initialize again

        // Use reflection to access the private initialLoadEventToPublish field
        PipelineClusterConfigChangeEvent queuedEvent = null;
        try {
            java.lang.reflect.Field field = DynamicConfigurationManagerImpl.class.getDeclaredField("initialLoadEventToPublish");
            field.setAccessible(true);
            queuedEvent = (PipelineClusterConfigChangeEvent) field.get(dynamicConfigurationManager);

            // Verify that the event was queued
            assertNotNull(queuedEvent, "Event should be queued for publication");
            assertFalse(queuedEvent.isDeletion(), "Event should not be a deletion event");
            assertEquals(mockClusterConfig, queuedEvent.newConfig(), "Event should have the correct config");
            assertEquals(TEST_CLUSTER_NAME, queuedEvent.clusterName(), "Event should have the correct cluster name");

            // Manually publish the event to verify that the executor and event publisher work correctly
            // This simulates what would happen in the postConstructInitialize method
            // Note: The stubbing for mockEventPublishingExecutor.execute() is already done in setUp()

            // Use reflection to access the private publishMicronautEvent method
            java.lang.reflect.Method method = DynamicConfigurationManagerImpl.class.getDeclaredMethod("publishMicronautEvent", PipelineClusterConfigChangeEvent.class);
            method.setAccessible(true);
            method.invoke(dynamicConfigurationManager, queuedEvent);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | java.lang.reflect.InvocationTargetException e) {
            fail("Failed to access private members via reflection: " + e.getMessage());
        }

        // Now verify that the event was published
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        PipelineClusterConfigChangeEvent publishedEvent = eventCaptor.getValue();
        assertFalse(publishedEvent.isDeletion(), "Published event should not be a deletion event");
        assertEquals(mockClusterConfig, publishedEvent.newConfig(), "Published event should have the correct config");
        assertEquals(TEST_CLUSTER_NAME, publishedEvent.clusterName(), "Published event should have the correct cluster name");

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
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
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class)); // Corrected class

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
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class));

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void handleConsulClusterConfigUpdate_successfulUpdate_validatesAndCachesAndPublishes() {
        PipelineClusterConfig oldMockConfig = createSimpleTestClusterConfig("old-cluster-config-name");
        // For the initial load part of the test
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldMockConfig));
        when(mockConfigurationValidator.validate(eq(oldMockConfig), any())).thenReturn(ValidationResult.valid());
        // No need to stub mockCachedConfigHolder.getCurrentConfig() for initial load as it's not used for the Micronaut event

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // This will publish an initial event

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset mocks for the update phase
        Mockito.reset(mockEventPublisher); // Reset to verify only the update event

        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
        PipelineClusterConfig newWatchedConfig = createTestClusterConfig(TEST_CLUSTER_NAME, moduleMapNew);
        SchemaVersionData schemaVersionDataNew = new SchemaVersionData(
                2L, schemaRefNew.subject(), schemaRefNew.version(), "{\"type\":\"string\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "New Test Schema"
        );

        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
                .thenReturn(Optional.of(schemaVersionDataNew));
        when(mockConfigurationValidator.validate(eq(newWatchedConfig), any()))
                .thenReturn(ValidationResult.valid());
        // No need to stub mockCachedConfigHolder.getCurrentConfig() for the Micronaut event part

        actualWatchCallback.accept(WatchCallbackResult.success(newWatchedConfig));

        verify(mockConfigurationValidator).validate(eq(newWatchedConfig), any());
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
        verify(mockCachedConfigHolder).updateConfiguration(eq(newWatchedConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionDataNew.schemaContent(), capturedSchemaMap.get(schemaRefNew));

        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        PipelineClusterConfigChangeEvent publishedEvent = eventCaptor.getValue(); // Type is correct
        assertFalse(publishedEvent.isDeletion());
        assertEquals(newWatchedConfig, publishedEvent.newConfig());
        assertEquals(TEST_CLUSTER_NAME, publishedEvent.clusterName());
    }

    @Test
    void handleConsulClusterConfigUpdate_configDeleted_clearsCacheAndPublishes() {
        PipelineClusterConfig oldMockConfig = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldMockConfig));
        when(mockConfigurationValidator.validate(eq(oldMockConfig), any())).thenReturn(ValidationResult.valid());
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig)); // Stub for wasPresent check
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig)); // For wasPresent check before clear in SUT

        actualWatchCallback.accept(WatchCallbackResult.createAsDeleted());

        verify(mockCachedConfigHolder).clearConfiguration();
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());

        PipelineClusterConfigChangeEvent publishedEvent = eventCaptor.getValue(); // Type is correct
        assertTrue(publishedEvent.isDeletion());
        assertNull(publishedEvent.newConfig());
        assertEquals(TEST_CLUSTER_NAME, publishedEvent.clusterName());
    }

    @Test
    void handleConsulClusterConfigUpdate_validationFails_keepsOldConfigAndDoesNotPublishSuccessEvent() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        // For initial load
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        // No need to stub mockCachedConfigHolder.getCurrentConfig() for initial load Micronaut event

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator);
        // No need to stub mockCachedConfigHolder.getCurrentConfig() if no event is expected to be published

        PipelineClusterConfig newInvalidConfigFromWatch = createSimpleTestClusterConfig("new-invalid-config");
        when(mockConfigurationValidator.validate(eq(newInvalidConfigFromWatch), any()))
                .thenReturn(ValidationResult.invalid(List.of("Watch update validation error")));

        actualWatchCallback.accept(WatchCallbackResult.success(newInvalidConfigFromWatch));

        verify(mockConfigurationValidator).validate(eq(newInvalidConfigFromWatch), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class)); // Corrected class
    }

    @Test
    void initialize_fetchPipelineClusterConfigThrowsException_handlesGracefullyAndStillStartsWatch() {
        doNothing().when(mockConsulConfigFetcher).connect();
        doThrow(new RuntimeException("Consul connection totally failed during fetch!"))
                .when(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any(Consumer.class));
    }

    @Test
    void handleConsulClusterConfigUpdate_fetchSchemaThrowsException_handlesGracefullyKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        // For initial load
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        // No need to stub mockCachedConfigHolder.getCurrentConfig() for initial load Micronaut event

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator);
        // No need to stub mockCachedConfigHolder.getCurrentConfig() if no event is expected

        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
        PipelineClusterConfig newWatchedConfig = createTestClusterConfig(TEST_CLUSTER_NAME, moduleMapNew);

        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
                .thenThrow(new RuntimeException("Failed to fetch schema from Consul!"));

        actualWatchCallback.accept(WatchCallbackResult.success(newWatchedConfig));

        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
        verify(mockConfigurationValidator, never()).validate(eq(newWatchedConfig), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class)); // Corrected class
    }

    @Test
    void initialize_connectThrows_throwsConfigurationManagerInitializationException() {
        doThrow(new RuntimeException("Simulated connection failure"))
                .when(mockConsulConfigFetcher).connect();
        assertThrows(ConfigurationManagerInitializationException.class, () -> dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME));
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher, never()).fetchPipelineClusterConfig(anyString());
        verify(mockConsulConfigFetcher, never()).watchClusterConfig(anyString(), any());
    }

    @Test
    void initialize_watchClusterConfigThrows_throwsConfigurationManagerInitializationException() {
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("Simulated watch setup failure"))
                .when(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
        assertThrows(ConfigurationManagerInitializationException.class, () -> dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME));
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
    }

    @Test
    void handleConsulWatchUpdate_watchResultHasError_logsAndKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);

        RuntimeException watchError = new RuntimeException("KVCache internal error");
        actualWatchCallback.accept(WatchCallbackResult.failure(watchError));

        verify(mockConsulConfigFetcher, never()).fetchSchemaVersionData(anyString(), anyInt());
        verify(mockConfigurationValidator, never()).validate(any(), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class));
    }

    @Test
    void handleConsulWatchUpdate_validationItselfThrowsRuntimeException_logsAndKeepsOldConfig() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);

        PipelineClusterConfig newConfigFromWatch = createSimpleTestClusterConfig("new-config-causes-validator-error");
        when(mockConfigurationValidator.validate(eq(newConfigFromWatch), any()))
                .thenThrow(new RuntimeException("Validator blew up!"));
        actualWatchCallback.accept(WatchCallbackResult.success(newConfigFromWatch));

        verify(mockConfigurationValidator).validate(eq(newConfigFromWatch), any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class));
    }

    @Test
    void handleConsulWatchUpdate_cacheUpdateThrowsRuntimeException_logsError_eventNotPublished() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);

        PipelineClusterConfig newConfigFromWatch = createSimpleTestClusterConfig("new-config-causes-cache-error");
        when(mockConfigurationValidator.validate(eq(newConfigFromWatch), any()))
                .thenReturn(ValidationResult.valid());
        doThrow(new RuntimeException("Cache update failed!"))
                .when(mockCachedConfigHolder).updateConfiguration(eq(newConfigFromWatch), any());
        actualWatchCallback.accept(WatchCallbackResult.success(newConfigFromWatch));

        verify(mockConfigurationValidator).validate(eq(newConfigFromWatch), any());
        verify(mockCachedConfigHolder).updateConfiguration(eq(newConfigFromWatch), any());
        verify(mockEventPublisher, never()).publishEvent(any(PipelineClusterConfigChangeEvent.class));
    }

    @Test
    void publishEvent_directListenerThrows_continuesNotifyingOtherListenersAndMicronautPublisher() {
        PipelineClusterConfig currentConfig = createSimpleTestClusterConfig("current");
        PipelineClusterConfig newConfig = createSimpleTestClusterConfig("new-valid-config");

        Consumer<ClusterConfigUpdateEvent> misbehavingListener = Mockito.mock(Consumer.class);
        doThrow(new RuntimeException("Listener failed!")).when(misbehavingListener).accept(any(ClusterConfigUpdateEvent.class));
        Consumer<ClusterConfigUpdateEvent> wellBehavedListener = Mockito.mock(Consumer.class);

        dynamicConfigurationManager.registerConfigUpdateListener(misbehavingListener);
        dynamicConfigurationManager.registerConfigUpdateListener(wellBehavedListener);

        // Initial load
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(currentConfig));
        when(mockConfigurationValidator.validate(eq(currentConfig), any())).thenReturn(ValidationResult.valid());
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(currentConfig));
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // This will call notifyDirectListenersOfUpdate once

        // Capture watch callback
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset mocks for the update phase
        // Reset direct listeners too for this specific test logic, so we only verify the call *after* the watch update
        Mockito.reset(mockEventPublisher, misbehavingListener, wellBehavedListener);

        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(currentConfig));
        when(mockConfigurationValidator.validate(eq(newConfig), any())).thenReturn(ValidationResult.valid());

        // Trigger update (this will be the *first* call to direct listeners *after the reset*)
        actualWatchCallback.accept(WatchCallbackResult.success(newConfig));

        ArgumentCaptor<ClusterConfigUpdateEvent> directEventCaptor = ArgumentCaptor.forClass(ClusterConfigUpdateEvent.class);

        // Verify that accept was called on misbehavingListener (it should be called once after the reset)
        verify(misbehavingListener, times(1)).accept(directEventCaptor.capture());
        ClusterConfigUpdateEvent eventForMisbehaving = directEventCaptor.getValue();
        assertEquals(Optional.of(currentConfig), eventForMisbehaving.oldConfig());
        assertEquals(newConfig, eventForMisbehaving.newConfig());


        // Verify that accept was called on wellBehavedListener (also once after the reset)
        // Use a new captor instance or reset the captor if verifying distinct calls on different mocks
        // with the same captor instance after it's already captured.
        // A new captor is safer here.
        ArgumentCaptor<ClusterConfigUpdateEvent> wellBehavedDirectEventCaptor = ArgumentCaptor.forClass(ClusterConfigUpdateEvent.class);
        verify(wellBehavedListener, times(1)).accept(wellBehavedDirectEventCaptor.capture());
        ClusterConfigUpdateEvent eventForWellBehaved = wellBehavedDirectEventCaptor.getValue();
        assertEquals(Optional.of(currentConfig), eventForWellBehaved.oldConfig());
        assertEquals(newConfig, eventForWellBehaved.newConfig());


        // Verify Micronaut event publisher
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        PipelineClusterConfigChangeEvent eventForMicronaut = eventCaptor.getValue();
        assertFalse(eventForMicronaut.isDeletion());
        assertEquals(newConfig, eventForMicronaut.newConfig());
        assertEquals(TEST_CLUSTER_NAME, eventForMicronaut.clusterName());

        dynamicConfigurationManager.unregisterConfigUpdateListener(misbehavingListener);
        dynamicConfigurationManager.unregisterConfigUpdateListener(wellBehavedListener);
    }

    @Test
    void handleConsulWatchUpdate_ambiguousWatchResult_logsAndNoAction() {
        PipelineClusterConfig oldValidConfig = createSimpleTestClusterConfig("old-valid-config");
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME)).thenReturn(Optional.of(oldValidConfig));
        when(mockConfigurationValidator.validate(eq(oldValidConfig), any())).thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> actualWatchCallback = watchCallbackCaptor.getValue();
        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator, mockConsulConfigFetcher);

        WatchCallbackResult ambiguousResult = new WatchCallbackResult(Optional.empty(), Optional.empty(), false);
        actualWatchCallback.accept(ambiguousResult);

        verifyNoInteractions(mockConfigurationValidator);
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockCachedConfigHolder, never()).clearConfiguration();
        verifyNoInteractions(mockEventPublisher);
    }

    @Test
    void initialState_beforeInitialize_isStaleAndVersionIsEmpty() {
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        assertTrue(dynamicConfigurationManager.isCurrentConfigStale(), "Should be stale if no config loaded initially");
        assertTrue(dynamicConfigurationManager.getCurrentConfigVersionIdentifier().isEmpty(), "Version should be empty if no config loaded");
    }

    @Test
    void initialize_successfulLoad_isNotStaleAndVersionIsSet() {
        PipelineClusterConfig config = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(config));
        when(mockConfigurationValidator.validate(eq(config), any()))
                .thenReturn(ValidationResult.valid());

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        assertFalse(dynamicConfigurationManager.isCurrentConfigStale(), "Should not be stale after successful load");
        Optional<String> versionOpt = dynamicConfigurationManager.getCurrentConfigVersionIdentifier();
        assertTrue(versionOpt.isPresent(), "Version should be set after successful load");
        assertFalse(versionOpt.get().isEmpty(), "Version string should not be empty");
        assertFalse(versionOpt.get().startsWith("serialization-error-"), "Version should not be a serialization error fallback");
        assertFalse(versionOpt.get().startsWith("hashing-algo-error-"), "Version should not be a hashing algo error fallback");
        assertFalse(versionOpt.get().startsWith("null-config"), "Version should not be 'null-config'");
        System.out.println("Generated version on successful load: " + versionOpt.get());
    }

    @Test
    void initialize_initialLoadFailsValidation_isStaleAndVersionIsEmpty() {
        PipelineClusterConfig config = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(config));
        when(mockConfigurationValidator.validate(eq(config), any()))
                .thenReturn(ValidationResult.invalid(List.of("Validation failed!")));

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        assertTrue(dynamicConfigurationManager.isCurrentConfigStale(), "Should be stale if initial validation fails");
        assertTrue(dynamicConfigurationManager.getCurrentConfigVersionIdentifier().isEmpty(), "Version should be empty if initial validation fails");
    }

    @Test
    void handleConsulUpdate_successfulNewConfig_isNotStaleAndVersionUpdates() {
        PipelineClusterConfig initialConfigOriginal = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        PipelineClusterConfig initialConfig = withTopics(initialConfigOriginal, Collections.singleton("topic1"));
        String initialExpectedVersion = calculateExpectedVersion(initialConfig);

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(initialConfig));
        when(mockConfigurationValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        assertFalse(dynamicConfigurationManager.isCurrentConfigStale());
        assertEquals(Optional.of(initialExpectedVersion), dynamicConfigurationManager.getCurrentConfigVersionIdentifier());

        PipelineClusterConfig newConfigOriginal = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        PipelineClusterConfig newConfig = withTopics(newConfigOriginal, Collections.singleton("topic2"));
        String newExpectedVersion = calculateExpectedVersion(newConfig);

        // Ensure schema fetching is stubbed if newConfig has modules with schemas
        // For this test, assuming newConfig is simple or its schemas are handled by existing general stubs if any.
        // If newConfig has specific schemas, they need to be stubbed for mockConsulConfigFetcher.fetchSchemaVersionData

        when(mockConfigurationValidator.validate(eq(newConfig), any()))
                .thenReturn(ValidationResult.valid());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> callback = watchCallbackCaptor.getValue();
        callback.accept(WatchCallbackResult.success(newConfig));

        assertFalse(dynamicConfigurationManager.isCurrentConfigStale(), "Should not be stale after successful watch update");
        assertEquals(Optional.of(newExpectedVersion), dynamicConfigurationManager.getCurrentConfigVersionIdentifier(), "Version should update");
    }

    private String calculateExpectedVersion(PipelineClusterConfig config) {
        try {
            // Use the instantiated realObjectMapper field
            String jsonConfig = realObjectMapper.writeValueAsString(config);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(jsonConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate expected version for test", e);
        }
    }

    @Test
    void handleConsulUpdate_newConfigFailsValidation_becomesStaleAndKeepsOldVersion() {
        PipelineClusterConfig initialConfigOriginal = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        PipelineClusterConfig initialConfig = withTopics(initialConfigOriginal, Collections.singleton("topicInitial"));
        String initialExpectedVersion = calculateExpectedVersion(initialConfig);

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(initialConfig));
        when(mockConfigurationValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        PipelineClusterConfig invalidNewConfigOriginal = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        PipelineClusterConfig invalidNewConfig = withTopics(invalidNewConfigOriginal, Collections.singleton("topicInvalid"));

        when(mockConfigurationValidator.validate(eq(invalidNewConfig), any()))
                .thenReturn(ValidationResult.invalid(List.of("New config invalid!")));
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> callback = watchCallbackCaptor.getValue();
        callback.accept(WatchCallbackResult.success(invalidNewConfig));

        assertTrue(dynamicConfigurationManager.isCurrentConfigStale(), "Should become stale if new config from watch is invalid");
        assertEquals(Optional.of(initialExpectedVersion), dynamicConfigurationManager.getCurrentConfigVersionIdentifier(), "Version should remain the old valid one");
    }

    @Test
    void handleConsulUpdate_deletionEvent_isStaleAndVersionIsEmpty() {
        PipelineClusterConfig initialConfig = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(initialConfig));
        when(mockConfigurationValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(initialConfig)); // For wasPresent check
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> callback = watchCallbackCaptor.getValue();
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(initialConfig)); // For wasPresent check before clear in SUT

        callback.accept(WatchCallbackResult.createAsDeleted());

        assertTrue(dynamicConfigurationManager.isCurrentConfigStale(), "Should be stale after deletion event");
        assertTrue(dynamicConfigurationManager.getCurrentConfigVersionIdentifier().isEmpty(), "Version should be empty after deletion event");
    }

    @Test
    void handleConsulUpdate_errorEvent_isStaleAndKeepsOldVersion() {
        PipelineClusterConfig initialConfigOriginal = createSimpleTestClusterConfig(TEST_CLUSTER_NAME);
        PipelineClusterConfig initialConfig = withTopics(initialConfigOriginal, Collections.singleton("topicInitialForError"));
        String initialExpectedVersion = calculateExpectedVersion(initialConfig);

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(initialConfig));
        when(mockConfigurationValidator.validate(eq(initialConfig), any()))
                .thenReturn(ValidationResult.valid());
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<WatchCallbackResult> callback = watchCallbackCaptor.getValue();
        callback.accept(WatchCallbackResult.failure(new RuntimeException("Consul watch error!")));

        assertTrue(dynamicConfigurationManager.isCurrentConfigStale(), "Should be stale after watch error event");
        assertEquals(Optional.of(initialExpectedVersion), dynamicConfigurationManager.getCurrentConfigVersionIdentifier(), "Version should remain the old valid one after watch error");
    }
}
