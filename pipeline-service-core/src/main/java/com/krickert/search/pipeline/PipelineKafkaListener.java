// PipelineKafkaListener.java
package com.krickert.search.pipeline;

import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.grpc.PipelineService;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import static com.google.common.base.Preconditions.checkNotNull;

@KafkaListener(groupId = "${pipeline.name}")
@Singleton
public class PipelineKafkaListener {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PipelineKafkaListener.class);

    private final PipelineService pipelineService;

    @Inject
    public PipelineKafkaListener(PipelineService pipelineService) {
        this.pipelineService = checkNotNull(pipelineService);
        LOG.info("PipelineKafkaListener created");
    }

    @Topic("${pipeline.listen.topics}")
    public void receive(PipeStream pipe) {
        // Process the pipe using the helper method in the service.
        pipelineService.processKafkaMessage(pipe);
    }
}
