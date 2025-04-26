// KafkaForwarderClient.java
package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;

import java.util.UUID;

@KafkaClient
public interface KafkaForwarderClient {
    void send(@Topic String topic, @KafkaKey UUID key, PipeStream pipe);
}