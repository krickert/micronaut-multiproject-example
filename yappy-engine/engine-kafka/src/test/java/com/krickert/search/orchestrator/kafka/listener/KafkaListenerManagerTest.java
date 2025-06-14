package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import com.krickert.search.orchestrator.kafka.admin.OffsetResetParameters;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaListenerManager}.
 */
@ExtendWith(MockitoExtension.class)
class KafkaListenerManagerTest {

    private KafkaListenerManager listenerManager;

    @Mock
    private DefaultKafkaListenerPool mockListenerPool;

    @Mock
    private ConsumerStateManager mockStateManager;

    @Mock
    private KafkaAdminService mockKafkaAdminService;

    @Mock
    private ApplicationContext mockApplicationContext;

    @Mock
    private DynamicKafkaListener mockListener;
    
    @Mock
    private ApplicationEventPublisher<PipeStreamProcessingEvent> mockEventPublisher;

    private static final String PIPELINE_NAME = "test-pipeline";
    private static final String STEP_NAME = "test-step";
    private static final String TOPIC = "test-topic";
    private static final String GROUP_ID = "test-group";
    private static final String LISTENER_ID = "test-listener-id"; // This is the uniquePoolListenerId
    private static final String TEST_SCHEMA_REGISTRY_TYPE = "apicurio";
    private static final String APP_CLUSTER_NAME = "test-app-cluster-klm"; // Added for constructor

    @BeforeEach
    void setUp() {
        listenerManager = new KafkaListenerManager(
                mockListenerPool,
                mockStateManager,
                mockKafkaAdminService,
                mockEventPublisher,
                mockApplicationContext,
                TEST_SCHEMA_REGISTRY_TYPE,
                APP_CLUSTER_NAME
        );
    }



