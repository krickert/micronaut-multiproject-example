package com.krickert.search.pipeline.engine.kafka.visualization;

import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.websocket.WebSocketBroadcaster;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnClose;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Real-time visualization component for Kafka partition rebalancing.
 * Provides WebSocket endpoint for live updates of partition distribution.
 */
@Singleton
@ServerWebSocket("/ws/rebalancing/{engineId}")
@Requires(property = "app.kafka.visualization.enabled", value = "true", defaultValue = "false")
public class RebalancingVisualizer {
    
    private static final Logger LOG = LoggerFactory.getLogger(RebalancingVisualizer.class);
    
    private final KafkaSlotManager slotManager;
    private final WebSocketBroadcaster broadcaster;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Disposable> engineWatchers = new ConcurrentHashMap<>();
    private final Map<String, EngineMetrics> engineMetrics = new ConcurrentHashMap<>();
    private final AtomicLong eventCounter = new AtomicLong();
    private final List<RebalanceEvent> recentEvents = Collections.synchronizedList(new ArrayList<>());
    private final int maxEventsToKeep;
    
    @Inject
    public RebalancingVisualizer(
            KafkaSlotManager slotManager,
            WebSocketBroadcaster broadcaster,
            @Value("${app.kafka.visualization.max-events:1000}") int maxEventsToKeep) {
        this.slotManager = slotManager;
        this.broadcaster = broadcaster;
        this.maxEventsToKeep = maxEventsToKeep;
    }
    
    @OnOpen
    public void onOpen(String engineId, WebSocketSession session) {
        LOG.info("WebSocket opened for engine: {} from {}", engineId, session.getId());
        sessions.put(session.getId(), session);
        
        // Start watching this engine if not already
        if (!engineWatchers.containsKey(engineId)) {
            startWatchingEngine(engineId);
        }
        
        // Send initial state
        sendInitialState(engineId, session);
    }
    
    @OnMessage
    public void onMessage(String engineId, String message, WebSocketSession session) {
        LOG.debug("Received message from {}: {}", session.getId(), message);
        
        // Handle different message types
        if ("refresh".equals(message)) {
            sendCurrentState(engineId, session);
        } else if (message.startsWith("history:")) {
            int count = Integer.parseInt(message.substring(8));
            sendEventHistory(session, count);
        }
    }
    
    @OnClose
    public void onClose(String engineId, WebSocketSession session) {
        LOG.info("WebSocket closed for engine: {} from {}", engineId, session.getId());
        sessions.remove(session.getId());
        
        // Stop watching if no more sessions for this engine
        boolean hasOtherSessions = sessions.values().stream()
                .anyMatch(s -> s.getUriVariables().get("engineId", String.class, engineId).equals(engineId));
        
        if (!hasOtherSessions && engineWatchers.containsKey(engineId)) {
            stopWatchingEngine(engineId);
        }
    }
    
    private void startWatchingEngine(String engineId) {
        LOG.info("Starting to watch engine: {}", engineId);
        
        Disposable watcher = slotManager.watchAssignments(engineId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        assignment -> handleAssignmentChange(engineId, assignment),
                        error -> LOG.error("Error watching engine {}", engineId, error),
                        () -> LOG.info("Watcher completed for engine {}", engineId)
                );
        
        engineWatchers.put(engineId, watcher);
        engineMetrics.put(engineId, new EngineMetrics(engineId));
    }
    
    private void stopWatchingEngine(String engineId) {
        LOG.info("Stopping watch for engine: {}", engineId);
        
        Disposable watcher = engineWatchers.remove(engineId);
        if (watcher != null && !watcher.isDisposed()) {
            watcher.dispose();
        }
        
        engineMetrics.remove(engineId);
    }
    
    private void handleAssignmentChange(String engineId, SlotAssignment assignment) {
        EngineMetrics metrics = engineMetrics.get(engineId);
        if (metrics == null) {
            return;
        }
        
        // Calculate changes
        int previousCount = metrics.currentPartitionCount;
        int newCount = assignment.getSlotCount();
        
        // Update metrics
        metrics.currentPartitionCount = newCount;
        metrics.lastUpdateTime = Instant.now();
        metrics.updateCount++;
        
        if (newCount > previousCount) {
            metrics.partitionsGained += (newCount - previousCount);
        } else if (newCount < previousCount) {
            metrics.partitionsLost += (previousCount - newCount);
        }
        
        // Create event
        RebalanceEvent event = new RebalanceEvent(
                eventCounter.incrementAndGet(),
                engineId,
                Instant.now(),
                previousCount,
                newCount,
                assignment.getSlotsByTopic()
        );
        
        // Store event
        recentEvents.add(event);
        if (recentEvents.size() > maxEventsToKeep) {
            recentEvents.remove(0);
        }
        
        // Broadcast update
        broadcastUpdate(engineId, event, metrics);
    }
    
    private void broadcastUpdate(String engineId, RebalanceEvent event, EngineMetrics metrics) {
        Map<String, Object> update = new HashMap<>();
        update.put("type", "rebalance");
        update.put("engineId", engineId);
        update.put("event", event.toMap());
        update.put("metrics", metrics.toMap());
        update.put("timestamp", Instant.now().toString());
        
        // Get all engines' current state
        Map<String, Object> globalState = getGlobalState();
        update.put("globalState", globalState);
        
        broadcaster.broadcastSync(update);
    }
    
