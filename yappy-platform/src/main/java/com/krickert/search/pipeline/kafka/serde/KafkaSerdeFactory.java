package com.krickert.search.pipeline.kafka.serde;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Factory for creating Kafka serializers and deserializers based on configuration.
 * This factory creates the appropriate serializer and deserializer instances
 * based on the registry type and other configuration parameters.
 */
public interface KafkaSerdeFactory {

    /**
     * Get a key deserializer for the given pipeline configuration.
     *
     * @param <K> The key type.
     * @return A configured key deserializer.
     */
    <K>Deserializer<K> getKeyDeserializer();

    /**
     * Get a value deserializer for the given pipeline configuration.
     *
     * @param <V> The value type.
     * @return A configured value deserializer.
     */
    <V> Deserializer<V> getValueDeserializer();

    /**
     * Get a key serializer for the given pipeline configuration.
     *
     * @param <K> The key type.
     * @return A configured key serializer.
     */
    <K> Serializer<K> getKeySerializer();

    /**
     * Get a value serializer for the given pipeline configuration.
     *
     * @param <V> The value type.
     * @return A configured value serializer.
     */
    <V> Serializer<V> getValueSerializer();

    /**
     * Get the registry type used by this factory.
     *
     * @return The registry type (e.g., "glue", "apicurio").
     */
    String getRegistryType();
}