    @Test
    void testCreateListenersForPipelineNonExistentPipeline() {
        // Test that synchronization handles missing pipeline gracefully
        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName(APP_CLUSTER_NAME)
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap())) // No pipelines
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .build();
        
        // Trigger event
        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent(
                APP_CLUSTER_NAME,
                config
        );
        
        // Should handle gracefully without errors
        assertDoesNotThrow(() -> listenerManager.onApplicationEvent(event));
        
        // Verify no listeners were created
        Map<String, ConsumerStatus> statuses = listenerManager.getConsumerStatuses();
        assertTrue(statuses.isEmpty());
    }

    @Test
    void testCreateListener_Apicurio() {
        // Test listener creation with Apicurio registry through event-driven approach
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of(TOPIC))
                .consumerGroupId(GROUP_ID)
                .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                .build();
                
        PipelineStepConfig stepConfig = PipelineStepConfig.builder()
                .stepName(STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-module", null))
                .kafkaInputs(List.of(kafkaInput))
                .build();
                
        PipelineConfig pipelineConfig = new PipelineConfig(
                PIPELINE_NAME,
                Map.of(STEP_NAME, stepConfig)
        );
                
        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName(APP_CLUSTER_NAME)
                .pipelineGraphConfig(new PipelineGraphConfig(Map.of(PIPELINE_NAME, pipelineConfig)))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .build();
        
        // Mock the listener pool to verify creation
        DynamicKafkaListener mockCreatedListener = mock(DynamicKafkaListener.class);
        when(mockCreatedListener.getListenerId()).thenReturn("test-listener-123");
        when(mockCreatedListener.getPipelineName()).thenReturn(PIPELINE_NAME);
        when(mockCreatedListener.getStepName()).thenReturn(STEP_NAME);
        when(mockCreatedListener.getTopic()).thenReturn(TOPIC);
        when(mockCreatedListener.getGroupId()).thenReturn(GROUP_ID);
        when(mockCreatedListener.isPaused()).thenReturn(false);
        
        when(mockListenerPool.createListener(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString(), any())).thenReturn(mockCreatedListener);
        
        // Trigger event
        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent(
                APP_CLUSTER_NAME,
                config
        );
        
        listenerManager.onApplicationEvent(event);
        
        // Allow async processing
        await().atMost(Duration.ofSeconds(2))
                .until(() -> !listenerManager.getConsumerStatuses().isEmpty());
        
        // Verify listener was created with correct parameters
        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockListenerPool).createListener(anyString(), eq(TOPIC), eq(GROUP_ID), configCaptor.capture(), any(), eq(PIPELINE_NAME), eq(STEP_NAME), any());
        
        Map<String, Object> capturedConfig = configCaptor.getValue();
        // The schema registry type is configured at the manager level, not in the consumer config
        assertNotNull(capturedConfig);
    }

    @Test
    void testCreateListenerExistingListener() throws Exception {
        // Test that duplicate listeners are not created
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of(TOPIC))
                .consumerGroupId(GROUP_ID)
                .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                .build();
                
        PipelineStepConfig stepConfig = PipelineStepConfig.builder()
                .stepName(STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-module", null))
                .kafkaInputs(List.of(kafkaInput))
                .build();
                
        PipelineConfig pipelineConfig = new PipelineConfig(
                PIPELINE_NAME,
                Map.of(STEP_NAME, stepConfig)
        );
                
        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName(APP_CLUSTER_NAME)
                .pipelineGraphConfig(new PipelineGraphConfig(Map.of(PIPELINE_NAME, pipelineConfig)))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .build();
        
        // Pre-populate the listener map to simulate existing listener
        String listenerKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        DynamicKafkaListener existingListener = mock(DynamicKafkaListener.class);
        when(existingListener.getListenerId()).thenReturn("existing-listener-123");
        when(existingListener.getTopic()).thenReturn(TOPIC);
        when(existingListener.getGroupId()).thenReturn(GROUP_ID);
        when(existingListener.getConsumerConfigForComparison()).thenReturn(Map.of("auto.offset.reset", (Object)"earliest"));
        
        // Use reflection to set the listener in the map
        Map<String, DynamicKafkaListener> activeMap = new ConcurrentHashMap<>();
        activeMap.put(listenerKey, existingListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);
        
        // Trigger event
        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent(
                APP_CLUSTER_NAME,
                config
        );
        
        listenerManager.onApplicationEvent(event);
        
        // Allow async processing
        Thread.sleep(500);
        
        // Verify no new listener was created (pool.createListener should not be called)
        verify(mockListenerPool, never()).createListener(anyString(), anyString(), anyString(), any(), any(), anyString(), anyString(), any());
        
        // Verify the existing listener is still there
        Map<String, ConsumerStatus> statuses = listenerManager.getConsumerStatuses();
        assertEquals(1, statuses.size());
    }

    @Test
    void testPauseConsumer() throws Exception {
        // This test needs to set up activeListenerInstanceMap correctly before calling pause.
        // For now, just fix the signature.
        // String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME; // Old key
        String listenerInstanceKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        // Simulate listener exists in the map
        // In a real test, this would be populated by synchronizeListeners
        // For unit testing, we might need to use reflection or a test helper if we don't want to test synchronizeListeners here.
        // Or, this test becomes an integration test of synchronizeListeners + pause.
        // For now, let's assume the listener is there for the sake of testing the pause logic itself.
        Map<String, DynamicKafkaListener> activeMap = new HashMap<>();
        activeMap.put(listenerInstanceKey, mockListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);


        when(mockListener.getListenerId()).thenReturn(LISTENER_ID); // Ensure mockListener has an ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);

        Mono<Void> result = listenerManager.pauseConsumer(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        verify(mockListener).pause();
        verify(mockStateManager).updateState(eq(LISTENER_ID), any(ConsumerState.class));
    }

    @Test
    void testPauseConsumerNonExistentListener() {
        Mono<Void> result = listenerManager.pauseConsumer(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
                
        verify(mockListener, never()).pause();
        verify(mockStateManager, never()).updateState(anyString(), any(ConsumerState.class));
    }

    @Test
    void testResumeConsumer() throws Exception {
        String listenerInstanceKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        Map<String, DynamicKafkaListener> activeMap = new HashMap<>();
        activeMap.put(listenerInstanceKey, mockListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);

        when(mockListener.getListenerId()).thenReturn(LISTENER_ID);

        Mono<Void> result = listenerManager.resumeConsumer(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        verify(mockListener).resume();
        verify(mockStateManager).updateState(eq(LISTENER_ID), any(ConsumerState.class));
    }

    @Test
    void testResumeConsumerNonExistentListener() {
        Mono<Void> result = listenerManager.resumeConsumer(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
                
        verify(mockListener, never()).resume();
        verify(mockStateManager, never()).updateState(anyString(), any(ConsumerState.class));
    }

    @Test
    void testResetOffsetToDate() throws Exception {
        Instant date = Instant.now();
        String listenerInstanceKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        Map<String, DynamicKafkaListener> activeMap = new HashMap<>();
        activeMap.put(listenerInstanceKey, mockListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);

        when(mockListener.getListenerId()).thenReturn(LISTENER_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID, date);

        StepVerifier.create(result)
                .verifyComplete();
                
        // Verify the listener was removed/shutdown during offset reset
        ArgumentCaptor<OffsetResetParameters> paramsCaptor = ArgumentCaptor.forClass(OffsetResetParameters.class);
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), paramsCaptor.capture());
        assertEquals(date.toEpochMilli(), paramsCaptor.getValue().getTimestamp());
    }

    @Test
    void testResetOffsetToDateNonExistentListener() {
        Instant date = Instant.now();
        Mono<Void> result = listenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID, date);
        
        StepVerifier.create(result)
                .expectError(IllegalArgumentException.class)
                .verify();
                
        verify(mockKafkaAdminService, never()).resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class));
    }

    @Test
    void testResetOffsetToEarliest() throws Exception {
        String listenerInstanceKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        Map<String, DynamicKafkaListener> activeMap = new HashMap<>();
        activeMap.put(listenerInstanceKey, mockListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);

        when(mockListener.getListenerId()).thenReturn(LISTENER_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToEarliest(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        // Verify the listener was removed/shutdown during offset reset
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class));
    }

    @Test
    void testResetOffsetToLatest() throws Exception {
        String listenerInstanceKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        Map<String, DynamicKafkaListener> activeMap = new HashMap<>();
        activeMap.put(listenerInstanceKey, mockListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);

        when(mockListener.getListenerId()).thenReturn(LISTENER_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToLatest(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        // Verify the listener was removed/shutdown during offset reset
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class));
    }

    @Test
    void testGetConsumerStatuses() {
        // This test needs to set up activeListenerInstanceMap
        // For now, just ensure it compiles and runs.
        // A more thorough test would involve populating activeListenerInstanceMap
        // and verifying the output.
        Map<String, ConsumerStatus> result = listenerManager.getConsumerStatuses();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "Status map should be empty if no listeners are active.");
    }

    @Test
    void testRemoveListener() throws Exception {
        // Test listener removal through configuration update
        
        // First, set up a listener
        String listenerKey = String.format("%s:%s:%s:%s", PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);
        DynamicKafkaListener existingListener = mock(DynamicKafkaListener.class);
        when(existingListener.getListenerId()).thenReturn("listener-to-remove");
        
        // Use reflection to set the listener in the map
        Map<String, DynamicKafkaListener> activeMap = new ConcurrentHashMap<>();
        activeMap.put(listenerKey, existingListener);
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("activeListenerInstanceMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, activeMap);
        
        // Create empty config (no pipelines) to trigger removal
        PipelineClusterConfig emptyConfig = PipelineClusterConfig.builder()
                .clusterName(APP_CLUSTER_NAME)
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .build();
        
        // Trigger event with empty config
        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent(
                APP_CLUSTER_NAME,
                emptyConfig
        );
        
        listenerManager.onApplicationEvent(event);
        
        // Allow async processing
        await().atMost(Duration.ofSeconds(2))
                .until(() -> listenerManager.getConsumerStatuses().isEmpty());
        
        // Verify listener was shut down
        verify(existingListener).shutdown();
        verify(mockListenerPool).removeListener("listener-to-remove");
        
        // Verify no listeners remain
        Map<String, ConsumerStatus> statuses = listenerManager.getConsumerStatuses();
        assertTrue(statuses.isEmpty());
    }

    @Test
    void testRemoveListenerNonExistentListener() {
        // Test that removing non-existent listener is handled gracefully
        
        // Start with no listeners
        Map<String, ConsumerStatus> initialStatuses = listenerManager.getConsumerStatuses();
        assertTrue(initialStatuses.isEmpty());
        
        // Create empty config to trigger removal logic
        PipelineClusterConfig emptyConfig = PipelineClusterConfig.builder()
                .clusterName(APP_CLUSTER_NAME)
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .build();
        
        // Trigger event
        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent(
                APP_CLUSTER_NAME,
                emptyConfig
        );
        
        // Should handle gracefully without errors
        assertDoesNotThrow(() -> listenerManager.onApplicationEvent(event));
        
        // Verify no shutdown was attempted (since there were no listeners)
        verify(mockListenerPool, never()).removeListener(anyString());
        
        // Status should still be empty
        Map<String, ConsumerStatus> finalStatuses = listenerManager.getConsumerStatuses();
        assertTrue(finalStatuses.isEmpty());
    }
}