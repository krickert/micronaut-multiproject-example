package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a Kafka topic to publish to.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class KafkaPublishTopics {
    /**
     * The name of the Kafka topic.
     */
    private String topic;

    /**
     * The ID of the pipeline step that publishes to this topic.
     */
    private String pipelineStepId;
}
