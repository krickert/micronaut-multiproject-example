// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/KafkaListenerPool.java
package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;

import java.util.Collection;
import java.util.Map;

public interface KafkaListenerPool {

    DynamicKafkaListener createListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig,
            Map<String, String> originalConsumerPropertiesFromStep,
            String pipelineName,
            String stepName,
            //TODO: this should be the event type
            //TODO: will also need the slot manager project service!!
            PipeStreamEngine pipeStreamEngine
    );

    DynamicKafkaListener removeListener(String listenerId);

    DynamicKafkaListener getListener(String listenerId);

    Collection<DynamicKafkaListener> getAllListeners();

    int getListenerCount();

    boolean hasListener(String listenerId);

    void shutdownAllListeners();
}