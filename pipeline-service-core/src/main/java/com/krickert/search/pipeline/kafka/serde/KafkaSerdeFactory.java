package com.krickert.search.pipeline.kafka.serde;

import com.krickert.search.pipeline.config.PipelineConfig;
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
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <K> The key type.
     * @return A configured key deserializer.
     */
    <K> Deserializer<K> getKeyDeserializer(String pipelineName, PipelineConfig pipelineConfig);

    /**
     * Get a value deserializer for the given pipeline configuration.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <V> The value type.
     * @return A configured value deserializer.
     */
    <V> Deserializer<V> getValueDeserializer(String pipelineName, PipelineConfig pipelineConfig);

    /**
     * Get a key serializer for the given pipeline configuration.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <K> The key type.
     * @return A configured key serializer.
     */
    <K> Serializer<K> getKeySerializer(String pipelineName, PipelineConfig pipelineConfig);

    /**
     * Get a value serializer for the given pipeline configuration.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <V> The value type.
     * @return A configured value serializer.
     */
    <V> Serializer<V> getValueSerializer(String pipelineName, PipelineConfig pipelineConfig);

    /**
     * Get the registry type used by this factory.
     *
     * @return The registry type (e.g., "glue", "apicurio").
     */
    String getRegistryType();
}