// KafkaForwarderClient.java
package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import io.micronaut.configuration.kafka.annotation.KafkaClient;

@KafkaClient
public interface KafkaForwarderClient {
    void send(String topic, PipeStream pipe);
}