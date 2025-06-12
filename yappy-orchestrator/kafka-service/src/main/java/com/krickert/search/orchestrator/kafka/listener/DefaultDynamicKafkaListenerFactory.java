package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.inject.Singleton;

import java.util.Map;

@Singleton
public class DefaultDynamicKafkaListenerFactory implements DynamicKafkaListenerFactory {
    @Override
    public DynamicKafkaListener create(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig,
            Map<String, String> originalConsumerPropertiesFromStep,
            String pipelineName,
            String stepName,
            ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher) {
        return new DynamicKafkaListener(
                listenerId,
                topic,
                groupId,
                finalConsumerConfig,
                originalConsumerPropertiesFromStep,
                pipelineName,
                stepName,
                eventPublisher
        );
    }
}
