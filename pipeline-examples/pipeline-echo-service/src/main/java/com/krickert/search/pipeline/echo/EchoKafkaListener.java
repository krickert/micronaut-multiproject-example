package com.krickert.search.pipeline.echo;

import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.grpc.PipelineServiceImpl;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Kafka listener for the Echo Pipeline Service.
 * Listens to the "echo-input" topic and processes PipeStream objects.
 */
@KafkaListener(groupId = "echo-pipe-group")
@Singleton
@Slf4j
public class EchoKafkaListener {

    @Inject
    PipelineServiceImpl pipelineService;

    @Topic("echo-input")
    public void receive(PipeStream pipe) {
        log.debug("Received PipeStream on echo-input topic");
        // Process the pipe using the helper method in the service.
        pipelineService.processKafkaMessage(pipe);
    }
}