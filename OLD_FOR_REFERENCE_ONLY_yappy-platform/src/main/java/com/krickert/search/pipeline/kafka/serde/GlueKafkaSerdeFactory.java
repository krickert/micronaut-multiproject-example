package com.krickert.search.pipeline.kafka.serde;

import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import io.micronaut.configuration.kafka.config.AbstractKafkaConfiguration;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of KafkaSerdeFactory for AWS Glue Schema Registry.
 * This factory creates serializers and deserializers configured for Glue Schema Registry.
 */
@Singleton
@Requires(property = "kafka.schema.registry.type", value = "glue")
public class GlueKafkaSerdeFactory implements KafkaSerdeFactory {
    private static final Logger log = LoggerFactory.getLogger(GlueKafkaSerdeFactory.class);

    private static final String REGISTRY_TYPE = "glue";
    private static final String DEFAULT_AWS_REGION = "us-east-1";
    private static final String DEFAULT_REGISTRY_NAME = "default-registry";

    private final AbstractKafkaConfiguration kafkaConfiguration;

    @Inject
    public GlueKafkaSerdeFactory(io.micronaut.configuration.kafka.config.KafkaConsumerConfiguration kafkaConfiguration) {
        this.kafkaConfiguration = kafkaConfiguration;
        log.info("Initializing GlueKafkaSerdeFactory");
    }

    /**
     * Get a key deserializer for the given pipeline configuration.
     * For Glue, we typically use StringDeserializer for keys.
     *
     * @param <K> The key type.
     * @return A configured key deserializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <K> Deserializer<K> getKeyDeserializer() {
        log.debug("Creating key deserializer for pipeline with Glue Schema Registry");
        // For keys, we typically use StringDeserializer
        return (Deserializer<K>) new UUIDDeserializer();
    }

    /**
     * Get a value deserializer for the given pipeline configuration.
     * For Glue, we use GlueSchemaRegistryKafkaDeserializer configured with the appropriate settings.
     *
     * @param <V> The value type.
     * @return A configured value deserializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Deserializer<V> getValueDeserializer() {
        log.debug("Creating value deserializer for pipeline with Glue Schema Registry");

        // Get base configuration from Kafka config
        Map<String, Object> configs = new HashMap<>();
        // Copy values from Kafka config, ensuring correct types
        for (Map.Entry<?, ?> entry : kafkaConfiguration.getConfig().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                configs.put(entry.getKey().toString(), entry.getValue());
            }
        }

        // Extract Glue-specific configuration
        String awsRegion = getConfigProperty(configs, "aws.region", DEFAULT_AWS_REGION);
        String registryName = getConfigProperty(configs, "registry.name", DEFAULT_REGISTRY_NAME);

        // Set AWS Glue Schema Registry specific properties
        configs.put(AWSSchemaRegistryConstants.AWS_REGION, awsRegion);
        configs.put(AWSSchemaRegistryConstants.REGISTRY_NAME, registryName);
        configs.put(AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, "SPECIFIC_RECORD");

        // Create and configure the deserializer
        GlueSchemaRegistryKafkaDeserializer deserializer = new GlueSchemaRegistryKafkaDeserializer();
        deserializer.configure(configs, false); // false = value deserializer

        log.info("Created Glue Schema Registry deserializer for with region={}, registry={}",
                awsRegion, registryName);

        return (Deserializer<V>) deserializer;
    }

    /**
     * Get a key serializer for the given pipeline configuration.
     * For Glue, we typically use StringSerializer for keys.
     *
     * @param <K> The key type.
     * @return A configured key serializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <K> Serializer<K> getKeySerializer() {
        log.debug("Creating key serializer for pipeline with Glue Schema Registry");
        // For keys, we typically use StringSerializer
        return (Serializer<K>) new StringSerializer();
    }

    /**
     * Get a value serializer for the given pipeline configuration.
     * For Glue, we use GlueSchemaRegistryKafkaSerializer configured with the appropriate settings.
     *
     * @param <V> The value type.
     * @return A configured value serializer.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V> Serializer<V> getValueSerializer() {
        log.debug("Creating value serializer for pipeline with Glue Schema Registry");

        // Get base configuration from Kafka config
        Map<String, Object> configs = new HashMap<>();
        // Copy values from Kafka config, ensuring correct types
        for (Map.Entry<?, ?> entry : kafkaConfiguration.getConfig().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                configs.put(entry.getKey().toString(), entry.getValue());
            }
        }

        // Extract Glue-specific configuration
        String awsRegion = getConfigProperty(configs, "aws.region", DEFAULT_AWS_REGION);
        String registryName = getConfigProperty(configs, "registry.name", DEFAULT_REGISTRY_NAME);

        // Set AWS Glue Schema Registry specific properties
        configs.put(AWSSchemaRegistryConstants.AWS_REGION, awsRegion);
        configs.put(AWSSchemaRegistryConstants.REGISTRY_NAME, registryName);
        configs.put(AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, "true");
        configs.put(AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, "SPECIFIC_RECORD");

        // Create and configure the serializer
        GlueSchemaRegistryKafkaSerializer serializer = new GlueSchemaRegistryKafkaSerializer();
        serializer.configure(configs, false); // false = value serializer

        log.info("Created Glue Schema Registry serializer for with region={}, registry={}",
                 awsRegion, registryName);

        return (Serializer<V>) serializer;
    }

    /**
     * Get the registry type used by this factory.
     *
     * @return The registry type ("glue").
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
