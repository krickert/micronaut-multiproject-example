// KafkaForwarder.java
package com.krickert.search.pipeline.engine.kafka;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
@io.micronaut.context.annotation.Requires(property = "kafka.enabled", value = "true")
public class KafkaForwarder {

    @Inject
    KafkaForwarderClient kafkaForwarderClient;

    public void forwardToKafka(PipeStream pipe, String topic) {
        // The 'destination' field contains the Kafka topic name.
        kafkaForwarderClient.send(topic, ProtobufUtils.createKey(pipe.getStreamId()), pipe);
    }

    public void forwardToError(PipeStream pipe, String topic) {
        // Use a backup topic (e.g. prefix with "backup-") for reprocessing failed messages.
        String backupTopic = "backup-" + topic;
        kafkaForwarderClient.send(backupTopic, ProtobufUtils.createKey(pipe.getStreamId()), pipe);
    }
}
