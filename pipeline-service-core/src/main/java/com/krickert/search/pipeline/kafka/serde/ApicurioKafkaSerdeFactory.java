package com.krickert.search.pipeline.kafka.serde;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.PipelineConfig;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer;
import io.micronaut.configuration.kafka.config.AbstractKafkaConfiguration;
import io.micronaut.configuration.kafka.config.KafkaConsumerConfiguration;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of KafkaSerdeFactory for Apicurio Registry.
 * This factory creates serializers and deserializers configured for Apicurio Registry.
 */
@Singleton
@Requires(property = "kafka.schema.registry.type", value = "apicurio")
public class ApicurioKafkaSerdeFactory implements KafkaSerdeFactory {
    private static final Logger log = LoggerFactory.getLogger(ApicurioKafkaSerdeFactory.class);

    private static final String REGISTRY_TYPE = "apicurio";
    private static final String DEFAULT_REGISTRY_URL = "http://localhost:8080/apis/registry/v3";
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

    private final AbstractKafkaConfiguration<UUID,PipeStream> kafkaConfiguration;

    @Inject
    public ApicurioKafkaSerdeFactory(KafkaConsumerConfiguration<UUID, PipeStream> kafkaConfiguration) {
        this.kafkaConfiguration = kafkaConfiguration;
        log.info("Initializing ApicurioKafkaSerdeFactory");
    }

    /**
     * Get a key deserializer for the given pipeline configuration.
     * For Apicurio, we typically use StringDeserializer for keys.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <K> The key type.
     * @return A configured key deserializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <K> Deserializer<K> getKeyDeserializer(String pipelineName, PipelineConfig pipelineConfig) {
        log.debug("Creating key deserializer for pipeline '{}' with Apicurio Registry", pipelineName);
        // For keys, we typically use StringDeserializer
        return (Deserializer<K>) new StringDeserializer();
    }

    /**
     * Get a value deserializer for the given pipeline configuration.
     * For Apicurio, we use ProtobufKafkaDeserializer configured with the appropriate settings.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <V> The value type.
     * @return A configured value deserializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Deserializer<V> getValueDeserializer(String pipelineName, PipelineConfig pipelineConfig) {
        log.debug("Creating value deserializer for pipeline '{}' with Apicurio Registry", pipelineName);

        // Get base configuration from Kafka config
        Map<String, Object> configs = new HashMap<>();
        // Copy values from Kafka config, ensuring correct types
        for (Map.Entry<?, ?> entry : kafkaConfiguration.getConfig().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                configs.put(entry.getKey().toString(), entry.getValue());
            }
        }

        // Extract Apicurio-specific configuration
        String registryUrl = getConfigProperty(configs, "apicurio.registry.url", DEFAULT_REGISTRY_URL);
        String returnClass = getConfigProperty(configs, "deserializer.specific.value.return.class", DEFAULT_RETURN_CLASS);

        // Set Apicurio Registry specific properties
        configs.put(SerdeConfig.REGISTRY_URL, registryUrl);
        configs.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        configs.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        configs.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");
        configs.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);

        // Create and configure the deserializer
        ProtobufKafkaDeserializer<PipeStream> deserializer = new ProtobufKafkaDeserializer<>();
        deserializer.configure(configs, false); // false = value deserializer

        log.info("Created Apicurio Registry deserializer for pipeline '{}' with registry={}, returnClass={}",
                pipelineName, registryUrl, returnClass);

        return (Deserializer<V>) deserializer;
    }

    /**
     * Get a key serializer for the given pipeline configuration.
     * For Apicurio, we typically use StringSerializer for keys.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <K> The key type.
     * @return A configured key serializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <K> Serializer<K> getKeySerializer(String pipelineName, PipelineConfig pipelineConfig) {
        log.debug("Creating key serializer for pipeline '{}' with Apicurio Registry", pipelineName);
        // For keys, we typically use StringSerializer
        return (Serializer<K>) new StringSerializer();
    }

    /**
     * Get a value serializer for the given pipeline configuration.
     * For Apicurio, we use ProtobufKafkaSerializer configured with the appropriate settings.
     *
     * @param pipelineName The name of the pipeline (often the groupId).
     * @param pipelineConfig The configuration object for the pipeline.
     * @param <V> The value type.
     * @return A configured value serializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Serializer<V> getValueSerializer(String pipelineName, PipelineConfig pipelineConfig) {
        log.debug("Creating value serializer for pipeline '{}' with Apicurio Registry", pipelineName);

        // Get base configuration from Kafka config
        Map<String, Object> configs = new HashMap<>();
        // Copy values from Kafka config, ensuring correct types
        for (Map.Entry<?, ?> entry : kafkaConfiguration.getConfig().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                configs.put(entry.getKey().toString(), entry.getValue());
            }
        }

        // Extract Apicurio-specific configuration
        String registryUrl = getConfigProperty(configs, "apicurio.registry.url", DEFAULT_REGISTRY_URL);

        // Set Apicurio Registry specific properties
        configs.put(SerdeConfig.REGISTRY_URL, registryUrl);
        configs.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        configs.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        configs.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");

        // Create and configure the serializer
        ProtobufKafkaSerializer<PipeStream> serializer = new ProtobufKafkaSerializer<>();
        serializer.configure(configs, false); // false = value serializer

        log.info("Created Apicurio Registry serializer for pipeline '{}' with registry={}",
                pipelineName, registryUrl);

        return (Serializer<V>) serializer;
    }

    /**
     * Get the registry type used by this factory.
     *
     * @return The registry type ("apicurio").
     */
    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }

    /**
     * Helper method to get a configuration property with a default value.
     *
     * @param configs The configuration map.
     * @param key The property key.
     * @param defaultValue The default value if the property is not found.
     * @return The property value or the default value.
     */
    private String getConfigProperty(Map<String, Object> configs, String key, String defaultValue) {
        Object value = configs.get(key);
        if (value != null) {
            return value.toString();
        }

        // Try with kafka.consumers.default. prefix
        value = configs.get("kafka.consumers.default." + key);
        if (value != null) {
            return value.toString();
        }

        // Try with kafka.producers.default. prefix
        value = configs.get("kafka.producers.default." + key);
        if (value != null) {
            return value.toString();
        }

        return defaultValue;
    }
}
