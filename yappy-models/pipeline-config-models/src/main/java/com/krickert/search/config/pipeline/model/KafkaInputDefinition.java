package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaInputDefinition(
    @JsonProperty("listenTopics") @NotEmpty List<String> listenTopics,
    @JsonProperty("consumerGroupId") String consumerGroupId, // Now truly optional in config
    @JsonProperty("kafkaConsumerProperties") Map<String, String> kafkaConsumerProperties
) {
    public KafkaInputDefinition {
        // ... (validations for listenTopics, properties) ...
        // No validation for consumerGroupId being null/blank here, as it's optional.
        // The engine will handle defaulting if it's null.
    }
}