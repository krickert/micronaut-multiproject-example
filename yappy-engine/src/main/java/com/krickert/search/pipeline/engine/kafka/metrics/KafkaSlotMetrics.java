package com.krickert.search.pipeline.engine.kafka.metrics;

import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micrometer.core.instrument.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for Kafka slot management and rebalancing.
 * Exposes metrics that can be consumed by Prometheus, Grafana, etc.
 */
@Singleton
@Requires(property = "app.kafka.metrics.enabled", value = "true", defaultValue = "true")
public class KafkaSlotMetrics {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSlotMetrics.class);
    
    private final MeterRegistry meterRegistry;
    private final KafkaSlotManager slotManager;
    private final String engineInstanceId;
    
    // Gauges
    private final AtomicInteger totalPartitions = new AtomicInteger(0);
    private final AtomicInteger activeEngines = new AtomicInteger(0);
    private final AtomicInteger myPartitionCount = new AtomicInteger(0);
    
    // Counters
    private Counter rebalanceEvents;
    private Counter partitionsGained;
    private Counter partitionsLost;
    private Counter slotAcquisitions;
    private Counter slotReleases;
    
    // Timers
    private Timer rebalanceDuration;
    private Timer slotAcquisitionTime;
    
    // Distribution Summary
    private DistributionSummary partitionDistribution;
    
    // Per-engine metrics
    private final Map<String, EngineGauges> engineGauges = new ConcurrentHashMap<>();
    private Disposable slotWatcher;
    private Instant lastRebalanceTime = Instant.now();
    
    @Inject
    public KafkaSlotMetrics(
            MeterRegistry meterRegistry,
            KafkaSlotManager slotManager,
            @Value("${app.engine.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") String engineInstanceId) {
        this.meterRegistry = meterRegistry;
        this.slotManager = slotManager;
        this.engineInstanceId = engineInstanceId;
    }
    
    @PostConstruct
    void initialize() {
        // Register gauges
        Gauge.builder("kafka.slots.partitions.total", totalPartitions, AtomicInteger::get)
                .description("Total number of Kafka partitions managed by slot manager")
                .register(meterRegistry);
        
        Gauge.builder("kafka.slots.engines.active", activeEngines, AtomicInteger::get)
                .description("Number of active engines in the cluster")
                .register(meterRegistry);
        
        Gauge.builder("kafka.slots.partitions.assigned", myPartitionCount, AtomicInteger::get)
                .description("Number of partitions assigned to this engine")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        Gauge.builder("kafka.slots.rebalance.time_since_last", this, 
                metrics -> Duration.between(metrics.lastRebalanceTime, Instant.now()).getSeconds())
                .description("Seconds since last rebalance event")
                .baseUnit("seconds")
                .register(meterRegistry);
        
        // Register counters
        rebalanceEvents = Counter.builder("kafka.slots.rebalance.events")
                .description("Total number of rebalance events")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        partitionsGained = Counter.builder("kafka.slots.partitions.gained")
                .description("Total partitions gained through rebalancing")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        partitionsLost = Counter.builder("kafka.slots.partitions.lost")
                .description("Total partitions lost through rebalancing")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        slotAcquisitions = Counter.builder("kafka.slots.acquisitions")
                .description("Total slot acquisition attempts")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        slotReleases = Counter.builder("kafka.slots.releases")
                .description("Total slot release operations")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        // Register timers
        rebalanceDuration = Timer.builder("kafka.slots.rebalance.duration")
                .description("Duration of rebalance operations")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        slotAcquisitionTime = Timer.builder("kafka.slots.acquisition.time")
                .description("Time taken to acquire slots")
                .tag("engine", engineInstanceId)
                .register(meterRegistry);
        
        // Register distribution summary
        partitionDistribution = DistributionSummary.builder("kafka.slots.partition.distribution")
                .description("Distribution of partitions across engines")
                .baseUnit("partitions")
                .register(meterRegistry);
        
        // Start watching slot assignments
        startWatchingSlots();
        
        LOG.info("Kafka slot metrics initialized for engine: {}", engineInstanceId);
    }
    
    private void startWatchingSlots() {
        slotWatcher = slotManager.watchAssignments(engineInstanceId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        this::handleAssignmentChange,
                        error -> LOG.error("Error watching slot assignments", error),
                        () -> LOG.info("Slot watcher completed")
                );
    }
    
    private void handleAssignmentChange(SlotAssignment assignment) {
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            int previousCount = myPartitionCount.get();
            int newCount = assignment.getSlotCount();
            
            // Update partition count
            myPartitionCount.set(newCount);
            
            // Track changes
            if (newCount != previousCount) {
                rebalanceEvents.increment();
                lastRebalanceTime = Instant.now();
                
                if (newCount > previousCount) {
                    partitionsGained.increment(newCount - previousCount);
                } else {
                    partitionsLost.increment(previousCount - newCount);
                }
                
                LOG.info("Partition assignment changed: {} -> {} partitions", previousCount, newCount);
            }
            
            // Update per-topic metrics
            updateTopicMetrics(assignment);
            
        } finally {
            sample.stop(rebalanceDuration);
        }
    }
    
    private void updateTopicMetrics(SlotAssignment assignment) {
        assignment.getSlotsByTopic().forEach((topic, slots) -> {
            // Record topic-specific partition count
            meterRegistry.gauge("kafka.slots.topic.partitions",
                    Tags.of("engine", engineInstanceId, "topic", topic),
                    slots.size());
            
            // Record partition IDs for detailed tracking
            slots.forEach(slot -> {
                meterRegistry.gauge("kafka.slots.partition.assigned",
                        Tags.of(
                                "engine", engineInstanceId,
                                "topic", topic,
                                "partition", String.valueOf(slot.getPartition()),
                                "group", slot.getGroupId()
                        ),
                        1);
            });
        });
    }
    
    /**
     * Scheduled task to collect cluster-wide metrics
     */
    @Scheduled(fixedDelay = "30s")
    public void collectClusterMetrics() {
        // TODO: Implement when KafkaSlotManager has these methods
        LOG.debug("TODO: Implement cluster metrics collection when KafkaSlotManager API is complete");
        
        // For now, just set some placeholder values
        activeEngines.set(1); // At least this engine
        totalPartitions.set(myPartitionCount.get());
    }
    
    private void calculateDistributionStats() {
        // TODO: Implement when KafkaSlotManager.getSlotDistribution() is available
    }
    
    /**
     * Record a slot acquisition operation
     */
    public void recordSlotAcquisition(String topic, String groupId, int requestedSlots, boolean success) {
        slotAcquisitions.increment();
        
        if (success) {
            meterRegistry.counter("kafka.slots.acquisition.success",
                    Tags.of("engine", engineInstanceId, "topic", topic, "group", groupId))
                    .increment();
        } else {
            meterRegistry.counter("kafka.slots.acquisition.failure",
                    Tags.of("engine", engineInstanceId, "topic", topic, "group", groupId))
                    .increment();
        }
    }
    
    /**
     * Record a slot release operation
     */
    public void recordSlotRelease(int slotCount) {
        slotReleases.increment(slotCount);
    }
    
    /**
     * Time a slot operation
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }
    
    /**
     * Stop timer for acquisition
     */
    public void stopAcquisitionTimer(Timer.Sample sample) {
        sample.stop(slotAcquisitionTime);
    }
    
    /**
     * Helper class to manage per-engine gauges
     * TODO: Complete implementation when KafkaSlotManager.EngineInfo is available
     */
    private static class EngineGauges {
        private final String engineId;
        private final AtomicLong lastHeartbeat = new AtomicLong();
        private final AtomicInteger slotCount = new AtomicInteger();
        private final AtomicInteger maxSlots = new AtomicInteger();
        
        EngineGauges(String engineId, MeterRegistry registry) {
            this.engineId = engineId;
            
            Gauge.builder("kafka.slots.engine.slots", slotCount, AtomicInteger::get)
                    .description("Current slot count for engine")
                    .tag("engine", engineId)
                    .register(registry);
            
            Gauge.builder("kafka.slots.engine.max_slots", maxSlots, AtomicInteger::get)
                    .description("Maximum slots allowed for engine")
                    .tag("engine", engineId)
                    .register(registry);
            
            Gauge.builder("kafka.slots.engine.heartbeat_age", this,
                    g -> System.currentTimeMillis() - g.lastHeartbeat.get())
                    .description("Milliseconds since last heartbeat")
                    .tag("engine", engineId)
                    .baseUnit("milliseconds")
                    .register(registry);
        }
        
        // TODO: Implement when KafkaSlotManager.EngineInfo is available
        /*
        void updateMetrics(KafkaSlotManager.EngineInfo engineInfo) {
            lastHeartbeat.set(engineInfo.getLastHeartbeat().toEpochMilli());
            slotCount.set(engineInfo.getCurrentSlots());
            maxSlots.set(engineInfo.getMaxSlots());
        }
        */
    }
}