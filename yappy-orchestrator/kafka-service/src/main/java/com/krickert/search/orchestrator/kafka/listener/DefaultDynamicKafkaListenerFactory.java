package com.krickert.search.orchestrator.kafka.listener;

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
            /*TODO: this should be an event listener instead.  The piesteam engine will listen
            for the event and process the pipestream.  We should not inject the PipeStreamEngine
            furthermore, we would wait to send an ACK for this and only after processing should we sent the ACK
            Since this is a fire-and-forget, it will be async for the event call, so we can ACK after that because we will
            use a DLQ concept. */
            /*TODO: we will also need to integrate the kafka-slot-manager for when we integrate slot management*/
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
