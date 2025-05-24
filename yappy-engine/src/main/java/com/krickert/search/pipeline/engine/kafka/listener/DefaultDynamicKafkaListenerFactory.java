package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
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
            PipeStreamEngine pipeStreamEngine) {
        return new DynamicKafkaListener(
                listenerId,
                topic,
                groupId,
                finalConsumerConfig,
                originalConsumerPropertiesFromStep,
                pipelineName,
                stepName,
                pipeStreamEngine
        );
    }
}