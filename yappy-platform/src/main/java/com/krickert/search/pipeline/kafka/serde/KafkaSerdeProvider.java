// <llm-snippet-file>pipeline-service-core/src/main/java/com/krickert/search/pipeline/kafka/KafkaSerdeProvider.java</llm-snippet-file>
package com.krickert.search.pipeline.kafka.serde;

import org.apache.kafka.common.serialization.Deserializer;

/**
 * Provides configured Kafka Deserializer instances for dynamic consumers.
 * Implementations can encapsulate logic for different serialization formats
 * and configuration strategies.
 */
public interface KafkaSerdeProvider {

    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <K> Deserializer<K> getKeyDeserializer(String pipelineName);

    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <V> Deserializer<V> getValueDeserializer(String pipelineName);

    // Optional: Add methods for Serializers if needed for dynamic producers later
    // <K> Serializer<K> getKeySerializer(String pipelineName, PipelineConfig pipelineConfig);
    // <V> Serializer<V> getValueSerializer(String pipelineName, PipelineConfig pipelineConfig);
}