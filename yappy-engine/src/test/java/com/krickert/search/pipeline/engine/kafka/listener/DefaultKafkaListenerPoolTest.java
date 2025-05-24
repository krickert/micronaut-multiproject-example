package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
// Micronaut @Value and jakarta.inject.Inject are not needed for this specific unit test setup
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
// No need to import ConcurrentHashMap for direct use in tests if we don't use reflection for the listeners map

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultKafkaListenerPoolTest {

    @Mock
    private DynamicKafkaListenerFactory mockListenerFactory; // Mock the factory
    @Mock
    private PipeStreamEngine mockPipeStreamEngine;
    @Mock
    private DynamicKafkaListener mockCreatedListener; // A generic mock for listeners returned by the factory
    @Mock
    private DynamicKafkaListener mockExistingListener; // A generic mock for pre-existing listeners

    private DefaultKafkaListenerPool listenerPool;

    private static final String LISTENER_ID = "test-listener";
    private static final String OTHER_LISTENER_ID = "other-listener";
    private static final String TOPIC = "test-topic";
    private static final String GROUP_ID = "test-group";
    private static final String PIPELINE_NAME = "test-pipeline";
    private static final String STEP_NAME = "test-step";
    private Map<String, Object> consumerConfig;
    private Map<String, String> originalProps;

    @BeforeEach
    void setUp() {
        listenerPool = new DefaultKafkaListenerPool(mockListenerFactory); // Inject the mock factory
        consumerConfig = new HashMap<>(); // Keep for passing to createListener
        consumerConfig.put("bootstrap.servers", "dummy:9092"); // Still good practice for parameters
        originalProps = Collections.emptyMap();
    }

    @Test
    void testCreateListener_createsAndStoresNewListener() {
        when(mockListenerFactory.create(
                eq(LISTENER_ID), eq(TOPIC), eq(GROUP_ID), eq(consumerConfig),
                eq(originalProps), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        )).thenReturn(mockCreatedListener); // Factory returns our mock

        DynamicKafkaListener createdListener = listenerPool.createListener(
                LISTENER_ID, TOPIC, GROUP_ID, consumerConfig,
                originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        assertSame(mockCreatedListener, createdListener, "Listener returned by pool should be the one from the factory.");
        assertEquals(1, listenerPool.getListenerCount());
        assertSame(mockCreatedListener, listenerPool.getListener(LISTENER_ID), "Created listener should be in the pool.");
        verify(mockListenerFactory).create(anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class));
    }

    @Test
    void testCreateListener_whenListenerExists_replacesExistingListener() throws Exception {
        // 1. Setup initial state: Pool has an existing listener
        // We can't directly put into the pool's map anymore without reflection,
        // so we'll simulate it by having createListener called once, then again.

        // First call to createListener (simulates pre-existing listener)
        when(mockListenerFactory.create(
                eq(LISTENER_ID), eq(TOPIC), eq(GROUP_ID), eq(consumerConfig),
                eq(originalProps), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        )).thenReturn(mockExistingListener); // Factory returns mockExistingListener first time

        listenerPool.createListener( // This puts mockExistingListener into the pool
                LISTENER_ID, TOPIC, GROUP_ID, consumerConfig,
                originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        assertEquals(1, listenerPool.getListenerCount());
        assertSame(mockExistingListener, listenerPool.getListener(LISTENER_ID));

        // 2. Setup for the second call that will replace the existing one
        Map<String, Object> newConsumerConfig = new HashMap<>(consumerConfig);
        newConsumerConfig.put("some.new.prop", "newValue");
        when(mockListenerFactory.create( // Factory will return a *different* mock for the replacement
                eq(LISTENER_ID), eq(TOPIC), eq(GROUP_ID), eq(newConsumerConfig), // Potentially new config
                eq(originalProps), eq(PIPELINE_NAME), eq(STEP_NAME), eq(mockPipeStreamEngine)
        )).thenReturn(mockCreatedListener); // mockCreatedListener is the "new" one

        // 3. Test: Call createListener again with the same ID
        DynamicKafkaListener newReturnedListener = listenerPool.createListener(
                LISTENER_ID, TOPIC, GROUP_ID, newConsumerConfig, // Pass new config
                originalProps,
                PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        // 4. Verify
        verify(mockExistingListener).shutdown(); // Shutdown called on the first listener
        assertSame(mockCreatedListener, newReturnedListener, "The newly created listener should be returned.");
        assertEquals(1, listenerPool.getListenerCount(), "Pool should still have 1 listener after replacement.");
        assertSame(mockCreatedListener, listenerPool.getListener(LISTENER_ID), "Pool should now contain the new listener.");
        verify(mockListenerFactory, times(2)).create(anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class));
    }

    @Test
    void testRemoveListener() {
        // Setup: Add a listener to the pool by calling createListener
        when(mockListenerFactory.create(anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(mockExistingListener);
        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        // Test
        DynamicKafkaListener removedListener = listenerPool.removeListener(LISTENER_ID);

        // Verify
        assertSame(mockExistingListener, removedListener);
        verify(mockExistingListener).shutdown();
        assertEquals(0, listenerPool.getListenerCount());
        assertNull(listenerPool.getListener(LISTENER_ID));
    }

    @Test
    void testRemoveListenerForNonExistentListener() {
        DynamicKafkaListener result = listenerPool.removeListener("non-existent-listener");
        assertNull(result);
    }

    @Test
    void testGetListener() {
        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(mockExistingListener);
        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        DynamicKafkaListener found = listenerPool.getListener(LISTENER_ID);
        assertSame(mockExistingListener, found);
    }

    @Test
    void testGetListenerForNonExistentListener() {
        assertNull(listenerPool.getListener("non-existent-listener"));
    }

    @Test
    void testGetAllListeners() {
        DynamicKafkaListener listener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener listener2 = mock(DynamicKafkaListener.class);

        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener1);
        when(mockListenerFactory.create(eq(OTHER_LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener2);

        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        listenerPool.createListener(OTHER_LISTENER_ID, "other-topic", GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        Collection<DynamicKafkaListener> allListeners = listenerPool.getAllListeners();
        assertEquals(2, allListeners.size());
        assertTrue(allListeners.contains(listener1));
        assertTrue(allListeners.contains(listener2));
    }

    @Test
    void testGetAllListenersReturnsUnmodifiableCollection() {
        when(mockListenerFactory.create(anyString(), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(mockCreatedListener);
        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        Collection<DynamicKafkaListener> result = listenerPool.getAllListeners();
        assertThrows(UnsupportedOperationException.class, () -> result.add(mock(DynamicKafkaListener.class)));
    }


    @Test
    void testGetListenerCount() {
        DynamicKafkaListener listener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener listener2 = mock(DynamicKafkaListener.class);

        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener1);
        when(mockListenerFactory.create(eq(OTHER_LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener2);

        assertEquals(0, listenerPool.getListenerCount());
        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        assertEquals(1, listenerPool.getListenerCount());
        listenerPool.createListener(OTHER_LISTENER_ID, "other-topic", GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        assertEquals(2, listenerPool.getListenerCount());
    }

    @Test
    void testHasListener() {
        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(mockCreatedListener);

        assertFalse(listenerPool.hasListener(LISTENER_ID));
        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        assertTrue(listenerPool.hasListener(LISTENER_ID));
        assertFalse(listenerPool.hasListener("non-existent-listener"));
    }

    @Test
    void testShutdownAllListeners() {
        DynamicKafkaListener listener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener listener2 = mock(DynamicKafkaListener.class);

        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener1);
        when(mockListenerFactory.create(eq(OTHER_LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener2);

        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        listenerPool.createListener(OTHER_LISTENER_ID, "other-topic", GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        listenerPool.shutdownAllListeners();

        verify(listener1).shutdown();
        verify(listener2).shutdown();
        assertEquals(0, listenerPool.getListenerCount());
    }

    @Test
    void testShutdownAllListenersHandlesExceptions() {
        DynamicKafkaListener listener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener listener2 = mock(DynamicKafkaListener.class);

        when(mockListenerFactory.create(eq(LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener1);
        when(mockListenerFactory.create(eq(OTHER_LISTENER_ID), anyString(), anyString(), anyMap(), anyMap(), anyString(), anyString(), any(PipeStreamEngine.class)))
                .thenReturn(listener2);

        listenerPool.createListener(LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);
        listenerPool.createListener(OTHER_LISTENER_ID, "other-topic", GROUP_ID, consumerConfig, originalProps, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

        doThrow(new RuntimeException("Test exception")).when(listener1).shutdown();

        listenerPool.shutdownAllListeners(); // Should not throw, should log error

        verify(listener1).shutdown();
        verify(listener2).shutdown(); // Ensure second listener is still shut down
        assertEquals(0, listenerPool.getListenerCount());
    }
}