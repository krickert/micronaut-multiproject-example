package com.krickert.search.config.pipeline.model; // Or your chosen package

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;

/**
 * Configuration specific to Kafka transport for a pipeline step.
 *
 * @param listenTopics Topics this step consumes from. Can be empty if the step only publishes.
 * @param publishTopicPattern Pattern for the topic this step publishes its output to
 * (e.g., "${pipelineId}.${stepId}.output"). Can be null if the step only consumes.
 * @param kafkaProperties Additional Kafka consumer/producer properties specific to this step.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTransportConfig(
    @JsonProperty("listenTopics") List<String> listenTopics,
    @JsonProperty("publishTopicPattern") String publishTopicPattern,
    @JsonProperty("kafkaProperties") Map<String, String> kafkaProperties
) {
    @JsonCreator // Important for Jackson deserialization with records if custom logic/defaults exist
    public KafkaTransportConfig(
        @JsonProperty("listenTopics") List<String> listenTopics,
        @JsonProperty("publishTopicPattern") String publishTopicPattern,
        @JsonProperty("kafkaProperties") Map<String, String> kafkaProperties
    ) {
        this.listenTopics = (listenTopics == null) ? Collections.emptyList() : List.copyOf(listenTopics);
        this.publishTopicPattern = publishTopicPattern; // Can be null
        this.kafkaProperties = (kafkaProperties == null) ? Collections.emptyMap() : Map.copyOf(kafkaProperties);

        for (String topic : this.listenTopics) {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("KafkaTransportConfig listenTopics cannot contain null or blank topics.");
            }
        }
        // No validation for null/blank publishTopicPattern as it's optional
    }

    // Overriding default record constructor to ensure immutability and validation if not using @JsonCreator
    // public KafkaTransportConfig {
    //    this.listenTopics = (listenTopics == null) ? Collections.emptyList() : List.copyOf(listenTopics);
    //    // publishTopicPattern can be null
    //    this.kafkaProperties = (kafkaProperties == null) ? Collections.emptyMap() : Map.copyOf(kafkaProperties);
    //    for (String topic : this.listenTopics) {
    //        if (topic == null || topic.isBlank()) {
    //            throw new IllegalArgumentException("KafkaTransportConfig listenTopics cannot contain null or blank topics.");
    //        }
    //    }
    // }
}