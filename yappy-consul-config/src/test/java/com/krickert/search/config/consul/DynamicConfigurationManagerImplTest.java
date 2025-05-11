//package com.krickert.search.config.consul;
//
//import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
//import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
//import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
//import com.krickert.search.config.pipeline.model.PipelineModuleMap;
//import com.krickert.search.config.pipeline.model.SchemaReference;
//import com.krickert.search.config.schema.registry.model.SchemaCompatibility;
//import com.krickert.search.config.schema.registry.model.SchemaType;
//import com.krickert.search.config.schema.registry.model.SchemaVersionData;
//
//import io.micronaut.context.event.ApplicationEventPublisher;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Captor;
//import org.mockito.Mock;
//import org.mockito.Mockito;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.time.Instant;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.function.Consumer;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.eq;
//import static org.mockito.ArgumentMatchers.any; // This is the key!
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//class DynamicConfigurationManagerImplTest {
//
//    private static final String TEST_CLUSTER_NAME = "test-cluster";
//
//    @Mock
//    private ConsulConfigFetcher mockConsulConfigFetcher;
//    @Mock
//    private ConfigurationValidator mockConfigurationValidator;
//    @Mock
//    private CachedConfigHolder mockCachedConfigHolder;
//    @Mock
//    private ApplicationEventPublisher<ClusterConfigUpdateEvent> mockEventPublisher;
//
//    @Captor
//    private ArgumentCaptor<Consumer<Optional<PipelineClusterConfig>>> watchCallbackCaptor;
//    @Captor
//    private ArgumentCaptor<ClusterConfigUpdateEvent> eventCaptor;
//    @Captor
//    private ArgumentCaptor<Map<SchemaReference, String>> schemaCacheCaptor;
//
//    private DynamicConfigurationManagerImpl dynamicConfigurationManager;
//
//    @BeforeEach
//    void setUp() {
//        dynamicConfigurationManager = new DynamicConfigurationManagerImpl(
//                TEST_CLUSTER_NAME,
//                mockConsulConfigFetcher,
//                mockConfigurationValidator,
//                mockCachedConfigHolder,
//                mockEventPublisher
//        );
//    }
//
//    @Test
//    void initialize_successfulInitialLoad_validatesAndCachesConfigAndStartsWatch() {
//        SchemaReference schemaRef1 = new SchemaReference("moduleA-schema", 1);
//        PipelineModuleConfiguration moduleAConfig = new PipelineModuleConfiguration("ModuleA", "moduleA_impl_id", schemaRef1);
//        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("moduleA_impl_id", moduleAConfig));
//        PipelineClusterConfig mockClusterConfig = new PipelineClusterConfig(
//                TEST_CLUSTER_NAME, null, moduleMap, Collections.emptySet(), Collections.emptySet()
//        );
//        SchemaVersionData schemaVersionData1 = new SchemaVersionData(
//                1L, schemaRef1.subject(), schemaRef1.version(), "{\"type\":\"object\"}",
//                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "Test Schema"
//        );
//
//        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
//                .thenReturn(Optional.of(mockClusterConfig));
//        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version()))
//                .thenReturn(Optional.of(schemaVersionData1));
//        // Use any() for the Function generic type
//        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any()))
//                .thenReturn(ValidationResult.valid());
//
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//
//        verify(mockConsulConfigFetcher).connect();
//        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRef1.subject(), schemaRef1.version());
//        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any());
//        verify(mockCachedConfigHolder).updateConfiguration(eq(mockClusterConfig), schemaCacheCaptor.capture());
//        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
//        assertEquals(1, capturedSchemaMap.size());
//        assertEquals(schemaVersionData1.schemaContent(), capturedSchemaMap.get(schemaRef1));
//        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
//        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
//        assertTrue(publishedEvent.oldConfig().isEmpty());
//        assertEquals(mockClusterConfig, publishedEvent.newConfig());
//        // Using ArgumentMatchers.any() for the Consumer generic type
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
//    }
//
//    @Test
//    void initialize_consulReturnsEmptyConfig_logsWarningAndStartsWatch() {
//        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
//                .thenReturn(Optional.empty());
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).connect();
//        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
//        verify(mockConfigurationValidator, never()).validate(any(), any());
//        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
//        verify(mockEventPublisher, never()).publishEvent(any());
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
//    }
//
//    @Test
//    void initialize_initialValidationFails_doesNotUpdateCacheOrPublishEventAndStartsWatch() {
//        PipelineClusterConfig mockClusterConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME);
//        when(mockConsulConfigFetcher.fetchPipelineClusterConfig(TEST_CLUSTER_NAME))
//                .thenReturn(Optional.of(mockClusterConfig));
//        when(mockConfigurationValidator.validate(eq(mockClusterConfig), any()))
//                .thenReturn(ValidationResult.invalid(List.of("Initial Test validation error")));
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).connect();
//        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
//        verify(mockConfigurationValidator).validate(eq(mockClusterConfig), any());
//        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
//        verify(mockEventPublisher, never()).publishEvent(any());
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
//    }
//
//    @Test
//    void handleConsulClusterConfigUpdate_successfulUpdate_validatesAndCachesAndPublishes() {
//        PipelineClusterConfig oldMockConfig = new PipelineClusterConfig("old-cluster-config-name");
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));
//        // ... (setup newWatchedConfig, schemaVersionDataNew)
//        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
//        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
//        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
//        PipelineClusterConfig newWatchedConfig = new PipelineClusterConfig(
//                TEST_CLUSTER_NAME, null, moduleMapNew, Collections.emptySet(), Collections.emptySet()
//        );
//        SchemaVersionData schemaVersionDataNew = new SchemaVersionData(
//                2L, schemaRefNew.subject(), schemaRefNew.version(), "{\"type\":\"string\"}",
//                SchemaType.JSON_SCHEMA, SchemaCompatibility.NONE, Instant.now(), "New Test Schema"
//        );
//
//
//        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
//                .thenReturn(Optional.of(schemaVersionDataNew));
//        when(mockConfigurationValidator.validate(eq(newWatchedConfig), any()))
//                .thenReturn(ValidationResult.valid());
//
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
//        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();
//        actualWatchCallback.accept(Optional.of(newWatchedConfig));
//
//        verify(mockConfigurationValidator).validate(eq(newWatchedConfig), any());
//        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
//        verify(mockCachedConfigHolder).updateConfiguration(eq(newWatchedConfig), schemaCacheCaptor.capture());
//        Map<SchemaReference, String> capturedSchemaMap = schemaCacheCaptor.getValue();
//        assertEquals(1, capturedSchemaMap.size());
//        assertEquals(schemaVersionDataNew.schemaContent(), capturedSchemaMap.get(schemaRefNew));
//        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
//        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
//        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
//        assertEquals(newWatchedConfig, publishedEvent.newConfig());
//        verify(mockEventPublisher).publishEvent(any(ClusterConfigUpdateEvent.class));
//    }
//
//    @Test
//    void handleConsulClusterConfigUpdate_configDeleted_clearsCacheAndPublishes() {
//        PipelineClusterConfig oldMockConfig = new PipelineClusterConfig(TEST_CLUSTER_NAME);
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldMockConfig));
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
//        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();
//        actualWatchCallback.accept(Optional.empty());
//        verify(mockCachedConfigHolder).clearConfiguration();
//        verify(mockEventPublisher).publishEvent(eventCaptor.capture());
//        ClusterConfigUpdateEvent publishedEvent = eventCaptor.getValue();
//        assertEquals(Optional.of(oldMockConfig), publishedEvent.oldConfig());
//        assertNotNull(publishedEvent.newConfig());
//        assertEquals(TEST_CLUSTER_NAME, publishedEvent.newConfig().clusterName());
//        assertTrue(publishedEvent.newConfig().pipelineModuleMap() == null ||
//                (publishedEvent.newConfig().pipelineModuleMap().availableModules() != null &&
//                        publishedEvent.newConfig().pipelineModuleMap().availableModules().isEmpty()));
//    }
//
//    @Test
//    void handleConsulClusterConfigUpdate_validationFails_keepsOldConfigAndDoesNotPublishSuccessEvent() {
//        PipelineClusterConfig oldValidConfig = new PipelineClusterConfig("old-valid-config");
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));
//        PipelineClusterConfig newInvalidConfigFromWatch = new PipelineClusterConfig("new-invalid-config");
//
//
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
//        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();
//
//        Mockito.reset(mockEventPublisher, mockCachedConfigHolder);
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));
//
//        when(mockConfigurationValidator.validate(eq(newInvalidConfigFromWatch), any())) // CHANGED HERE
//                .thenReturn(ValidationResult.invalid(List.of("Watch update validation error")));
//
//        actualWatchCallback.accept(Optional.of(newInvalidConfigFromWatch));
//
//        verify(mockConfigurationValidator).validate(eq(newInvalidConfigFromWatch), any()); // CHANGED HERE
//        verify(mockCachedConfigHolder, never()).updateConfiguration(eq(newInvalidConfigFromWatch), anyMap()); // CHANGED HERE
//        verify(mockCachedConfigHolder, never()).clearConfiguration();
//        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
//    }
//
//    @Test
//    void initialize_fetchPipelineClusterConfigThrowsException_handlesGracefullyAndStillStartsWatch() {
//        doThrow(new RuntimeException("Consul connection totally failed during fetch!"))
//                .when(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
//        doNothing().when(mockConsulConfigFetcher).connect();
//
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//
//        verify(mockConsulConfigFetcher).connect();
//        verify(mockConsulConfigFetcher).fetchPipelineClusterConfig(TEST_CLUSTER_NAME);
//        verify(mockConfigurationValidator, never()).validate(any(), any());
//        verify(mockCachedConfigHolder, never()).updateConfiguration(any(), anyMap());
//        verify(mockEventPublisher, never()).publishEvent(any());
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), any());
//    }
//
//    @Test
//    void handleConsulClusterConfigUpdate_fetchSchemaThrowsException_handlesGracefullyKeepsOldConfig() {
//        PipelineClusterConfig oldValidConfig = new PipelineClusterConfig("old-valid-config");
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));
//        SchemaReference schemaRefNew = new SchemaReference("moduleNew-schema", 1);
//        PipelineModuleConfiguration moduleNewConfig = new PipelineModuleConfiguration("ModuleNew", "moduleNew_impl_id", schemaRefNew);
//        PipelineModuleMap moduleMapNew = new PipelineModuleMap(Map.of("moduleNew_impl_id", moduleNewConfig));
//        PipelineClusterConfig newWatchedConfig = new PipelineClusterConfig(
//                TEST_CLUSTER_NAME, null, moduleMapNew, Collections.emptySet(), Collections.emptySet()
//        );
//
//
//        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);
//        verify(mockConsulConfigFetcher).watchClusterConfig(eq(TEST_CLUSTER_NAME), watchCallbackCaptor.capture());
//        Consumer<Optional<PipelineClusterConfig>> actualWatchCallback = watchCallbackCaptor.getValue();
//
//        Mockito.reset(mockEventPublisher, mockCachedConfigHolder, mockConfigurationValidator);
//        when(mockCachedConfigHolder.getCurrentConfig()).thenReturn(Optional.of(oldValidConfig));
//
//        when(mockConsulConfigFetcher.fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version()))
//                .thenThrow(new RuntimeException("Failed to fetch schema from Consul!"));
//
//        actualWatchCallback.accept(Optional.of(newWatchedConfig));
//
//        verify(mockConsulConfigFetcher).fetchSchemaVersionData(schemaRefNew.subject(), schemaRefNew.version());
//        verify(mockConfigurationValidator, never()).validate(eq(newWatchedConfig), any()); // CHANGED HERE
//        verify(mockCachedConfigHolder, never()).clearConfiguration();
//        verify(mockEventPublisher, never()).publishEvent(any(ClusterConfigUpdateEvent.class));
//    }
//}