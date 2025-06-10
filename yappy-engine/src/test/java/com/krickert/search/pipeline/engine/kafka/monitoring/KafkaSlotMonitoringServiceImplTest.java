package com.krickert.search.pipeline.engine.kafka.monitoring;

import com.krickert.search.model.*;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for KafkaSlotMonitoringServiceImpl
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaSlotMonitoringServiceImplTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSlotMonitoringServiceImplTest.class);
    
    @Inject
    KafkaSlotMonitoringServiceImpl monitoringService;
    
    @Mock
    KafkaSlotManager mockSlotManager;
    
    @Mock
    KafkaSlotMonitoringServiceImpl.RebalancingEventPublisher mockEventPublisher;
    
    @Mock
    StreamObserver<RebalancingEvent> mockEventObserver;
    
    @Mock
    StreamObserver<SlotDistributionSnapshot> mockDistributionObserver;
    
    @Mock
    StreamObserver<SlotMetrics> mockMetricsObserver;
    
    @Captor
    ArgumentCaptor<RebalancingEvent> eventCaptor;
    
    private AutoCloseable mocks;
    
    @BeforeEach
    void setup() {
        mocks = MockitoAnnotations.openMocks(this);
    }
    
    @AfterEach
    void cleanup() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }
    
    @Test
    @DisplayName("Should stream rebalancing events when slots change")
    void testStreamRebalancingEvents() throws Exception {
        LOG.info("Testing rebalancing event streaming");
        
        // Given
        String engineId = "test-engine-1";
        List<KafkaSlot> initialSlots = Arrays.asList(
            new KafkaSlot("test-topic", 0, "test-group"),
            new KafkaSlot("test-topic", 1, "test-group")
        );
        
        // Create a controllable flux for assignments
        Sinks.Many<SlotAssignment> assignmentSink = Sinks.many().multicast().onBackpressureBuffer();
        when(mockSlotManager.watchAssignments(engineId)).thenReturn(assignmentSink.asFlux());
        
        // Mock registered engines
        when(mockSlotManager.getRegisteredEngines()).thenReturn(
            Flux.just(new KafkaSlotManager.EngineInfo(engineId, 10, 2, Instant.now(), true))
        );
        
        CountDownLatch eventReceived = new CountDownLatch(1);
        AtomicReference<RebalancingEvent> receivedEvent = new AtomicReference<>();
        
        // Create a test observer
        StreamObserver<RebalancingEvent> testObserver = new StreamObserver<>() {
            @Override
            public void onNext(RebalancingEvent event) {
                LOG.info("Received event: {} for engine: {}", event.getEventType(), event.getEngineId());
                receivedEvent.set(event);
                eventReceived.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("Stream error", t);
            }
            
            @Override
            public void onCompleted() {
                LOG.info("Stream completed");
            }
        };
        
        // When
        StreamRebalancingRequest request = StreamRebalancingRequest.newBuilder()
            .addEngineIds(engineId)
            .setIncludeInitialState(false)
            .build();
        
        monitoringService.streamRebalancingEvents(request, testObserver);
        
        // Trigger a slot assignment change
        SlotAssignment newAssignment = new SlotAssignment(
            engineId,
            Arrays.asList(
                new KafkaSlot("test-topic", 0, "test-group"),
                new KafkaSlot("test-topic", 1, "test-group"),
                new KafkaSlot("test-topic", 2, "test-group")
            ),
            Instant.now(),
            Instant.now()
        );
        
        assignmentSink.tryEmitNext(newAssignment);
        
        // Then
        assertTrue(eventReceived.await(5, TimeUnit.SECONDS), "Should receive rebalancing event");
        
        RebalancingEvent event = receivedEvent.get();
        assertNotNull(event);
        assertEquals(engineId, event.getEngineId());
        assertEquals(RebalancingEvent.EventType.SLOTS_ACQUIRED, event.getEventType());
        assertEquals(3, event.getNewPartitionsList().size());
        assertTrue(event.getNewPartitionsList().contains(2));
        
        // Verify event was published to Kafka
        verify(mockEventPublisher).publishEvent(eq(engineId), any(RebalancingEvent.class));
    }
    
    @Test
    @DisplayName("Should calculate correct distribution statistics")
    void testGetSlotDistribution() {
        LOG.info("Testing slot distribution calculation");
        
        // Given
        Map<String, Integer> distribution = new HashMap<>();
        distribution.put("engine-1", 4);
        distribution.put("engine-2", 3);
        distribution.put("engine-3", 5);
        
        when(mockSlotManager.getSlotDistribution()).thenReturn(Mono.just(distribution));
        
        // Mock engine info
        when(mockSlotManager.getRegisteredEngines()).thenReturn(Flux.fromIterable(
            Arrays.asList(
                new KafkaSlotManager.EngineInfo("engine-1", 10, 4, Instant.now(), true),
                new KafkaSlotManager.EngineInfo("engine-2", 10, 3, Instant.now(), true),
                new KafkaSlotManager.EngineInfo("engine-3", 10, 5, Instant.now(), true)
            )
        ));
        
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<SlotDistributionSnapshot> receivedSnapshot = new AtomicReference<>();
        
        StreamObserver<SlotDistributionSnapshot> testObserver = new StreamObserver<>() {
            @Override
            public void onNext(SlotDistributionSnapshot snapshot) {
                receivedSnapshot.set(snapshot);
                responseLatch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("Error getting distribution", t);
                responseLatch.countDown();
            }
            
            @Override
            public void onCompleted() {
                LOG.info("Distribution request completed");
            }
        };
        
        // When
        monitoringService.getSlotDistribution(Empty.getDefaultInstance(), testObserver);
        
        // Then
        try {
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive distribution snapshot");
            
            SlotDistributionSnapshot snapshot = receivedSnapshot.get();
            assertNotNull(snapshot);
            assertEquals(12, snapshot.getTotalPartitions()); // 4+3+5
            assertEquals(3, snapshot.getActiveEngines());
            
            DistributionStats stats = snapshot.getStats();
            assertEquals(4.0, stats.getAveragePartitions(), 0.01);
            assertEquals(3, stats.getMinPartitions());
            assertEquals(5, stats.getMaxPartitions());
            
            // Imbalance ratio = (max-min)/avg = (5-3)/4 = 0.5
            assertEquals(0.5, stats.getImbalanceRatio(), 0.01);
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }
    }
    
    @Test
    @DisplayName("Should filter historical events correctly")
    void testGetRebalancingHistory() {
        LOG.info("Testing rebalancing history filtering");
        
        // This test would require setting up historical events
        // For now, we'll test the basic flow
        
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<RebalancingHistoryResponse> receivedResponse = new AtomicReference<>();
        
        StreamObserver<RebalancingHistoryResponse> testObserver = new StreamObserver<>() {
            @Override
            public void onNext(RebalancingHistoryResponse response) {
                receivedResponse.set(response);
                responseLatch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                LOG.error("Error getting history", t);
                responseLatch.countDown();
            }
            
            @Override
            public void onCompleted() {
                LOG.info("History request completed");
            }
        };
        
        // When
        RebalancingHistoryRequest request = RebalancingHistoryRequest.newBuilder()
            .setLimit(10)
            .build();
        
        monitoringService.getRebalancingHistory(request, testObserver);
        
        // Then
        try {
            assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Should receive history response");
            
            RebalancingHistoryResponse response = receivedResponse.get();
            assertNotNull(response);
            assertNotNull(response.getSummary());
            // More assertions would go here once we have historical data
            
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }
    }
    
    @Test
    @Disabled("TODO: Complete when metrics implementation is ready")
    @DisplayName("Should stream periodic metrics updates")
    void testStreamMetrics() {
        fail("TODO: Implement metrics streaming test");
        // Detailed test plan:
        // 1. Mock slot manager metrics methods:
        //    - getSlotDistribution() returns engine->count map
        //    - getAllSlots() returns topic:group->slots map
        //    - getRegisteredEngines() returns engine info flux
        // 2. Request metrics stream with:
        //    - 2 second interval
        //    - include_topic_metrics = true
        //    - include_engine_metrics = true
        // 3. Verify metrics are sent at correct intervals:
        //    - Use CountDownLatch(3) to wait for 3 updates
        //    - Measure time between updates (should be ~2s ± 100ms)
        // 4. Check metrics include all requested components:
        //    - GlobalMetrics with total_slots, assigned_slots, etc.
        //    - EngineMetrics map with at least 2 engines
        //    - TopicMetrics map with partition distribution
        // 5. Edge cases to test:
        //    - Zero interval should default to minimum (1s?)
        //    - Negative interval should error
        //    - Stream continues even if one metric collection fails
        // 6. Verify stream cleanup:
        //    - Complete the stream observer
        //    - Verify no more updates after completion
        //    - Check memory cleanup (no lingering subscriptions)
    }
    
    @Test
    @Disabled("TODO: Test engine registration and unregistration events")
    @DisplayName("Should handle engine lifecycle events")
    void testEngineLifecycleEvents() {
        fail("TODO: Implement engine lifecycle event test");
        // Detailed test plan:
        // 1. Setup initial state:
        //    - Mock getRegisteredEngines() to return empty initially
        //    - Create event stream observer with engine filter
        //    - Use CountDownLatch(2) for registration and unregistration
        // 2. Simulate engine registration:
        //    - Update mock to return new EngineInfo("engine-1", 10, 0, now, true)
        //    - Trigger the Flux to emit the new engine
        // 3. Verify ENGINE_REGISTERED event:
        //    - Event has correct engine ID
        //    - Previous partitions is empty
        //    - New partitions is empty (no slots yet)
        //    - Timestamp is recent (within 1 second)
        // 4. Simulate engine getting slots:
        //    - Mock watchAssignments() for new engine
        //    - Emit SlotAssignment with 3 partitions
        //    - Verify SLOTS_ACQUIRED event
        // 5. Simulate engine unregistration:
        //    - Update mock to remove engine from list
        //    - Emit empty SlotAssignment
        //    - Stop heartbeats
        // 6. Verify ENGINE_UNREGISTERED event:
        //    - Correct engine ID
        //    - Previous partitions shows what it had
        //    - New partitions is empty
        // 7. Verify Kafka publishing:
        //    - Use ArgumentCaptor to capture all events
        //    - Verify publishEvent() called 3 times
        //    - Check event order and content
        // 8. Edge cases:
        //    - Engine re-registration (same ID)
        //    - Multiple engines registering simultaneously
        //    - Registration with immediate slot assignment
        //    - Unregistration during rebalancing
    }
    
    @Test
    @Disabled("TODO: Test concurrent streams and proper cleanup")
    @DisplayName("Should handle multiple concurrent event streams")
    void testConcurrentEventStreams() {
        fail("TODO: Implement concurrent streams test");
        // Detailed test plan:
        // 1. Create multiple event stream observers:
        //    - Stream A: No filter (all engines)
        //    - Stream B: Filter for engine-1 only
        //    - Stream C: Filter for engine-2 and engine-3
        //    - Each with its own CountDownLatch and event collector
        // 2. Setup mock slot manager:
        //    - 3 engines registered
        //    - Each engine has different partition counts
        //    - Mock watchAssignments() for each engine
        // 3. Trigger various rebalancing events:
        //    - Engine-1: gains 2 partitions
        //    - Engine-2: loses 1 partition
        //    - Engine-3: rebalances (loses 2, gains 3)
        // 4. Verify event distribution:
        //    - Stream A receives all 3 events
        //    - Stream B receives only engine-1 event
        //    - Stream C receives engine-2 and engine-3 events
        //    - Verify event content matches expected
        // 5. Test stream error handling:
        //    - Force error in Stream B (throw in onNext)
        //    - Verify Stream B is removed from active streams
        //    - Verify Streams A and C continue receiving events
        // 6. Test graceful stream closure:
        //    - Call onCompleted() on Stream C
        //    - Trigger more events
        //    - Verify only Stream A receives new events
        // 7. Memory leak prevention:
        //    - Check internal maps for proper cleanup
        //    - Verify no references to closed streams
        //    - Test with 100 streams opening/closing rapidly
        // 8. Edge cases:
        //    - Stream closes during event broadcast
        //    - Same client opens multiple streams
        //    - Stream with invalid engine filter
        //    - Slow consumer (blocks in onNext)
    }
    
    @Test
    @Disabled("TODO: Test heartbeat expiration detection")
    @DisplayName("Should detect and report heartbeat expiration")
    void testHeartbeatExpiration() {
        fail("TODO: Implement heartbeat expiration test");
        // Extremely detailed test plan:
        // 
        // 1. Setup initial state with 3 engines:
        //    - Engine-1: 5 slots, healthy heartbeats
        //    - Engine-2: 3 slots, will expire
        //    - Engine-3: 4 slots, healthy heartbeats
        //    - Mock getRegisteredEngines() to return all 3
        //    - Mock watchAssignments() for each engine
        //    - Use CountDownLatch(2) for expiration events
        //
        // 2. Start event stream for all engines:
        //    - StreamRebalancingRequest with empty filter
        //    - Collect all events in ConcurrentLinkedQueue
        //    - Verify initial SLOTS_ACQUIRED events for each
        //
        // 3. Simulate normal heartbeat flow:
        //    - Update Engine-1 and Engine-3 heartbeats every 2s
        //    - Mock getRegisteredEngines() to emit updated EngineInfo
        //    - EngineInfo should have updated lastHeartbeat timestamps
        //    - Verify no expiration events yet
        //
        // 4. Stop heartbeats for Engine-2:
        //    - Stop updating Engine-2's lastHeartbeat
        //    - Continue updating Engine-1 and Engine-3
        //    - Wait for heartbeat timeout (e.g., 10 seconds)
        //    
        // 5. Simulate heartbeat expiration detection:
        //    - Update Engine-2's EngineInfo with active=false
        //    - Or simulate time passing beyond timeout
        //    - Mock should return stale lastHeartbeat for Engine-2
        //
        // 6. Verify HEARTBEAT_EXPIRED event:
        //    - Event should have engine_id = "engine-2"
        //    - EventType = HEARTBEAT_EXPIRED
        //    - previous_partitions = [0,1,2] (what it had)
        //    - new_partitions = [] (loses all)
        //    - metadata["timeout_seconds"] = "10"
        //    - metadata["last_heartbeat"] = ISO timestamp
        //
        // 7. Verify slot redistribution:
        //    - Engine-2's slots should be marked available
        //    - Mock rebalanceSlots() to redistribute
        //    - Engine-1 gets 2 more slots (now has 7)
        //    - Engine-3 gets 1 more slot (now has 5)
        //    - Verify SLOTS_ACQUIRED events for redistribution
        //
        // 8. Test edge cases:
        //    a) Multiple engines expire simultaneously:
        //       - Stop heartbeats for Engine-1 and Engine-2
        //       - Should get 2 HEARTBEAT_EXPIRED events
        //       - All slots go to Engine-3
        //    b) Engine recovers before redistribution:
        //       - Resume heartbeats just before timeout
        //       - Should NOT generate expiration event
        //    c) Expired engine tries to heartbeat:
        //       - After expiration, Engine-2 sends heartbeat
        //       - Should be rejected (slots already gone)
        //       - May generate ENGINE_REGISTERED event
        //    d) Network partition scenario:
        //       - Engine still running but can't reach Consul
        //       - Other engines see it as expired
        //       - When partition heals, conflict resolution
        //
        // 9. Verify Kafka event publishing:
        //    - Use ArgumentCaptor for all events
        //    - Verify publishEvent() called for each
        //    - Check event ordering and timestamps
        //    - Ensure no duplicate events
        //
        // 10. Memory and resource cleanup:
        //     - Verify expired engine removed from maps
        //     - Check watcher disposable is cancelled
        //     - No lingering references to expired engine
        //
        // Challenges from original architecture:
        // - Heartbeat timing was tricky to test reliably
        // - Race conditions between expiration and recovery
        // - Ensuring deterministic test behavior
        // - Mocking time progression accurately
    }
    
    @Test
    @Disabled("TODO: Test error handling in streams")
    @DisplayName("Should handle errors gracefully in streams")
    void testStreamErrorHandling() {
        fail("TODO: Implement stream error handling test");
        // Comprehensive error handling test plan:
        //
        // 1. Setup multiple concurrent streams:
        //    - Stream A: Event stream for all engines
        //    - Stream B: Metrics stream with 2s interval
        //    - Stream C: Event stream for engine-1 only
        //    - Each with its own error collector
        //    - Use CountDownLatch for coordination
        //
        // 2. Test slot manager failures:
        //    a) watchAssignments() throws exception:
        //       - Mock to throw ReactorException after 3 emissions
        //       - Verify affected stream gets onError
        //       - Check error message propagated correctly
        //       - Other streams should continue
        //    b) getRegisteredEngines() returns error Flux:
        //       - Return Flux.error(new ConsulException())
        //       - All event streams should handle gracefully
        //       - Log errors but don't crash
        //    c) getSlotDistribution() times out:
        //       - Return Mono.never() or delayed Mono
        //       - Distribution request should timeout (5s)
        //       - Client should receive timeout error
        //
        // 3. Test stream observer failures:
        //    a) Observer throws in onNext():
        //       - Create observer that throws after 2 events
        //       - Verify stream is removed from active list
        //       - Check no memory leak (observer released)
        //       - Other streams unaffected
        //    b) Observer becomes unresponsive:
        //       - Mock observer blocks in onNext()
        //       - Test backpressure handling
        //       - Verify doesn't block other streams
        //       - May need timeout or circuit breaker
        //
        // 4. Test Kafka publisher failures:
        //    - Mock eventPublisher.publishEvent() to throw
        //    - Should log error but not fail stream
        //    - Events still sent to gRPC observers
        //    - Track failed publishes in metrics
        //
        // 5. Test resource exhaustion:
        //    a) Too many concurrent streams:
        //       - Open 1000 event streams rapidly
        //       - Verify graceful degradation
        //       - New streams rejected with clear error
        //       - Existing streams continue
        //    b) Memory pressure:
        //       - Generate events faster than consumed
        //       - Test bounded buffers work correctly
        //       - Old events dropped if necessary
        //       - Monitor GC pressure
        //
        // 6. Test recovery scenarios:
        //    a) Transient Consul failure:
        //       - Fail 3 calls, then succeed
        //       - Streams should retry and recover
        //       - No duplicate events sent
        //    b) Kafka broker down:
        //       - Publisher fails continuously
        //       - gRPC streams still work
        //       - Events queued or dropped?
        //       - Recovery when Kafka returns
        //
        // 7. Test cascading failures:
        //    - Error in one component triggers others
        //    - E.g., slot manager error -> event generation fails
        //    - Verify circuit breakers prevent cascade
        //    - System degrades gracefully, not catastrophically
        //
        // 8. Verify error metrics and logging:
        //    - Each error type increments counter
        //    - Errors logged with context
        //    - Stack traces for unexpected errors
        //    - Rate limiting on error logs
        //
        // 9. Test cleanup after errors:
        //    - Force errors in all streams
        //    - Wait for all to fail/complete
        //    - Verify all resources released:
        //      * Stream maps empty
        //      * Watchers disposed
        //      * No lingering subscriptions
        //      * Thread pools cleaned up
        //
        // 10. Edge cases:
        //     - Error during stream initialization
        //     - Error in cleanup code itself
        //     - Concurrent errors in multiple components
        //     - Error after stream marked complete
        //     - Very slow errors (e.g., network timeout)
        //
        // Challenges from original implementation:
        // - Reactor error handling was complex
        // - Ensuring errors don't leak between streams
        // - Proper resource cleanup on all error paths
        // - Testing timeout scenarios reliably
        // - Avoiding error loops
    }
    
    @Test
    @Disabled("TODO: Test event history limits and cleanup")
    @DisplayName("Should respect max events limit in history")
    void testEventHistoryLimits() {
        fail("TODO: Implement event history limits test");
        // Detailed test plan for event history management:
        //
        // 1. Configure and verify max events limit:
        //    - Set maxEventsToKeep = 100 via property
        //    - Verify initial recentEvents list is empty
        //    - Check list is synchronized properly
        //
        // 2. Generate events exceeding the limit:
        //    - Create 150 different events rapidly:
        //      * 50 ENGINE_REGISTERED events
        //      * 40 SLOTS_ACQUIRED events  
        //      * 30 REBALANCE_TRIGGERED events
        //      * 20 SLOTS_RELEASED events
        //      * 10 ENGINE_UNREGISTERED events
        //    - Each with unique timestamps (1 sec apart)
        //    - Different engine IDs (engine-1 to engine-10)
        //    - Track first 50 event IDs for verification
        //
        // 3. Verify FIFO eviction policy:
        //    - Check recentEvents.size() == 100
        //    - First 50 events should be gone
        //    - Last 100 events should be present
        //    - Verify by checking event IDs
        //    - Ensure events maintain chronological order
        //
        // 4. Test history request with no filters:
        //    - Request: limit=50, no other filters
        //    - Should get most recent 50 events
        //    - Verify they match expected event IDs
        //    - Check hasMore=true (more than limit)
        //    - Verify summary stats are accurate
        //
        // 5. Test time-based filtering:
        //    a) Start time only:
        //       - Set start_time to middle of event range
        //       - Should get events after that time
        //       - Verify no events before start_time
        //    b) End time only:
        //       - Set end_time to 75% through range
        //       - Should get events before that time
        //    c) Time range:
        //       - Set both start and end time
        //       - Should get events within window
        //       - Test empty range (no events)
        //    d) Future times:
        //       - Request events from future
        //       - Should return empty list
        //
        // 6. Test engine ID filtering:
        //    - Filter for ["engine-2", "engine-5", "engine-8"]
        //    - Should only get events for those engines
        //    - Verify other engines excluded
        //    - Test non-existent engine ID
        //    - Test empty engine list (should get all)
        //
        // 7. Test event type filtering:
        //    - Filter for [SLOTS_ACQUIRED, SLOTS_RELEASED]
        //    - Should exclude other event types
        //    - Verify counts match expected
        //    - Test single event type filter
        //    - Test all event types (same as no filter)
        //
        // 8. Test combined filters:
        //    - Time range + engine IDs + event types
        //    - E.g., engine-3 REBALANCE events in last hour
        //    - Verify all filters applied correctly
        //    - Test filters that yield no results
        //
        // 9. Test limit parameter:
        //    - limit=0 should return all matching
        //    - limit=1 should return just one
        //    - limit > total should return all
        //    - Negative limit should error or default
        //
        // 10. Test summary statistics:
        //     - Verify total_events count
        //     - Check events_by_type map accuracy
        //     - Validate top_engines list:
        //       * Sorted by event count descending
        //       * Limited to top 5 engines
        //       * Correct counts per engine
        //     - Peak periods calculation (if implemented)
        //
        // 11. Concurrent event generation and queries:
        //     - Start thread generating events continuously
        //     - Query history from multiple threads
        //     - Verify no ConcurrentModificationException
        //     - Results should be consistent snapshots
        //     - No events lost or duplicated
        //
        // 12. Memory efficiency:
        //     - Generate 10,000 events (limit still 100)
        //     - Verify memory usage stays bounded
        //     - Check GC behavior is reasonable
        //     - No memory leaks from old events
        //
        // 13. Event ordering guarantees:
        //     - Events with same timestamp
        //     - Verify secondary ordering (by event ID?)
        //     - Ensure deterministic results
        //
        // 14. Edge cases:
        //     - Empty history (no events yet)
        //     - Exactly at limit (100 events)
        //     - All events same type/engine
        //     - Rapid event generation (microsecond gaps)
        //     - Clock skew (events out of order)
        //
        // Challenges from original implementation:
        // - Thread safety of event list operations
        // - Efficient filtering of large event lists
        // - Memory pressure from event accumulation
        // - Ensuring consistent snapshots during queries
        // - Balancing history size vs memory usage
    }
    
    @MockBean(KafkaSlotManager.class)
    KafkaSlotManager kafkaSlotManager() {
        return mockSlotManager;
    }
    
    @MockBean(KafkaSlotMonitoringServiceImpl.RebalancingEventPublisher.class)
    KafkaSlotMonitoringServiceImpl.RebalancingEventPublisher eventPublisher() {
        return mockEventPublisher;
    }
}