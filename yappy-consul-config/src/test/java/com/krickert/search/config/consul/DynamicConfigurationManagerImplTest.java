package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.registry.model.SchemaCompatibility; // explicit import
import com.krickert.search.config.schema.registry.model.SchemaType;      // explicit import
import com.krickert.search.config.schema.registry.model.SchemaVersionData;


import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
// InjectMocks is not strictly needed if we manually construct, but good practice if we were injecting more complex scenarios.
// For now, manual construction as done is fine.
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentMatchers;

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

    @Captor
    private ArgumentCaptor<Consumer<Optional<PipelineClusterConfig>>> watchCallbackCaptor;
    @Captor
    private ArgumentCaptor<ClusterConfigUpdateEvent> eventCaptor;
    @Captor
    private ArgumentCaptor<Map<SchemaReference, String>> schemaCacheCaptor;


    private DynamicConfigurationManagerImpl dynamicConfigurationManager;

    @BeforeEach
    void setUp() {
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
        // ... (Arrange as before) ...
        new PipelineClusterConfig(
                TEST_CLUSTER_NAME, null, new PipelineModuleMap(Collections.emptyMap()),
                Collections.emptySet(), Collections.emptySet()
        );
        PipelineClusterConfig mockClusterConfig;
        // For this specific test, let's assume it has one module requiring one schema for clarity
        SchemaReference schemaRef1 = new SchemaReference("moduleA-schema", 1);
        PipelineModuleConfiguration moduleAConfig = new PipelineModuleConfiguration("ModuleA", "moduleA_impl_id", schemaRef1);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("moduleA_impl_id", moduleAConfig));
        mockClusterConfig = new PipelineClusterConfig( // Re-assign with moduleMap
                TEST_CLUSTER_NAME, null, moduleMap, Collections.emptySet(), Collections.emptySet()
        );
        SchemaVersionData schemaVersionData1 = new SchemaVersionData(
                1L, schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "Test Schema"
        );

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()))
                .thenReturn(Optional.of(schemaVersionData1));
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), ArgumentMatchers.any()))
                .thenReturn(ValidationResult.valid());


        // --- Act ---
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        // --- Assert ---
        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()); // Verify schema fetch
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), ArgumentMatchers.any()); // Verify validation

        verify(mockCachedConfigHolder).updateConfiguration(eq(mockClusterConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionData1.schemaContent(), capturedSchemaMap.get(schemaRef1));

        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
        assertTrue(publishedEvent.oldConfig().isEmpty());
        assertEquals(mockClusterConfig, publishedEvent.newConfig());

        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), ArgumentMatchers.any());
    }

    @Test
    void initialize_consulReturnsEmptyConfig_logsWarningAndStartsWatch() {
        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.empty());

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        verify(mockConfigurationValidator, never()).validate(any(), any()); // Correct: no validation if no config
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockEventPublisher, never()).publishEvent(any());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), ArgumentMatchers.any());
    }

    @Test
    void initialize_validationFails_doesNotUpdateCacheOrPublishEventAndStartsWatch() {
        PipelineClusterConfig mockClusterConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME);

        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
                .thenReturn(Optional.of(mockClusterConfig));
        // Even if it's an empty config, schema fetching for its (empty) module map might occur.
        // For this test, assuming no modules, so no schema fetches.
        when(mockConfigurationValidator.validate(eq(mockClusterConfig), ArgumentMatchers.any()))
                .thenReturn(ValidationResult.invalid(List.of("Test validation error")));

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        verify(mockConsulConfigFetcher).connect();
        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
        // If mockClusterConfig had modules, schema fetches would be verified here.
        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), ArgumentMatchers.any());
        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), any());
        verify(mockEventPublisher, never()).publishEvent(any());
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), ArgumentMatchers.any());
    }

    // --- Tests for handleConsulClusterConfigUpdate ---

    @Test
    void handleConsulClusterConfigUpdate_successfulUpdate_validatesAndCachesAndPublishes() {
        // --- Arrange ---
        PipelineClusterConfig oldMockConfig = new PipelineClusterConfig("old-cluster-config-name");
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));

        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
        PipelineClusterConfig newWatchedConfig = new PipelineClusterConfig(
                TEST_CLUSTER_NAME, null, moduleMapNew, Collections.emptySet(), Collections.emptySet()
        );
        SchemaVersionData schemaVersionDataNew = new SchemaVersionData(
                2L, schemaRefNew.subject(), schemaRefNew.version(), "{\"type\":\"string\"}",
                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "New Test Schema"
        );

        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
                .thenReturn(Optional.of(schemaVersionDataNew));
        when(mockConfigurationValidator.validate(eq(newWatchedConfig), ArgumentMatchers.any()))
                .thenReturn(ValidationResult.valid());

        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME); // Sets up the watch via mock
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();

        // --- Act ---
        actualWatchCallback.accept(Optional.of(newWatchedConfig));

        // --- Assert ---
        verify(mockConfigurationValidator).validate(eq(newWatchedConfig), ArgumentMatchers.any());
        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());

        // Use the SAME captor instance (it should have been reset or this is a new capture for this interaction)
        // If updateConfiguration is called multiple times in a test method flow with the same captor,
        // getValue() gets the last captured value. This is fine here.
        verify(mockCachedConfigHolder).updateConfiguration(eq(newWatchedConfig), schemaCacheCaptor.capture());
        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
        assertEquals(1, capturedSchemaMap.size());
        assertEquals(schemaVersionDataNew.schemaContent(), capturedSchemaMap.get(schemaRefNew));

        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
        assertEquals(newWatchedConfig, publishedEvent.newConfig());
    }

    @Test
    void handleConsulClusterConfigUpdate_configDeleted_clearsCacheAndPublishes() {
        PipelineClusterConfig oldMockConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME);
        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));

        // Simulate that initialize() has already run and set up the watch
        // For this specific test, we don't need to mock the full init sequence if we directly test the handler
        // However, to get the watchCallbackCaptor populated, initialize() needs to run.
        // To ensure no interference from initialize's call to validate, we can reset the mock after initialize
        // if we are *only* interested in validate calls triggered by the watch callback.

        // Run initialize to setup the watcher and capture the callback
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();

        // Reset validator if you want to count validate calls *only* from the watch handler in this test.
        // This is optional and depends on how granular you want your verification.
        // reset(mockConfigurationValidator); // Optional: Reset after initialize

        // --- Act ---
        actualWatchCallback.accept(Optional.empty()); // Simulate Consul firing watch with key deleted

        // --- Assert ---
        verify(mockCachedConfigHolder).clearConfiguration();
        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
        assertNotNull(publishedEvent.newConfig());
        assertEquals(TEST_CLUSTER_NAME, publishedEvent.newConfig().clusterName());
        // Check that newConfig is effectively empty
        assertTrue(publishedEvent.newConfig().pipelineModuleMap() == null ||
                (publishedEvent.newConfig().pipelineModuleMap().availableModules() != null &&
                        publishedEvent.newConfig().pipelineModuleMap().availableModules().isEmpty()));
        assertTrue(publishedEvent.newConfig().pipelineGraphConfig() == null ||
                (publishedEvent.newConfig().pipelineGraphConfig().pipelines() != null &&
                        publishedEvent.newConfig().pipelineGraphConfig().pipelines().isEmpty()));


        // Ensure validate is NOT called for a *new* config when the config is deleted.
        // If initialize() called validate, that's fine. We're checking no *additional* validate call.
        // If reset(mockConfigurationValidator) was used after initialize:
        // verify(mockConfigurationValidator, never()).validate(any(), any());
        // If not reset, and initialize() made one call due to mocked initial config:
        // This verification depends on whether initialize() was mocked to have a config to validate.
        // For simplicity, let's assume initialize might have called it. The key is no *new* validation
        // attempt for the "deleted" state.
        // A more robust way is to verify specific calls:
        // verify(mockConfigurationValidator, times(X)).validate(configFromInit, any()); // X is 0 or 1
        // verify(mockConfigurationValidator, never()).validate(isNull(), any()); // Or similar for deleted state
    }



    // TODO: Add more tests for handleConsulClusterConfigUpdate:
    // - Update leads to validation failure (new config comes, validator returns invalid)

}
