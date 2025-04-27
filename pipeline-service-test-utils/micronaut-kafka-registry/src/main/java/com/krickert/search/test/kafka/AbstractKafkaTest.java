package com.krickert.search.test.kafka;

import com.krickert.search.test.kafka.registry.SchemaRegistryFactory;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
     * Reset the schema registry after each test to ensure a clean state.
     * This prevents tests from interfering with each other when run together.
     */
    @AfterEach
    public void cleanupAfterTest() {
        // Reset schema registry
        if (schemaRegistry != null) {
            log.info("Resetting schema registry after test");
            schemaRegistry.reset();
        } else {
            log.warn("Schema registry is null, cannot reset");
        }

        // Ensure topics are created for the next test
        createTopicsIfNeeded();

        // Force garbage collection to help clean up resources
        System.gc();
    }

    /**
     * Create Kafka topics if they don't already exist.
     * This ensures that topics are available for tests.
     */
    protected void createTopicsIfNeeded() {
        if (!kafka.isRunning()) {
            getBootstrapServers(); // This will start Kafka if it's not running
        }

        try {
            Map<String, Object> adminProps = new HashMap<>();
            adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                // List of topics to create
                List<NewTopic> topics = Arrays.asList(
                    new NewTopic("test-pipeline-input", 1, (short) 1),
                    new NewTopic("test-pipeline-output", 1, (short) 1),
                    new NewTopic("test-PipeStream", 1, (short) 1),
                    new NewTopic("test-PipeStream-apicurio", 1, (short) 1),
                    new NewTopic("test-micronaut-pipestream-apicurio", 1, (short) 1),
                    new NewTopic("echo-output-test", 1, (short) 1),
                    new NewTopic("input-pipe", 1, (short) 1)
                );

                // Create topics
                CreateTopicsResult result = adminClient.createTopics(topics);

                // Wait for topic creation to complete
                result.all().get(30, TimeUnit.SECONDS);
                log.info("Kafka topics created or already exist");
            }
        } catch (Exception e) {
            // Log but don't fail if topics already exist
            log.warn("Error creating Kafka topics (they may already exist): {}", e.getMessage());
        }
    }

    /**
     * Get the bootstrap servers for Kafka.
     * 
     * @return the bootstrap servers as a string
     */
    protected String getBootstrapServers() {
        if (!kafka.isRunning()) {
            log.info("Starting Kafka container...");
            kafka.start();
            log.info("Kafka container started. Bootstrap servers: {}", kafka.getBootstrapServers());
        }
        return kafka.getBootstrapServers();
    }

    /**
     * Ensures both Schema Registry and Kafka are started in the correct order.
     * First starts the Schema Registry, then Kafka.
     */
    protected void ensureServicesStarted() {
        // First, ensure Schema Registry is started
        if (schemaRegistry == null) {
            log.warn("Schema Registry is null, creating a temporary instance");
            // Create a temporary ApplicationContext to get the SchemaRegistry
            ApplicationContext context = ApplicationContext.builder().build();
            try (context) {
                context.start();
                SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);
                schemaRegistry = factory.schemaRegistry(getSchemaRegistryType());
                log.info("Created local SchemaRegistry of type: {}", getSchemaRegistryType());
            }
        }

        // Start Schema Registry first
        log.info("Starting Schema Registry...");
        schemaRegistry.start();
        log.info("Schema Registry started: {}", schemaRegistry.getEndpoint());

        // Then start Kafka
        log.info("Starting Kafka...");
        getBootstrapServers();

        // Create topics after Kafka is started
        createTopicsIfNeeded();

        log.info("Both Schema Registry and Kafka are now running with required topics");
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
        // Ensure both Schema Registry and Kafka are started in the correct order
        ensureServicesStarted();

        String producerPrefix = "kafka.producers.default.";
        Map<String, String> props = new HashMap<>();

        // Basic Kafka producer configuration
        props.put(producerPrefix + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(producerPrefix + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, schemaRegistry.getSerializerClass());

        // Basic Kafka consumer configuration
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(consumerPrefix + ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(consumerPrefix + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(consumerPrefix + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, schemaRegistry.getDeserializerClass());

        // Add AdminClient configuration
        String adminPrefix = "kafka.admin.";
        props.put("kafka.admin.client.id", "test-admin-client");
        props.put("kafka.admin.request.timeout.ms", "5000");
        props.put("kafka.admin.retries", "3");
        props.put(adminPrefix + AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        // Add schema registry properties
        Map<String, String> registryProps = schemaRegistry.getProperties();
        props.putAll(registryProps);

        log.info("Kafka properties configured with bootstrap servers: {}", getBootstrapServers());

        return props;
    }
}
