package com.krickert.search.test.kafka;

import com.krickert.search.test.kafka.registry.SchemaRegistryFactory;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for Kafka tests that use a schema registry.
 * This class sets up a Kafka container and provides configuration for tests.
 */
public abstract class AbstractKafkaTest implements TestPropertyProvider {
    protected static final Logger log = LoggerFactory.getLogger(AbstractKafkaTest.class);

    // Environment variable and system property to control which schema registry to use
    private static final String SCHEMA_REGISTRY_TYPE_ENV = "SCHEMA_REGISTRY_TYPE";
    private static final String SCHEMA_REGISTRY_TYPE_PROP = "schema.registry.type";
    private static final String DEFAULT_REGISTRY_TYPE = "apicurio"; // Default to apicurio

    protected static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:latest")
    ).withPrivilegedMode(true).withAccessToHost(true);

    @Inject
    protected SchemaRegistry schemaRegistry;

    @BeforeAll
    public static void setupKafka() {
        log.info("Setup Kafka container...");
        // Kafka setup is handled by the container
    }

    /**
     * Get the bootstrap servers for Kafka.
     * 
     * @return the bootstrap servers as a string
     */
    protected String getBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    /**
     * Get the schema registry type from system property, environment variable, or use default.
     * 
     * @return the schema registry type
     */
    protected String getSchemaRegistryType() {
        // Check system property first
        String registryType = System.getProperty(SCHEMA_REGISTRY_TYPE_PROP);

        // If not found, check environment variable
        if (registryType == null || registryType.trim().isEmpty()) {
            registryType = System.getenv(SCHEMA_REGISTRY_TYPE_ENV);
        }

        // If still not found, use default
        if (registryType == null || registryType.trim().isEmpty()) {
            registryType = DEFAULT_REGISTRY_TYPE;
        }

        log.info("Using schema registry type: {}", registryType);
        return registryType;
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        if (!kafka.isRunning()) {
            kafka.start();
        }
        // Create a local SchemaRegistry if injection hasn't happened yet
        SchemaRegistry registry = schemaRegistry;
        if (registry == null) {
            // Create a temporary ApplicationContext to get the SchemaRegistry
            ApplicationContext context = ApplicationContext.builder().build();
            try (context) {
                context.start();
                SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);
                registry = factory.schemaRegistry(getSchemaRegistryType());
                log.info("Created local SchemaRegistry of type: {}", getSchemaRegistryType());
            }
        }

        // Ensure schema registry is started
        registry.start();

        String producerPrefix = "kafka.producers.default.";
        Map<String, String> props = new HashMap<>();

        // Basic Kafka producer configuration
        props.put(producerPrefix + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(producerPrefix + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, registry.getSerializerClass());

        // Basic Kafka consumer configuration
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(consumerPrefix + ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(consumerPrefix + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(consumerPrefix + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, registry.getDeserializerClass());

        // Add AdminClient configuration
        String adminPrefix = "kafka.admin.";
        props.put("kafka.admin.client.id", "test-admin-client");
        props.put("kafka.admin.request.timeout.ms", "5000");
        props.put("kafka.admin.retries", "3");
        props.put(adminPrefix + AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        // Add schema registry properties
        Map<String, String> registryProps = registry.getProperties();
        props.putAll(registryProps);

        log.info("Kafka properties configured with bootstrap servers: {}", getBootstrapServers());

        return props;
    }
}
