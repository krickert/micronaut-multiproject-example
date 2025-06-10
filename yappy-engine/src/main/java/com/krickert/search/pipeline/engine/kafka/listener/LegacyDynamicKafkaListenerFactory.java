package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * Legacy factory for creating DynamicKafkaListener instances without slot management.
 * This factory is only used when slot management is disabled, which is not recommended
 * for production use.
 * 
 * @deprecated Use slot management for better partition coordination across engine instances
 */
@Singleton
@Requires(property = "app.kafka.slot-management.enabled", value = "false", defaultValue = "false")
@Deprecated
public class LegacyDynamicKafkaListenerFactory implements DynamicKafkaListenerFactory {
    
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
        throw new UnsupportedOperationException(
                "Legacy Kafka listener without slot management is no longer supported. " +
                "Please enable slot management by setting 'app.kafka.slot-management.enabled=true'"
        );
    }
}