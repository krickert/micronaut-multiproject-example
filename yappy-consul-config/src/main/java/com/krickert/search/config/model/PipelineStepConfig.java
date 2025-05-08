package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Configuration for a pipeline step, which represents a single processing unit in a pipeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineStepConfig {
    /**
     * The ID of the pipeline step.
     */
    private String pipelineStepId;

    /**
     * The ID of the pipeline implementation.
     */
    private String pipelineImplementationId;

    /**
     * Custom configuration options for the pipeline step.
     */
    private JsonConfigOptions customConfig;

    /**
     * List of Kafka topics to listen to.
     */
    private List<String> kafkaListenTopics;

    /**
     * List of Kafka topics to publish to.
     */
    private List<KafkaPublishTopics> kafkaPublishTopics;

    /**
     * List of gRPC services to forward to.
     */
    private List<String> grpcForwardTo;
}
