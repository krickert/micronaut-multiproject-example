package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a Kafka topic to publish to.
 * This class now only contains the topic name, as the publishing step's ID is implicit.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class KafkaPublishTopic { // Renamed from KafkaPublishTopics
    /**
     * The name of the Kafka topic.
     */
    private String topic;

    // pipelineStepId that publishes to this topic was removed as it's redundant.
    // If this topic is targeted for a specific downstream step,
    // that logic would be handled differently (e.g., by convention or another mapping field).
}