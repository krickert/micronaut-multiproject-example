package com.krickert.search.engine.service;

import com.krickert.search.model.PipeStream;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
// Assuming DynamicConfigurationManager is not directly needed by the KafkaClient interface itself
// but rather by services that determine the topic.
// @Requires(beans = DynamicConfigurationManager.class)
@KafkaClient(id = "pipestream-dispatcher") // Giving the Kafka client an ID
public interface PipeStreamDispatcher {
    // The topic will be determined by the caller and passed here
    void dispatch(@Topic String topicName, String key, PipeStream pipeStream);

    // Overload for no key if preferred, Kafka client will handle partitioning
    default void dispatch(String topicName, PipeStream pipeStream) {
        dispatch(topicName, pipeStream.getStreamId(), pipeStream);
    }
}