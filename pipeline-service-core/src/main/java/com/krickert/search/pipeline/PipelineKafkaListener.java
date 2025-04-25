// PipelineKafkaListener.java
package com.krickert.search.pipeline;

import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.grpc.PipelineServiceImpl;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@KafkaListener(groupId = "pipe-group")
@Singleton
public class PipelineKafkaListener {

    @Inject
    PipelineServiceImpl pipelineService;

    @Topic("input-pipe")
    public void receive(PipeStream pipe) {
        // Process the pipe using the helper method in the service.
        pipelineService.processKafkaMessage(pipe);
    }
}
