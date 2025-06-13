package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import io.micronaut.context.event.ApplicationEventPublisher;

import java.util.Map;

public interface DynamicKafkaListenerFactory {
    DynamicKafkaListener create(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig,
            Map<String, String> originalConsumerPropertiesFromStep,
            String pipelineName,
            String stepName,
            ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher
    );
}