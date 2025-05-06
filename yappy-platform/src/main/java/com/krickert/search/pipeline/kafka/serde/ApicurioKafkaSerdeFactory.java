package com.krickert.search.pipeline.kafka.serde;

import com.krickert.search.model.PipeStream;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer;
import io.micronaut.configuration.kafka.config.AbstractKafkaConfiguration;
import io.micronaut.configuration.kafka.config.KafkaConsumerConfiguration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.*;
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
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

    private final String DEFAULT_REGISTRY_URL;
    private final AbstractKafkaConfiguration<UUID,PipeStream> kafkaConfiguration;

    @Inject
    public ApicurioKafkaSerdeFactory(
            @Value("${apicurio.registry.url}")  String apicurioRegistryUrl,
            KafkaConsumerConfiguration<UUID, PipeStream> kafkaConfiguration) {
        DEFAULT_REGISTRY_URL = apicurioRegistryUrl;
        this.kafkaConfiguration = kafkaConfiguration;
        log.info("Initializing ApicurioKafkaSerdeFactory");
    }

    /**
     * Get a key deserializer for the given pipeline configuration.
     * For Apicurio, we typically use StringDeserializer for keys.
     *
     * @param <K> The key type.
     * @return A configured key deserializer.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <K>Deserializer<K> getKeyDeserializer() {
        log.debug("Creating key deserializer for pipeline with Apicurio Registry");
        // For keys, we typically use UUIDDeserializer
        return (Deserializer<K>) new UUIDDeserializer();
    }


    /**
     * Get a key serializer for the given pipeline configuration.
     * @return A configured key serializer.
     */
    @Override
    public <K>Serializer<K> getKeySerializer() {
        return (Serializer<K>) new UUIDSerializer();
    }

    /**
     * Get a value serializer for the given pipeline configuration.
     *
     * @return A configured value serializer.
     */
    @Override
    public <V> Serializer<V> getValueSerializer() {
        log.debug("Creating value serializer for pipeline with Apicurio Registry");

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

        log.info("Created Apicurio Registry serializer for pipeline with registry={}",
                registryUrl);

        return (Serializer<V>) serializer;
    }

    /**
     * Get a value deserializer for the given pipeline configuration.
     * For Apicurio, we use ProtobufKafkaDeserializer configured with the appropriate settings.
     *
     * @param <V> The value type.
     * @return A configured value deserializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Deserializer<V> getValueDeserializer() {
        log.debug("Creating value deserializer for pipeline with Apicurio Registry");

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

        log.info("Created Apicurio Registry deserializer for pipeline with registry={}, returnClass={}", registryUrl, returnClass);

        return (Deserializer<V>) deserializer;
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
