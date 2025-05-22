package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaListenerPool}.
 */
@ExtendWith(MockitoExtension.class)
class KafkaListenerPoolTest {

    private KafkaListenerPool listenerPool;

    @Mock
    private PipeStreamEngine mockPipeStreamEngine;

    @Mock
    private DynamicKafkaListener mockListener;

    private static final String LISTENER_ID = "test-listener";
    private static final String TOPIC = "test-topic";
    private static final String GROUP_ID = "test-group";
    private static final String PIPELINE_NAME = "test-pipeline";
    private static final String STEP_NAME = "test-step";
    private Map<String, Object> consumerConfig;

    @BeforeEach
    void setUp() {
        listenerPool = new KafkaListenerPool();
        consumerConfig = new HashMap<>();
    }

    /**
     * Test that createListener correctly creates and stores a listener.
     * 
     * Note: This test is challenging because DynamicKafkaListener creates a real KafkaConsumer
     * in its constructor, which we can't do in a unit test. We'll need to refactor the code
     * to make it more testable, or use a more integration-test approach.
     */
    @Test
    void testCreateListener() {
        // This test is incomplete because we can't create a real DynamicKafkaListener in a unit test.
        // We would need to refactor the KafkaListenerPool class to make it more testable.
        // For example, by allowing a DynamicKafkaListener to be injected rather than created internally.
    }

    /**
     * Test that createListener returns an existing listener if one exists with the same ID.
     */
    @Test
    void testCreateListenerReturnsExistingListener() {
        // Setup: Add a mock listener to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        listeners.put(LISTENER_ID, mockListener);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Call createListener with the same ID
            DynamicKafkaListener result = listenerPool.createListener(
                    LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, PIPELINE_NAME, STEP_NAME, mockPipeStreamEngine);

            // Verify: The existing listener is returned
            assertSame(mockListener, result);

            // Verify: No new listener was created
            assertEquals(1, listeners.size());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that removeListener correctly removes a listener.
     */
    @Test
    void testRemoveListener() {
        // Setup: Add a mock listener to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        listeners.put(LISTENER_ID, mockListener);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Remove the listener
            DynamicKafkaListener result = listenerPool.removeListener(LISTENER_ID);

            // Verify: The correct listener was returned
            assertSame(mockListener, result);

            // Verify: The listener was removed from the pool
            assertEquals(0, listeners.size());

            // Verify: The listener was shut down
            verify(mockListener).shutdown();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that removeListener returns null for a non-existent listener.
     */
    @Test
    void testRemoveListenerForNonExistentListener() {
        // Test: Remove a non-existent listener
        DynamicKafkaListener result = listenerPool.removeListener("non-existent-listener");

        // Verify: null is returned
        assertNull(result);
    }

    /**
     * Test that getListener returns the correct listener.
     */
    @Test
    void testGetListener() {
        // Setup: Add a mock listener to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        listeners.put(LISTENER_ID, mockListener);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Get the listener
            DynamicKafkaListener result = listenerPool.getListener(LISTENER_ID);

            // Verify: The correct listener is returned
            assertSame(mockListener, result);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that getListener returns null for a non-existent listener.
     */
    @Test
    void testGetListenerForNonExistentListener() {
        // Test: Get a non-existent listener
        DynamicKafkaListener result = listenerPool.getListener("non-existent-listener");

        // Verify: null is returned
        assertNull(result);
    }

    /**
     * Test that getAllListeners returns all listeners.
     */
    @Test
    void testGetAllListeners() {
        // Setup: Add mock listeners to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        DynamicKafkaListener mockListener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener mockListener2 = mock(DynamicKafkaListener.class);
        listeners.put("listener1", mockListener1);
        listeners.put("listener2", mockListener2);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Get all listeners
            Collection<DynamicKafkaListener> result = listenerPool.getAllListeners();

            // Verify: All listeners are returned
            assertEquals(2, result.size());
            assertTrue(result.contains(mockListener1));
            assertTrue(result.contains(mockListener2));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that getAllListeners returns an unmodifiable collection.
     */
    @Test
    void testGetAllListenersReturnsUnmodifiableCollection() {
        // Setup: Add a mock listener to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        listeners.put(LISTENER_ID, mockListener);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Get all listeners
            Collection<DynamicKafkaListener> result = listenerPool.getAllListeners();

            // Verify: The collection is unmodifiable
            assertThrows(UnsupportedOperationException.class, () -> result.add(mock(DynamicKafkaListener.class)));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that getListenerCount returns the correct count.
     */
    @Test
    void testGetListenerCount() {
        // Setup: Add mock listeners to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        DynamicKafkaListener mockListener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener mockListener2 = mock(DynamicKafkaListener.class);
        listeners.put("listener1", mockListener1);
        listeners.put("listener2", mockListener2);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Get the listener count
            int result = listenerPool.getListenerCount();

            // Verify: The correct count is returned
            assertEquals(2, result);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that hasListener correctly reports whether a listener exists.
     */
    @Test
    void testHasListener() {
        // Setup: Add a mock listener to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        listeners.put(LISTENER_ID, mockListener);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Check if listeners exist
            boolean hasExistingListener = listenerPool.hasListener(LISTENER_ID);
            boolean hasNonExistentListener = listenerPool.hasListener("non-existent-listener");

            // Verify: hasListener returns the correct results
            assertTrue(hasExistingListener);
            assertFalse(hasNonExistentListener);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that shutdownAllListeners correctly shuts down all listeners.
     */
    @Test
    void testShutdownAllListeners() {
        // Setup: Add mock listeners to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        DynamicKafkaListener mockListener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener mockListener2 = mock(DynamicKafkaListener.class);
        listeners.put("listener1", mockListener1);
        listeners.put("listener2", mockListener2);

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Shut down all listeners
            listenerPool.shutdownAllListeners();

            // Verify: All listeners were shut down
            verify(mockListener1).shutdown();
            verify(mockListener2).shutdown();

            // Verify: The listeners map was cleared
            assertEquals(0, listeners.size());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }

    /**
     * Test that shutdownAllListeners handles exceptions from listeners.
     */
    @Test
    void testShutdownAllListenersHandlesExceptions() {
        // Setup: Add mock listeners to the pool using reflection
        Map<String, DynamicKafkaListener> listeners = new HashMap<>();
        DynamicKafkaListener mockListener1 = mock(DynamicKafkaListener.class);
        DynamicKafkaListener mockListener2 = mock(DynamicKafkaListener.class);
        listeners.put("listener1", mockListener1);
        listeners.put("listener2", mockListener2);

        // Make the first listener throw an exception when shutdown is called
        doThrow(new RuntimeException("Test exception")).when(mockListener1).shutdown();

        try {
            // Use reflection to set the private listeners field
            java.lang.reflect.Field listenersField = KafkaListenerPool.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            listenersField.set(listenerPool, listeners);

            // Test: Shut down all listeners
            listenerPool.shutdownAllListeners();

            // Verify: All listeners were shut down, even though one threw an exception
            verify(mockListener1).shutdown();
            verify(mockListener2).shutdown();

            // Verify: The listeners map was cleared
            assertEquals(0, listeners.size());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Failed to set up test: " + e.getMessage());
        }
    }
}