    private void sendInitialState(String engineId, WebSocketSession session) {
        Map<String, Object> state = new HashMap<>();
        state.put("type", "initial");
        state.put("engineId", engineId);
        state.put("globalState", getGlobalState());
        state.put("recentEvents", getRecentEventsForEngine(engineId, 50));
        state.put("timestamp", Instant.now().toString());
        
        session.sendSync(state);
    }
    
    private void sendCurrentState(String engineId, WebSocketSession session) {
        Map<String, Object> state = new HashMap<>();
        state.put("type", "refresh");
        state.put("engineId", engineId);
        state.put("globalState", getGlobalState());
        state.put("metrics", engineMetrics.get(engineId) != null ? 
                engineMetrics.get(engineId).toMap() : Collections.emptyMap());
        state.put("timestamp", Instant.now().toString());
        
        session.sendSync(state);
    }
    
    private void sendEventHistory(WebSocketSession session, int count) {
        List<Map<String, Object>> events = recentEvents.stream()
                .skip(Math.max(0, recentEvents.size() - count))
                .map(RebalanceEvent::toMap)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("type", "history");
        response.put("events", events);
        response.put("timestamp", Instant.now().toString());
        
        session.sendSync(response);
    }
    
    private Map<String, Object> getGlobalState() {
        Map<String, Object> global = new HashMap<>();
        
        // Get all engines' partition assignments
        Map<String, Integer> enginePartitions = new HashMap<>();
        int totalPartitions = 0;
        
        for (Map.Entry<String, EngineMetrics> entry : engineMetrics.entrySet()) {
            int count = entry.getValue().currentPartitionCount;
            enginePartitions.put(entry.getKey(), count);
            totalPartitions += count;
        }
        
        global.put("engines", enginePartitions);
        global.put("totalPartitions", totalPartitions);
        global.put("engineCount", engineMetrics.size());
        global.put("averagePartitionsPerEngine", 
                engineMetrics.isEmpty() ? 0 : totalPartitions / engineMetrics.size());
        
        // Calculate distribution variance
        if (!engineMetrics.isEmpty()) {
            double avg = (double) totalPartitions / engineMetrics.size();
            double variance = engineMetrics.values().stream()
                    .mapToDouble(m -> Math.pow(m.currentPartitionCount - avg, 2))
                    .average()
                    .orElse(0.0);
            global.put("distributionVariance", variance);
            global.put("distributionStdDev", Math.sqrt(variance));
        }
        
        return global;
    }
    
    private List<Map<String, Object>> getRecentEventsForEngine(String engineId, int limit) {
        return recentEvents.stream()
                .filter(e -> e.engineId.equals(engineId))
                .skip(Math.max(0, recentEvents.stream()
                        .filter(e -> e.engineId.equals(engineId))
                        .count() - limit))
                .map(RebalanceEvent::toMap)
                .collect(Collectors.toList());
    }
    
    /**
     * Scheduled task to broadcast periodic metrics
     */
    @Scheduled(fixedDelay = "5s")
    public void broadcastPeriodicMetrics() {
        if (sessions.isEmpty()) {
            return;
        }
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("type", "metrics");
        metrics.put("globalState", getGlobalState());
        metrics.put("engineMetrics", engineMetrics.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().toMap()
                )));
        metrics.put("timestamp", Instant.now().toString());
        
        broadcaster.broadcastSync(metrics);
    }
    
    /**
     * Get visualization dashboard URL
     */
    public String getDashboardUrl(String engineId) {
        return "/visualization/rebalancing/" + engineId;
    }
    
    // Inner classes
    
    static class EngineMetrics {
        final String engineId;
        int currentPartitionCount = 0;
        int partitionsGained = 0;
        int partitionsLost = 0;
        int updateCount = 0;
        Instant startTime = Instant.now();
        Instant lastUpdateTime = Instant.now();
        
        EngineMetrics(String engineId) {
            this.engineId = engineId;
        }
        
        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("engineId", engineId);
            map.put("currentPartitionCount", currentPartitionCount);
            map.put("partitionsGained", partitionsGained);
            map.put("partitionsLost", partitionsLost);
            map.put("updateCount", updateCount);
            map.put("uptimeSeconds", 
                    java.time.Duration.between(startTime, Instant.now()).getSeconds());
            map.put("lastUpdateTime", lastUpdateTime.toString());
            return map;
        }
    }
    
    static class RebalanceEvent {
        final long eventId;
        final String engineId;
        final Instant timestamp;
        final int previousPartitions;
        final int newPartitions;
        final Map<String, List<Integer>> partitionsByTopic;
        
        RebalanceEvent(long eventId, String engineId, Instant timestamp, 
                      int previousPartitions, int newPartitions,
                      Map<String, List<com.krickert.yappy.kafka.slot.model.KafkaSlot>> slotsByTopic) {
            this.eventId = eventId;
            this.engineId = engineId;
            this.timestamp = timestamp;
            this.previousPartitions = previousPartitions;
            this.newPartitions = newPartitions;
            this.partitionsByTopic = slotsByTopic.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream()
                                    .map(slot -> slot.getPartition())
                                    .sorted()
                                    .collect(Collectors.toList())
                    ));
        }
        
        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("eventId", eventId);
            map.put("engineId", engineId);
            map.put("timestamp", timestamp.toString());
            map.put("previousPartitions", previousPartitions);
            map.put("newPartitions", newPartitions);
            map.put("change", newPartitions - previousPartitions);
            map.put("changeType", newPartitions > previousPartitions ? "GAIN" : 
                    newPartitions < previousPartitions ? "LOSS" : "NONE");
            map.put("partitionsByTopic", partitionsByTopic);
            return map;
        }
    }
}