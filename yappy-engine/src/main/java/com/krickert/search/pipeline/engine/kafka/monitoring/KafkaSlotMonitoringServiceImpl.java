package com.krickert.search.pipeline.engine.kafka.monitoring;

import com.krickert.search.model.*;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.grpc.annotation.GrpcService;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * gRPC service implementation for Kafka slot monitoring.
 * This service provides real-time streaming of rebalancing events and metrics.
 * 
 * Events are also published to Kafka for durability and replay capability.
 */
@GrpcService
@Singleton
@Requires(property = "app.kafka.slot-management.enabled", value = "true")
public class KafkaSlotMonitoringServiceImpl extends KafkaSlotMonitoringServiceGrpc.KafkaSlotMonitoringServiceImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSlotMonitoringServiceImpl.class);
    
    private final KafkaSlotManager slotManager;
    private final RebalancingEventPublisher eventPublisher;
    private final String engineInstanceId;
    
    // Event tracking
    private final AtomicLong eventCounter = new AtomicLong();
    private final List<RebalancingEvent> recentEvents = Collections.synchronizedList(new ArrayList<>());
    private final int maxEventsToKeep;
    
    // Active streams
    private final Map<String, StreamObserver<RebalancingEvent>> eventStreams = new ConcurrentHashMap<>();
    private final Map<String, StreamObserver<SlotMetrics>> metricsStreams = new ConcurrentHashMap<>();
    
    // Engine monitoring
    private final Map<String, EngineMonitoringState> engineStates = new ConcurrentHashMap<>();
    private final Map<String, Disposable> engineWatchers = new ConcurrentHashMap<>();
    
    @Inject
    public KafkaSlotMonitoringServiceImpl(
            KafkaSlotManager slotManager,
            RebalancingEventPublisher eventPublisher,
            @Value("${app.engine.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") String engineInstanceId,
            @Value("${app.kafka.monitoring.max-events:1000}") int maxEventsToKeep) {
        this.slotManager = slotManager;
        this.eventPublisher = eventPublisher;
        this.engineInstanceId = engineInstanceId;
        this.maxEventsToKeep = maxEventsToKeep;
        
        // Start monitoring all engines
        startGlobalMonitoring();
    }
    
    @Override
    public void streamRebalancingEvents(StreamRebalancingRequest request, 
                                      StreamObserver<RebalancingEvent> responseObserver) {
        String streamId = UUID.randomUUID().toString();
        LOG.info("Starting rebalancing event stream: {}", streamId);
        
        eventStreams.put(streamId, responseObserver);
        
        // Send initial state if requested
        if (request.getIncludeInitialState()) {
            sendInitialState(responseObserver, request.getEngineIdsList());
        }
        
        // Start watching for specified engines
        Set<String> engineFilter = new HashSet<>(request.getEngineIdsList());
        if (engineFilter.isEmpty()) {
            // Watch all engines
            startWatchingAllEngines();
        } else {
            // Watch specific engines
            engineFilter.forEach(this::startWatchingEngine);
        }
        
        // Clean up on stream completion
        responseObserver = new StreamObserverWrapper<>(responseObserver, () -> {
            eventStreams.remove(streamId);
            LOG.info("Rebalancing event stream closed: {}", streamId);
        });
    }
    
    @Override
    public void getSlotDistribution(Empty request, StreamObserver<SlotDistributionSnapshot> responseObserver) {
        try {
            SlotDistributionSnapshot snapshot = buildDistributionSnapshot().block(Duration.ofSeconds(5));
            responseObserver.onNext(snapshot);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Error getting slot distribution", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void streamMetrics(StreamMetricsRequest request, StreamObserver<SlotMetrics> responseObserver) {
        String streamId = UUID.randomUUID().toString();
        LOG.info("Starting metrics stream: {} with interval {}s", streamId, request.getIntervalSeconds());
        
        metricsStreams.put(streamId, responseObserver);
        
        // Schedule periodic metrics updates
        Flux.interval(Duration.ofSeconds(request.getIntervalSeconds()))
                .flatMap(tick -> buildMetrics(request))
                .subscribe(
                        metrics -> {
                            StreamObserver<SlotMetrics> stream = metricsStreams.get(streamId);
                            if (stream != null) {
                                stream.onNext(metrics);
                            }
                        },
                        error -> {
                            LOG.error("Error in metrics stream", error);
                            StreamObserver<SlotMetrics> stream = metricsStreams.remove(streamId);
                            if (stream != null) {
                                stream.onError(error);
                            }
                        }
                );
        
        // Clean up on stream completion
        responseObserver = new StreamObserverWrapper<>(responseObserver, () -> {
            metricsStreams.remove(streamId);
            LOG.info("Metrics stream closed: {}", streamId);
        });
    }
    
    @Override
    public void getRebalancingHistory(RebalancingHistoryRequest request, 
                                    StreamObserver<RebalancingHistoryResponse> responseObserver) {
        try {
            List<RebalancingEvent> filteredEvents = filterHistoricalEvents(request);
            
            RebalancingHistoryResponse response = RebalancingHistoryResponse.newBuilder()
                    .addAllEvents(filteredEvents)
                    .setHasMore(filteredEvents.size() >= request.getLimit())
                    .setSummary(buildHistorySummary(filteredEvents))
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Error getting rebalancing history", e);
            responseObserver.onError(e);
        }
    }
    
    private void startGlobalMonitoring() {
        // Monitor all engine registrations/unregistrations
        slotManager.getRegisteredEngines()
                .doOnNext(engineInfo -> {
                    String engineId = engineInfo.engineId();
                    if (!engineStates.containsKey(engineId)) {
                        handleEngineRegistered(engineId);
                    }
                    
                    // Update engine state
                    EngineMonitoringState state = engineStates.computeIfAbsent(engineId, 
                            id -> new EngineMonitoringState(id));
                    state.updateFromInfo(engineInfo);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        engineInfo -> LOG.debug("Engine info updated: {}", engineInfo.engineId()),
                        error -> LOG.error("Error monitoring engines", error)
                );
    }
    
    private void startWatchingEngine(String engineId) {
        if (engineWatchers.containsKey(engineId)) {
            return; // Already watching
        }
        
        LOG.info("Starting to watch engine: {}", engineId);
        
        Disposable watcher = slotManager.watchAssignments(engineId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        assignment -> handleAssignmentChange(engineId, assignment),
                        error -> LOG.error("Error watching engine {}", engineId, error),
                        () -> LOG.info("Watcher completed for engine {}", engineId)
                );
        
        engineWatchers.put(engineId, watcher);
    }
    
    private void startWatchingAllEngines() {
        slotManager.getRegisteredEngines()
                .map(KafkaSlotManager.EngineInfo::engineId)
                .subscribe(this::startWatchingEngine);
    }
    
    private void handleEngineRegistered(String engineId) {
        RebalancingEvent event = createEvent(
                engineId,
                RebalancingEvent.EventType.ENGINE_REGISTERED,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
        
        broadcastEvent(event);
        startWatchingEngine(engineId);
    }
    
    private void handleAssignmentChange(String engineId, SlotAssignment assignment) {
        EngineMonitoringState state = engineStates.computeIfAbsent(engineId, 
                id -> new EngineMonitoringState(id));
        
        List<Integer> previousPartitions = new ArrayList<>(state.currentPartitions);
        List<Integer> newPartitions = assignment.assignedSlots().stream()
                .map(KafkaSlot::getPartition)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        if (!previousPartitions.equals(newPartitions)) {
            RebalancingEvent.EventType eventType = determineEventType(previousPartitions, newPartitions);
            
            String topic = assignment.assignedSlots().isEmpty() ? null : 
                    assignment.assignedSlots().get(0).getTopic();
            String groupId = assignment.assignedSlots().isEmpty() ? null : 
                    assignment.assignedSlots().get(0).getGroupId();
            
            RebalancingEvent event = createEvent(
                    engineId,
                    eventType,
                    previousPartitions,
                    newPartitions,
                    topic,
                    groupId
            );
            
            broadcastEvent(event);
            state.currentPartitions = new HashSet<>(newPartitions);
            state.lastUpdate = Instant.now();
        }
    }
    
    private RebalancingEvent.EventType determineEventType(List<Integer> previous, List<Integer> current) {
        if (previous.isEmpty() && !current.isEmpty()) {
            return RebalancingEvent.EventType.SLOTS_ACQUIRED;
        } else if (!previous.isEmpty() && current.isEmpty()) {
            return RebalancingEvent.EventType.SLOTS_RELEASED;
        } else {
            return RebalancingEvent.EventType.REBALANCE_TRIGGERED;
        }
    }
    
    private RebalancingEvent createEvent(String engineId, RebalancingEvent.EventType type,
                                       List<Integer> previousPartitions, List<Integer> newPartitions,
                                       String topic, String groupId) {
        RebalancingEvent.Builder builder = RebalancingEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEngineId(engineId)
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .setEventType(type)
                .addAllPreviousPartitions(previousPartitions)
                .addAllNewPartitions(newPartitions);
        
        if (topic != null) {
            builder.setTopic(topic);
        }
        if (groupId != null) {
            builder.setGroupId(groupId);
        }
        
        return builder.build();
    }
    
    private void broadcastEvent(RebalancingEvent event) {
        // Store event
        recentEvents.add(event);
        if (recentEvents.size() > maxEventsToKeep) {
            recentEvents.remove(0);
        }
        
        // Publish to Kafka for durability
        eventPublisher.publishEvent(event.getEngineId(), event);
        
        // Broadcast to active streams
        eventStreams.values().forEach(stream -> {
            try {
                stream.onNext(event);
            } catch (Exception e) {
                LOG.error("Error sending event to stream", e);
            }
        });
    }
    
    private void sendInitialState(StreamObserver<RebalancingEvent> stream, List<String> engineFilter) {
        // Send synthetic events representing current state
        engineStates.values().stream()
                .filter(state -> engineFilter.isEmpty() || engineFilter.contains(state.engineId))
                .forEach(state -> {
                    if (!state.currentPartitions.isEmpty()) {
                        RebalancingEvent event = createEvent(
                                state.engineId,
                                RebalancingEvent.EventType.SLOTS_ACQUIRED,
                                Collections.emptyList(),
                                new ArrayList<>(state.currentPartitions),
                                null,
                                null
                        );
                        stream.onNext(event);
                    }
                });
    }
    
    private Mono<SlotDistributionSnapshot> buildDistributionSnapshot() {
        return slotManager.getSlotDistribution()
                .map(distribution -> {
                    // Calculate statistics
                    DistributionStats stats = calculateDistributionStats(distribution);
                    
                    // Build engine distributions
                    Map<String, EngineSlots> engineDistributions = new HashMap<>();
                    engineStates.forEach((engineId, state) -> {
                        engineDistributions.put(engineId, EngineSlots.newBuilder()
                                .setEngineId(engineId)
                                .setSlotCount(distribution.getOrDefault(engineId, 0))
                                .setLastHeartbeat(Timestamp.newBuilder()
                                        .setSeconds(state.lastUpdate.getEpochSecond())
                                        .build())
                                .setActive(state.isActive())
                                .build());
                    });
                    
                    return SlotDistributionSnapshot.newBuilder()
                            .setTimestamp(Timestamp.newBuilder()
                                    .setSeconds(Instant.now().getEpochSecond())
                                    .build())
                            .setTotalPartitions(distribution.values().stream()
                                    .mapToInt(Integer::intValue).sum())
                            .setActiveEngines(engineDistributions.size())
                            .putAllEngineDistributions(engineDistributions)
                            .setStats(stats)
                            .build();
                });
    }
    
    private DistributionStats calculateDistributionStats(Map<String, Integer> distribution) {
        if (distribution.isEmpty()) {
            return DistributionStats.getDefaultInstance();
        }
        
        List<Integer> counts = new ArrayList<>(distribution.values());
        double avg = counts.stream().mapToInt(Integer::intValue).average().orElse(0);
        int min = counts.stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
        
        double variance = counts.stream()
                .mapToDouble(count -> Math.pow(count - avg, 2))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);
        double imbalanceRatio = avg > 0 ? (max - min) / avg : 0;
        
        return DistributionStats.newBuilder()
                .setAveragePartitions(avg)
                .setStdDeviation(stdDev)
                .setImbalanceRatio(imbalanceRatio)
                .setMinPartitions(min)
                .setMaxPartitions(max)
                .build();
    }
    
    private Mono<SlotMetrics> buildMetrics(StreamMetricsRequest request) {
        // TODO: Implement comprehensive metrics collection
        return Mono.just(SlotMetrics.getDefaultInstance());
    }
    
    private List<RebalancingEvent> filterHistoricalEvents(RebalancingHistoryRequest request) {
        return recentEvents.stream()
                .filter(event -> {
                    // Filter by time range
                    long eventTime = event.getTimestamp().getSeconds();
                    if (request.hasStartTime() && eventTime < request.getStartTime().getSeconds()) {
                        return false;
                    }
                    if (request.hasEndTime() && eventTime > request.getEndTime().getSeconds()) {
                        return false;
                    }
                    
                    // Filter by engine IDs
                    if (!request.getEngineIdsList().isEmpty() && 
                        !request.getEngineIdsList().contains(event.getEngineId())) {
                        return false;
                    }
                    
                    // Filter by event types
                    if (!request.getEventTypesList().isEmpty() && 
                        !request.getEventTypesList().contains(event.getEventType())) {
                        return false;
                    }
                    
                    return true;
                })
                .limit(request.getLimit() > 0 ? request.getLimit() : Integer.MAX_VALUE)
                .collect(Collectors.toList());
    }
    
    private HistorySummary buildHistorySummary(List<RebalancingEvent> events) {
        Map<String, Integer> eventsByType = events.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEventType().name(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
        
        Map<String, Long> engineActivity = events.stream()
                .collect(Collectors.groupingBy(
                        RebalancingEvent::getEngineId,
                        Collectors.counting()
                ));
        
        List<EngineActivity> topEngines = engineActivity.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(entry -> EngineActivity.newBuilder()
                        .setEngineId(entry.getKey())
                        .setEventCount(entry.getValue().intValue())
                        .build())
                .collect(Collectors.toList());
        
        return HistorySummary.newBuilder()
                .setTotalEvents(events.size())
                .putAllEventsByType(eventsByType)
                .addAllTopEngines(topEngines)
                .build();
    }
    
    /**
     * Monitoring state for an engine
     */
    private static class EngineMonitoringState {
        final String engineId;
        Set<Integer> currentPartitions = new HashSet<>();
        Instant lastUpdate = Instant.now();
        int maxSlots = 0;
        boolean active = true;
        
        EngineMonitoringState(String engineId) {
            this.engineId = engineId;
        }
        
        void updateFromInfo(KafkaSlotManager.EngineInfo info) {
            this.maxSlots = info.maxSlots();
            this.active = info.active();
            this.lastUpdate = info.lastHeartbeat();
        }
        
        boolean isActive() {
            return active && Duration.between(lastUpdate, Instant.now()).toMinutes() < 5;
        }
    }
    
    /**
     * Wrapper for StreamObserver to handle cleanup
     */
    private static class StreamObserverWrapper<T> implements StreamObserver<T> {
        private final StreamObserver<T> delegate;
        private final Runnable onComplete;
        
        StreamObserverWrapper(StreamObserver<T> delegate, Runnable onComplete) {
            this.delegate = delegate;
            this.onComplete = onComplete;
        }
        
        @Override
        public void onNext(T value) {
            delegate.onNext(value);
        }
        
        @Override
        public void onError(Throwable t) {
            delegate.onError(t);
            onComplete.run();
        }
        
        @Override
        public void onCompleted() {
            delegate.onCompleted();
            onComplete.run();
        }
    }
    
    /**
     * Kafka client for publishing events
     */
    @KafkaClient
    public interface RebalancingEventPublisher {
        @Topic("kafka-slot-rebalancing-events")
        void publishEvent(@KafkaKey String engineId, RebalancingEvent event);
    }
}