package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.admin.KafkaAdminService;
import com.krickert.search.pipeline.engine.kafka.admin.OffsetResetParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;
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
    private KafkaListenerPool mockListenerPool;
    
    @Mock
    private ConsumerStateManager mockStateManager;
    
    @Mock
    private KafkaAdminService mockKafkaAdminService;
    
    @Mock
    private DynamicConfigurationManager mockConfigManager;
    
    @Mock
    private PipeStreamEngine mockPipeStreamEngine;
    
    @Mock
    private DynamicKafkaListener mockListener;
    
    private static final String PIPELINE_NAME = "test-pipeline";
    private static final String STEP_NAME = "test-step";
    private static final String TOPIC = "test-topic";
    private static final String GROUP_ID = "test-group";
    private static final String LISTENER_ID = "test-listener-id";

    @BeforeEach
    void setUp() {
        listenerManager = new KafkaListenerManager(
                mockListenerPool,
                mockStateManager,
                mockKafkaAdminService,
                mockConfigManager,
                mockPipeStreamEngine
        );
    }

    /**
     * Test that createListenersForPipeline correctly creates listeners for a pipeline.
     */
    @Test
    void testCreateListenersForPipeline() {
        // Setup: Create a pipeline config with a step that has Kafka inputs
        PipelineStepConfig stepConfig = mock(PipelineStepConfig.class);
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(STEP_NAME, stepConfig);
        
        PipelineConfig pipelineConfig = mock(PipelineConfig.class);
        when(pipelineConfig.pipelineSteps()).thenReturn(steps);
        
        when(mockConfigManager.getPipelineConfig(PIPELINE_NAME)).thenReturn(Optional.of(pipelineConfig));
        
        // Setup: Create a Kafka input definition
        KafkaInputDefinition kafkaInput = mock(KafkaInputDefinition.class);
        when(kafkaInput.listenTopics()).thenReturn(Collections.singletonList(TOPIC));
        when(kafkaInput.consumerGroupId()).thenReturn(GROUP_ID);
        when(kafkaInput.kafkaConsumerProperties()).thenReturn(Collections.emptyMap());
        
        when(stepConfig.kafkaInputs()).thenReturn(Collections.singletonList(kafkaInput));
        
        // Setup: Mock the createListener method to return a mock listener
        when(mockListenerPool.createListener(
                anyString(), eq(TOPIC), eq(GROUP_ID), anyMap(), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        )).thenReturn(mockListener);
        
        // Test: Create listeners for the pipeline
        List<String> result = listenerManager.createListenersForPipeline(PIPELINE_NAME);
        
        // Verify: A listener was created
        verify(mockListenerPool).createListener(
                anyString(), eq(TOPIC), eq(GROUP_ID), anyMap(), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        );
        
        // Verify: The state was updated
        verify(mockStateManager).updateState(anyString(), any(ConsumerState.class));
        
        // Verify: The result contains the listener ID
        assertEquals(1, result.size());
    }

    /**
     * Test that createListenersForPipeline returns an empty list for a non-existent pipeline.
     */
    @Test
    void testCreateListenersForPipelineNonExistentPipeline() {
        // Setup: Mock the config manager to return an empty optional
        when(mockConfigManager.getPipelineConfig(PIPELINE_NAME)).thenReturn(Optional.empty());
        
        // Test: Create listeners for a non-existent pipeline
        List<String> result = listenerManager.createListenersForPipeline(PIPELINE_NAME);
        
        // Verify: No listeners were created
        verify(mockListenerPool, never()).createListener(
                anyString(), anyString(), anyString(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)
        );
        
        // Verify: The result is an empty list
        assertTrue(result.isEmpty());
    }

    /**
     * Test that createListener correctly creates a listener.
     */
    @Test
    void testCreateListener() {
        // Setup: Mock the listener pool to return a mock listener
        when(mockListenerPool.createListener(
                anyString(), eq(TOPIC), eq(GROUP_ID), anyMap(), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        )).thenReturn(mockListener);
        
        // Test: Create a listener
        DynamicKafkaListener result = listenerManager.createListener(
                PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID, Collections.emptyMap()
        );
        
        // Verify: A listener was created
        verify(mockListenerPool).createListener(
                anyString(), eq(TOPIC), eq(GROUP_ID), anyMap(), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        );
        
        // Verify: The state was updated
        verify(mockStateManager).updateState(anyString(), any(ConsumerState.class));
        
        // Verify: The result is the mock listener
        assertSame(mockListener, result);
    }

    /**
     * Test that createListener returns an existing listener if one exists.
     */
    @Test
    void testCreateListenerExistingListener() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Test: Create a listener
        DynamicKafkaListener result = listenerManager.createListener(
                PIPELINE_NAME, STEP_NAME, TOPIC, GROUP_ID, Collections.emptyMap()
        );
        
        // Verify: No new listener was created
        verify(mockListenerPool, never()).createListener(
                anyString(), anyString(), anyString(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)
        );
        
        // Verify: The result is the existing mock listener
        assertSame(mockListener, result);
    }

    /**
     * Test that pauseConsumer correctly pauses a consumer.
     */
    @Test
    void testPauseConsumer() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Setup: Mock the listener to return topic and group ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        
        // Test: Pause the consumer
        CompletableFuture<Void> result = listenerManager.pauseConsumer(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The listener was paused
        verify(mockListener).pause();
        
        // Verify: The state was updated
        verify(mockStateManager).updateState(eq(LISTENER_ID), any(ConsumerState.class));
        
        // Verify: The result is a completed future
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    /**
     * Test that pauseConsumer throws an exception for a non-existent listener.
     */
    @Test
    void testPauseConsumerNonExistentListener() {
        // Test: Pause a non-existent consumer
        CompletableFuture<Void> result = listenerManager.pauseConsumer(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The result is a failed future
        assertTrue(result.isCompletedExceptionally());
        
        // Verify: No listener was paused
        verify(mockListener, never()).pause();
        
        // Verify: No state was updated
        verify(mockStateManager, never()).updateState(anyString(), any(ConsumerState.class));
    }

    /**
     * Test that resumeConsumer correctly resumes a consumer.
     */
    @Test
    void testResumeConsumer() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Setup: Mock the listener to return topic and group ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        
        // Test: Resume the consumer
        CompletableFuture<Void> result = listenerManager.resumeConsumer(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The listener was resumed
        verify(mockListener).resume();
        
        // Verify: The state was updated
        verify(mockStateManager).updateState(eq(LISTENER_ID), any(ConsumerState.class));
        
        // Verify: The result is a completed future
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    /**
     * Test that resumeConsumer throws an exception for a non-existent listener.
     */
    @Test
    void testResumeConsumerNonExistentListener() {
        // Test: Resume a non-existent consumer
        CompletableFuture<Void> result = listenerManager.resumeConsumer(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The result is a failed future
        assertTrue(result.isCompletedExceptionally());
        
        // Verify: No listener was resumed
        verify(mockListener, never()).resume();
        
        // Verify: No state was updated
        verify(mockStateManager, never()).updateState(anyString(), any(ConsumerState.class));
    }

    /**
     * Test that resetOffsetToDate correctly resets a consumer's offset to a specific date.
     */
    @Test
    void testResetOffsetToDate() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Setup: Mock the listener to return topic and group ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        
        // Setup: Mock the pause and resume methods to return completed futures
//        when(mockListener.pause()).thenReturn(null);
//        when(mockListener.resume()).thenReturn(null);
//
        // Setup: Mock the KafkaAdminService to return a completed future
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(
                anyString(), anyString(), any(OffsetResetParameters.class)
        )).thenReturn(CompletableFuture.completedFuture(null));
        
        // Test: Reset the offset to a specific date
        Instant date = Instant.now();
        CompletableFuture<Void> result = listenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, date);
        
        // Verify: The listener was paused
        verify(mockListener).pause();
        
        // Verify: The KafkaAdminService was called with the correct parameters
        ArgumentCaptor<OffsetResetParameters> paramsCaptor = ArgumentCaptor.forClass(OffsetResetParameters.class);
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(
                eq(GROUP_ID), eq(TOPIC), paramsCaptor.capture()
        );
        
        // Verify: The timestamp in the parameters is correct
        assertEquals(date.toEpochMilli(), paramsCaptor.getValue().getTimestamp());
        
        // Verify: The listener was resumed
        verify(mockListener).resume();
        
        // Verify: The result is a completed future
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    /**
     * Test that resetOffsetToDate throws an exception for a non-existent listener.
     */
    @Test
    void testResetOffsetToDateNonExistentListener() {
        // Test: Reset the offset for a non-existent consumer
        Instant date = Instant.now();
        CompletableFuture<Void> result = listenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, date);
        
        // Verify: The result is a failed future
        assertTrue(result.isCompletedExceptionally());
        
        // Verify: No listener was paused or resumed
        verify(mockListener, never()).pause();
        verify(mockListener, never()).resume();
        
        // Verify: The KafkaAdminService was not called
        verify(mockKafkaAdminService, never()).resetConsumerGroupOffsetsAsync(
                anyString(), anyString(), any(OffsetResetParameters.class)
        );
    }

    /**
     * Test that resetOffsetToEarliest correctly resets a consumer's offset to earliest.
     */
    @Test
    void testResetOffsetToEarliest() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Setup: Mock the listener to return topic and group ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
        
//        // Setup: Mock the pause and resume methods to return completed futures
//        when(mockListener.pause()).thenReturn(null);
//        when(mockListener.resume()).thenReturn(null);
        
        // Setup: Mock the KafkaAdminService to return a completed future
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(
                anyString(), anyString(), any(OffsetResetParameters.class)
        )).thenReturn(CompletableFuture.completedFuture(null));
        
        // Test: Reset the offset to earliest
        CompletableFuture<Void> result = listenerManager.resetOffsetToEarliest(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The listener was paused
        verify(mockListener).pause();
        
        // Verify: The KafkaAdminService was called with the correct parameters
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(
                eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class)
        );
        
        // Verify: The listener was resumed
        verify(mockListener).resume();
        
        // Verify: The result is a completed future
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    /**
     * Test that resetOffsetToLatest correctly resets a consumer's offset to latest.
     */
    @Test
    void testResetOffsetToLatest() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Setup: Mock the listener pool to return a mock listener for the existing ID
        when(mockListenerPool.getListener(LISTENER_ID)).thenReturn(mockListener);
        
        // Setup: Mock the listener to return topic and group ID
        when(mockListener.getTopic()).thenReturn(TOPIC);
        when(mockListener.getGroupId()).thenReturn(GROUP_ID);
