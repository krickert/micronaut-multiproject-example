// KafkaForwarder.java
package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;
import com.krickert.search.model.Route;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class KafkaForwarder {

    @Inject
    KafkaForwarderClient kafkaForwarderClient;

    public void forwardToKafka(PipeStream pipe, Route route) {
        // The 'destination' field contains the Kafka topic name.
        String topic = route.getDestination();
        kafkaForwarderClient.send(topic, ProtobufUtils.createKey(pipe.getRequest().getDoc().getId()), pipe);
    }

    public void forwardToBackup(PipeStream pipe, Route route) {
        // Use a backup topic (e.g. prefix with "backup-") for reprocessing failed messages.
        String backupTopic = "backup-" + route.getDestination();
        kafkaForwarderClient.send(backupTopic, ProtobufUtils.createKey(pipe.getRequest().getDoc().getId()), pipe);
    }
}