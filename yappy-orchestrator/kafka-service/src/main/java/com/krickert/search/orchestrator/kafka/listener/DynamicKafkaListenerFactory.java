package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;

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
            //TODO: this will be a common event type
            //TODO: will also need the slot manager project service!!
            PipeStreamEngine pipeStreamEngine
    );
}