package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    @Disabled("Refactor: createListenersForPipeline was removed.")
    void testCreateListenersForPipelineNonExistentPipeline() {
        fail("Test needs refactoring for event-driven listener creation.");
    }

    @Test
    @Disabled("Refactor: public createListener was removed; test private createAndRegisterListenerInstance via synchronizeListeners.")
    void testCreateListener_Apicurio() {
        fail("Test needs refactoring for event-driven listener creation and private method testing.");
    }

    @Test
    @Disabled("Refactor: public createListener was removed and internal map changed.")
    void testCreateListenerExistingListener() throws Exception {
        fail("Test needs refactoring for event-driven listener creation and internal map changes.");
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
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);

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
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID, date);

        StepVerifier.create(result)
                .verifyComplete();
                
        verify(mockListener).pause();
        ArgumentCaptor<OffsetResetParameters> paramsCaptor = ArgumentCaptor.forClass(OffsetResetParameters.class);
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), paramsCaptor.capture());
        assertEquals(date.toEpochMilli(), paramsCaptor.getValue().getTimestamp());
        verify(mockListener).resume();
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
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToEarliest(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        verify(mockListener).pause();
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class));
        verify(mockListener).resume();
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
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(anyString(), anyString(), any(OffsetResetParameters.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        Mono<Void> result = listenerManager.resetOffsetToLatest(PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID);

        StepVerifier.create(result)
                .verifyComplete();
                
        verify(mockListener).pause();
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class));
        verify(mockListener).resume();
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
    @Disabled("Refactor: public removeListener(pipeline,step) was removed; test event-driven removal via synchronizeListeners")
    void testRemoveListener() throws Exception {
        fail("Test needs refactoring for event-driven listener removal.");
    }

    @Test
    @Disabled("Refactor: public removeListener(pipeline,step) was removed.")
    void testRemoveListenerNonExistentListener() {
        fail("Test needs refactoring for event-driven listener removal.");
    }
}