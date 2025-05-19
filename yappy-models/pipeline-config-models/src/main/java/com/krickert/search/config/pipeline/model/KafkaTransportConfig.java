// File: yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/model/KafkaTransportConfig.java
package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.Collections;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record KafkaTransportConfig(
        // For an OutputTarget, 'topic' is the primary field.
        // The old model's KafkaTransportConfig was multi-purpose.
        // Let's simplify for an OutputTarget context.
        @JsonProperty("topic") String topic, // Target topic to publish to
        // Consider if partitions/replicationFactor are relevant for an *output target's* config.
        // They are usually for topic creation/definition, not for a producer client.
        // @JsonProperty("partitions") Integer partitions,
        // @JsonProperty("replicationFactor") Integer replicationFactor,
        @JsonProperty("kafkaProducerProperties") Map<String, String> kafkaProducerProperties
        // Specific properties for the producer for THIS output
) {
    @JsonCreator
    public KafkaTransportConfig(
            @JsonProperty("topic") String topic,
            // @JsonProperty("partitions") Integer partitions,
            // @JsonProperty("replicationFactor") Integer replicationFactor,
            @JsonProperty("kafkaProducerProperties") Map<String, String> kafkaProducerProperties
    ) {
        this.topic = topic; // Can be null if not a Kafka output, validation handled by OutputTarget
        // this.partitions = partitions;
        // this.replicationFactor = replicationFactor;
        this.kafkaProducerProperties = (kafkaProducerProperties == null) ? Collections.emptyMap() : Map.copyOf(kafkaProducerProperties);
    }

    // Constructor from your old test for KafkaTransportConfig (used for step's own config)
    // This is likely NOT what's needed for OutputTarget's KafkaTransportConfig.
    // Keeping for reference from your old test:
    // KafkaTransportConfig(List<String> listenTopics, String publishTopicPattern, Map<String, String> kafkaProperties)
    // For an OUTPUT, it's just one target topic.
}