//
//        // Setup: Mock the pause and resume methods to return completed futures
//        when(mockListener.pause()).thenReturn(null);
//        when(mockListener.resume()).thenReturn(null);
        
        // Setup: Mock the KafkaAdminService to return a completed future
        when(mockKafkaAdminService.resetConsumerGroupOffsetsAsync(
                anyString(), anyString(), any(OffsetResetParameters.class)
        )).thenReturn(CompletableFuture.completedFuture(null));
        
        // Test: Reset the offset to latest
        CompletableFuture<Void> result = listenerManager.resetOffsetToLatest(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The listener was paused
        verify(mockListener).pause();
        
        // Verify: The KafkaAdminService was called with the correct parameters
        verify(mockKafkaAdminService).resetConsumerGroupOffsetsAsync(
                eq(GROUP_ID), eq(TOPIC), any(OffsetResetParameters.class)
        );
        
        // Verify: The listener was resumed
        verify(mockListener).resume();
        
        // Verify: The result is a completed future
        assertTrue(result.isDone());
        assertFalse(result.isCompletedExceptionally());
    }

    /**
     * Test that getConsumerStatuses correctly returns the status of all consumers.
     */
    @Test
    void testGetConsumerStatuses() {
        // Setup: Mock the listener pool to return a collection of mock listeners
        DynamicKafkaListener mockListener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener mockListener2 = mock(DynamicKafkaListener.class);
        Collection<DynamicKafkaListener> listeners = Arrays.asList(mockListener1, mockListener2);
        
        when(mockListenerPool.getAllListeners()).thenReturn(listeners);
        
        // Setup: Mock the listeners to return their IDs, topics, group IDs, etc.
        when(mockListener1.getListenerId()).thenReturn("listener1");
        when(mockListener1.getPipelineName()).thenReturn("pipeline1");
        when(mockListener1.getStepName()).thenReturn("step1");
        when(mockListener1.getTopic()).thenReturn("topic1");
        when(mockListener1.getGroupId()).thenReturn("group1");
        when(mockListener1.isPaused()).thenReturn(false);
        
        when(mockListener2.getListenerId()).thenReturn("listener2");
        when(mockListener2.getPipelineName()).thenReturn("pipeline2");
        when(mockListener2.getStepName()).thenReturn("step2");
        when(mockListener2.getTopic()).thenReturn("topic2");
        when(mockListener2.getGroupId()).thenReturn("group2");
        when(mockListener2.isPaused()).thenReturn(true);
        
        // Setup: Mock the state manager to return states for the listeners
        ConsumerState state1 = new ConsumerState("listener1", "topic1", "group1", false, Instant.now(), Collections.emptyMap());
        ConsumerState state2 = new ConsumerState("listener2", "topic2", "group2", true, Instant.now(), Collections.emptyMap());
        
        when(mockStateManager.getState("listener1")).thenReturn(state1);
        when(mockStateManager.getState("listener2")).thenReturn(state2);
        
        // Test: Get the consumer statuses
        Map<String, ConsumerStatus> result = listenerManager.getConsumerStatuses();
        
        // Verify: The result contains the correct statuses
        assertEquals(2, result.size());
        
        ConsumerStatus status1 = result.get("listener1");
        assertEquals("listener1", status1.id());
        assertEquals("pipeline1", status1.pipelineName());
        assertEquals("step1", status1.stepName());
        assertEquals("topic1", status1.topic());
        assertEquals("group1", status1.groupId());
        assertFalse(status1.paused());
        
        ConsumerStatus status2 = result.get("listener2");
        assertEquals("listener2", status2.id());
        assertEquals("pipeline2", status2.pipelineName());
        assertEquals("step2", status2.stepName());
        assertEquals("topic2", status2.topic());
        assertEquals("group2", status2.groupId());
        assertTrue(status2.paused());
    }

    /**
     * Test that removeListener correctly removes a listener.
     */
    @Test
    void testRemoveListener() throws Exception {
        // Setup: Use reflection to set up an existing listener in the pipelineStepToListenerMap
        Map<String, String> pipelineStepToListenerMap = new HashMap<>();
        String pipelineStepKey = PIPELINE_NAME + ":" + STEP_NAME;
        pipelineStepToListenerMap.put(pipelineStepKey, LISTENER_ID);
        
        java.lang.reflect.Field mapField = KafkaListenerManager.class.getDeclaredField("pipelineStepToListenerMap");
        mapField.setAccessible(true);
        mapField.set(listenerManager, pipelineStepToListenerMap);
        
        // Test: Remove the listener
        boolean result = listenerManager.removeListener(PIPELINE_NAME, STEP_NAME);
        
        // Verify: The listener was removed from the pool
        verify(mockListenerPool).removeListener(LISTENER_ID);
        
        // Verify: The state was removed
        verify(mockStateManager).removeState(LISTENER_ID);
        
        // Verify: The result is true
        assertTrue(result);
        
        // Verify: The listener was removed from the map
        assertTrue(pipelineStepToListenerMap.isEmpty());
    }

    /**
     * Test that removeListener returns false for a non-existent listener.
     */
    @Test
    void testRemoveListenerNonExistentListener() {
        // Test: Remove a non-existent listener
        boolean result = listenerManager.removeListener(PIPELINE_NAME, STEP_NAME);
        
        // Verify: No listener was removed from the pool
        verify(mockListenerPool, never()).removeListener(anyString());
        
        // Verify: No state was removed
        verify(mockStateManager, never()).removeState(anyString());
        
        // Verify: The result is false
        assertFalse(result);
    }
}