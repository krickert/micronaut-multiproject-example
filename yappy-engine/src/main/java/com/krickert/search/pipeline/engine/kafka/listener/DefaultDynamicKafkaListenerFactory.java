package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Default factory for creating DynamicKafkaListener instances with slot management.
 * This factory is used when slot management is enabled.
 */
@Singleton
@Requires(property = "app.kafka.slot-management.enabled", value = "true", defaultValue = "false")
public class DefaultDynamicKafkaListenerFactory implements DynamicKafkaListenerFactory {
    
    private final KafkaSlotManager slotManager;
    private final String engineInstanceId;
    private final int defaultRequestedSlots;
    
    @Inject
    public DefaultDynamicKafkaListenerFactory(
            KafkaSlotManager slotManager,
            @Value("${app.engine.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") String engineInstanceId,
            @Value("${app.kafka.slot-management.default-slots-per-listener:0}") int defaultRequestedSlots) {
        this.slotManager = slotManager;
        this.engineInstanceId = engineInstanceId;
        this.defaultRequestedSlots = defaultRequestedSlots;
    }
    
    @Override
    public DynamicKafkaListener create(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig,
            Map<String, String> originalConsumerPropertiesFromStep,
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine) {
        return new DynamicKafkaListener(
                listenerId,
                topic,
                groupId,
                finalConsumerConfig,
                originalConsumerPropertiesFromStep,
                pipelineName,
                stepName,
                pipeStreamEngine,
                slotManager,
                engineInstanceId,
                defaultRequestedSlots
        );
    }
}