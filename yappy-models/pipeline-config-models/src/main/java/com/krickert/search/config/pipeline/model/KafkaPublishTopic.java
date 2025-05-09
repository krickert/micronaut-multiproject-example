package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
// No Lombok needed

/**
 * Specifies the name of a Kafka topic a pipeline step will publish to.
 * This record is immutable.
 *
 * @param topic The name of the Kafka topic. Must not be null or blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaPublishTopic(
    @JsonProperty("topic") String topic
) {
    public KafkaPublishTopic {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("KafkaPublishTopic topic cannot be null or blank.");
        }
    }
}