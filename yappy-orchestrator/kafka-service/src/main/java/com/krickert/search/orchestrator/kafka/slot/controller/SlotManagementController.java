package com.krickert.search.orchestrator.kafka.slot.controller;

import com.krickert.search.orchestrator.kafka.slot.SlotAwareKafkaListenerManager;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.annotation.SerdeImport;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST API controller for Kafka slot management operations.
 * Provides endpoints to:
 * 1. Get current slot distribution across engines
 * 2. Trigger slot rebalancing for topics
 * 3. Monitor slot manager health
 * 4. Force grow/shrink operations
 */
@Controller("/api/kafka/slots")
@Requires(property = "kafka.slot-manager.enabled", value = "true", defaultValue = "false")
@ExecuteOn(TaskExecutors.BLOCKING)
@SerdeImport(KafkaSlotManager.EngineInfo.class)
@SerdeImport(KafkaSlotManager.SlotManagerHealth.class)
public class SlotManagementController {
    
    private static final Logger LOG = LoggerFactory.getLogger(SlotManagementController.class);
    
    private final SlotAwareKafkaListenerManager slotAwareManager;
    private final KafkaSlotManager slotManager;
    
    @Inject
    public SlotManagementController(SlotAwareKafkaListenerManager slotAwareManager, 
                                   KafkaSlotManager slotManager) {
        this.slotAwareManager = slotAwareManager;
        this.slotManager = slotManager;
        LOG.info("SlotManagementController initialized with slot management enabled");
    }
    
    @Get("/distribution")
    public Mono<Map<String, Integer>> getSlotDistribution() {
        LOG.info("📊 API request: Get slot distribution");
        return slotAwareManager.getSlotDistribution()
            .doOnNext(distribution -> LOG.info("Returning slot distribution: {}", distribution))
            .doOnError(error -> LOG.error("Error getting slot distribution: {}", error.getMessage()));
    }
    
    @Post("/rebalance")
    public Mono<RebalanceResponse> rebalanceSlots(
            @QueryValue String topic,
            @QueryValue String groupId) {
        
        LOG.info("🔄 API request: Rebalance slots for topic={}, groupId={}", topic, groupId);
        
        if (topic == null || topic.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Topic cannot be null or empty"));
        }
        if (groupId == null || groupId.trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("GroupId cannot be null or empty"));
        }
        
        return slotAwareManager.rebalanceSlots(topic, groupId)
            .thenReturn(new RebalanceResponse("success", 
                String.format("Rebalancing triggered for topic '%s' and group '%s'", topic, groupId)))
            .doOnSuccess(response -> LOG.info("Rebalancing completed: {}", response.message()))
            .doOnError(error -> LOG.error("Error during rebalancing: {}", error.getMessage()));
    }
    
    @Post("/grow")
    public Mono<RebalanceResponse> growSlots(
            @QueryValue String topic,
            @QueryValue String groupId) {
        
        LOG.info("📈 API request: Grow slots for topic={}, groupId={}", topic, groupId);
        
        // Growing is essentially triggering a rebalance that allows engines to acquire more slots
        return rebalanceSlots(topic, groupId)
            .map(response -> new RebalanceResponse("success",
                String.format("Grow operation triggered for topic '%s' and group '%s'. " +
                    "Engines can now acquire additional slots.", topic, groupId)));
    }
    
    @Post("/shrink")
    public Mono<RebalanceResponse> shrinkSlots(
            @QueryValue String topic,
            @QueryValue String groupId) {
        
        LOG.info("📉 API request: Shrink slots for topic={}, groupId={}", topic, groupId);
        
        // Shrinking is triggering a rebalance that encourages more efficient slot distribution
        return rebalanceSlots(topic, groupId)
            .map(response -> new RebalanceResponse("success",
                String.format("Shrink operation triggered for topic '%s' and group '%s'. " +
                    "Slot assignments will be redistributed more efficiently.", topic, groupId)));
    }
    
    @Get("/health")
    public Mono<KafkaSlotManager.SlotManagerHealth> getSlotManagerHealth() {
        LOG.info("🏥 API request: Get slot manager health");
        return slotAwareManager.getHealth()
            .doOnNext(health -> LOG.info("Returning slot manager health: {}", health))
            .doOnError(error -> LOG.error("Error getting slot manager health: {}", error.getMessage()));
    }
    
    @Get("/engines")
    public Mono<java.util.List<KafkaSlotManager.EngineInfo>> getRegisteredEngines() {
        LOG.info("🖥️ API request: Get registered engines");
        return slotManager.getRegisteredEngines()
            .collectList()
            .doOnNext(engines -> LOG.info("Returning {} registered engines", engines.size()))
            .doOnError(error -> LOG.error("Error getting registered engines: {}", error.getMessage()));
    }
    
    /**
     * Response model for rebalance operations.
     */
    @Serdeable
    public record RebalanceResponse(
        String status,
        String message
    ) {}
}