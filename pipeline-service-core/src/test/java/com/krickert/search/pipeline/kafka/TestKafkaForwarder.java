package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.Route;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * Test implementation of KafkaForwarder that uses TestKafkaForwarderClient
 * to handle UUID to String conversion for tests.
 */
@Singleton
@io.micronaut.context.annotation.Requires(property = "kafka.enabled", value = "true")
public class TestKafkaForwarder extends KafkaForwarder {

    @Inject
    KafkaForwarderClient testKafkaForwarderClient;

    @Override
    public void forwardToKafka(PipeStream pipe, Route route) {
        // The 'destination' field contains the Kafka topic name.
        String topic = route.getDestination();
        // Convert String ID to UUID
        UUID uuid = UUID.fromString(pipe.getRequest().getDoc().getId());
        testKafkaForwarderClient.send(topic, uuid, pipe);
    }

    @Override
    public void forwardToBackup(PipeStream pipe, Route route) {
        // Use a backup topic (e.g. prefix with "backup-") for reprocessing failed messages.
        String backupTopic = "backup-" + route.getDestination();
        // Convert String ID to UUID
        UUID uuid = UUID.fromString(pipe.getRequest().getDoc().getId());
        testKafkaForwarderClient.send(backupTopic, uuid, pipe);
    }
}
