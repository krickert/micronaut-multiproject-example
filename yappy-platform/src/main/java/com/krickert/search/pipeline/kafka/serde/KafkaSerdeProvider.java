// <llm-snippet-file>pipeline-service-core/src/main/java/com/krickert/search/pipeline/kafka/KafkaSerdeProvider.java</llm-snippet-file>
package com.krickert.search.pipeline.kafka.serde;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Provides configured Kafka Deserializer instances for dynamic consumers.
 * Implementations can encapsulate logic for different serialization formats
 * and configuration strategies.
 */
public interface KafkaSerdeProvider {
    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <K> Deserializer<K> getKeyDeserializer();

    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <V> Deserializer<V> getValueDeserializer();

    /**
     * Gets a configured key deserializer for the given pipeline.
     *
     * @return A configured Key Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <K>Serializer<K> getKeySerializer();

    /**
     * Gets a configured value deserializer for the given pipeline.
     *
     * @return A configured Value Deserializer instance.
     * @throws RuntimeException if configuration fails.
     */
    <V>Serializer<V> getValueSerializer();
}