package com.krickert.search.engine.kafka;

import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock implementation of KafkaConsumerService for testing without Kafka slot management.
 */
@Singleton
@Secondary
@Requires(property = "app.kafka.slot-management.enabled", value = "false", defaultValue = "false")
public class MockKafkaConsumerService implements KafkaConsumerService {
    
    private static final Logger LOG = LoggerFactory.getLogger(MockKafkaConsumerService.class);
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consumerCount = new AtomicInteger(0);
    
    @Override
    public Mono<Void> requestSlots(String topic, String groupId, int requestedSlots) {
        LOG.info("Mock: Requesting {} slots for topic: {}, group: {}", requestedSlots, topic, groupId);
        consumerCount.incrementAndGet();
        return Mono.empty();
    }
    
    @Override
    public Mono<Void> releaseSlots(String topic) {
        LOG.info("Mock: Releasing slots for topic: {}", topic);
        consumerCount.decrementAndGet();
        return Mono.empty();
    }
    
    @Override
    public SlotAssignment getCurrentAssignment() {
        // Return empty assignment with all required fields
        return new SlotAssignment(
            "mock-engine", 
            java.util.Collections.emptyList(),
            java.time.Instant.now(),
            java.time.Instant.now()
        );
    }
    
    @Override
    public int getActiveConsumerCount() {
        return consumerCount.get();
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public void startConsumers() {
        LOG.info("Mock: Starting consumers");
        running.set(true);
    }
    
    @Override
    public void stopConsumers() {
        LOG.info("Mock: Stopping consumers");
        running.set(false);
        consumerCount.set(0);
    }
    
    @Override
    public boolean isHealthy() {
        return running.get();
    }
}