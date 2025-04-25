package com.krickert.search.test.apicurio;

import com.krickert.search.test.registry.SchemaRegistry;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer;
import io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of SchemaRegistry using Apicurio Registry.
 * This class manages the Apicurio Registry container and provides configuration for tests.
 */
@Requires(env = "test")
@Singleton
public class ApicurioSchemaRegistry implements SchemaRegistry {
    private static final Logger log = LoggerFactory.getLogger(ApicurioSchemaRegistry.class);
    private static final String REGISTRY_NAME = "apicurio";
    private static final GenericContainer<?> apicurioContainer;
    private static final String endpoint;
    private static boolean initialized = false;

    // Default return class for the deserializer
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeDoc";

    // Configurable return class for the deserializer
    private String returnClass;

    static {
        // Initialize the Apicurio container
        apicurioContainer = new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry:latest"))
                .withExposedPorts(8080)
                .withAccessToHost(true)
                .withEnv(Map.of(
                        "QUARKUS_PROFILE", "prod",
                        "REGISTRY_STORAGE_KIND", "in-memory",
                        "REGISTRY_AUTH_ANONYMOUS_READ_ACCESS_ENABLED", "true",
                        "REGISTRY_LOG_LEVEL", "DEBUG"
                ))
                .withStartupTimeout(Duration.ofSeconds(60))
                .withReuse(false);

        // Start the container
        apicurioContainer.start();

        // Set the endpoint
        endpoint = "http://" + apicurioContainer.getHost() + ":" + apicurioContainer.getMappedPort(8080) + "/apis/registry/v3";
        log.info("Apicurio Registry endpoint: {}", endpoint);
    }

    /**
     * Constructor that initializes the registry.
     * 
     * @param returnClass Optional return class for the deserializer. If null, the default return class will be used.
     */
    public ApicurioSchemaRegistry(@Nullable String returnClass) {
        this.returnClass = returnClass;
        start();
    }

    /**
     * Default constructor that initializes the registry with the default return class.
     */
    public ApicurioSchemaRegistry() {
        this(null);
    }

    /**
     * Sets the return class for the deserializer.
     * 
     * @param returnClass The fully qualified class name to use as the return class for the deserializer.
     * @return This instance for method chaining.
     */
    public ApicurioSchemaRegistry setReturnClass(@NonNull String returnClass) {
        this.returnClass = returnClass;
        return this;
    }

    @Override
    public @NonNull String getEndpoint() {
        return endpoint;
    }

    @Override
    public @NonNull String getRegistryName() {
        return REGISTRY_NAME;
    }

    @Override
    public void start() {
        if (!initialized) {
            // No additional initialization needed for Apicurio
            initialized = true;
        }
    }

    @Override
    public boolean isRunning() {
        return apicurioContainer.isRunning();
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>();

        // Kafka producer Apicurio Registry properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + SerdeConfig.REGISTRY_URL, endpoint);
        props.put(producerPrefix + SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        props.put(producerPrefix + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        props.put(producerPrefix + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");
        props.put(producerPrefix + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ProtobufKafkaSerializer.class.getName());

        // Kafka consumer Apicurio Registry properties
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + SerdeConfig.REGISTRY_URL, endpoint);
        props.put(consumerPrefix + SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        props.put(consumerPrefix + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        props.put(consumerPrefix + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, "default");
        props.put(consumerPrefix + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ProtobufKafkaDeserializer.class.getName());

        // Use the configured return class if available, otherwise use the default
        String effectiveReturnClass = (returnClass != null) ? returnClass : DEFAULT_RETURN_CLASS;
        props.put(consumerPrefix + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, effectiveReturnClass);
        log.debug("Using return class for deserializer: {}", effectiveReturnClass);

        // Remove Avro-specific configuration as we're using Protobuf

        return props;
    }

    @Override
    public @NonNull String getSerializerClass() {
        return ProtobufKafkaSerializer.class.getName();
    }

    @Override
    public @NonNull String getDeserializerClass() {
        return ProtobufKafkaDeserializer.class.getName();
    }
}
