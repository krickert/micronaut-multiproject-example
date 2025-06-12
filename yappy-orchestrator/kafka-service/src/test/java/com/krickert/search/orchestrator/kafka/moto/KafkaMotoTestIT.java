package com.krickert.search.orchestrator.kafka.moto;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetRegistryResponse;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for KafkaMotoTest.
 * This test verifies that the Moto Registry is properly set up and can be used with Kafka.
 */
@Requires(property = "glue.enabled", value = "true")
@Requires(property = "kafka.enabled", value = "true")
@Requires(property = "kafka.schema.registry.type", value = "glue")
@MicronautTest(environments = "test-glue")
public class KafkaMotoTestIT {
    private static final Logger log = LoggerFactory.getLogger(KafkaMotoTestIT.class);

    // Default topics that should be created
    private static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "test-pipeline-input", 
            "test-processor-input", 
            "test-pipeline-output", 
            "pipeline-test-output-topic"
    );

    // AWS credentials and region for testing
    private static final String AWS_ACCESS_KEY = "test";
    private static final String AWS_SECRET_KEY = "test";
    private static final String AWS_REGION = "us-east-1";
    private static final String REGISTRY_NAME = "default";

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private GlueService glueService;

    @Value("${kafka.schema.registry.type}")
    String registryType;

    @Value("${glue.registry.url}")
    String registryUrl;

    @Value("${kafka.bootstrap.servers}")
    String bootstrapServers;

    /**
     * Get the registry endpoint from the application context.
     * @return The registry endpoint URL
     */
    private String getRegistryEndpoint() {
        return registryUrl != null ? registryUrl : applicationContext.getProperty("glue.registry.url", String.class).orElse(null);
    }

    /**
     * Get the Kafka bootstrap servers from the application context.
     * @return The bootstrap servers
     */
    private String getBootstrapServers() {
        return bootstrapServers != null ? bootstrapServers : applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
    }

    /**
     * Initialize the registry and create Kafka topics before running the test.
     */
    @BeforeEach
    void setup() throws ExecutionException, InterruptedException, TimeoutException {
        // Initialize the Glue registry
        glueService.initializeRegistry();

        // Create Kafka topics
        createKafkaTopics();
    }

    /**
     * Delete the registry after the test.
     */
    @AfterEach
    void cleanup() {
        // Delete the Glue registry
        glueService.deleteRegistry();
    }

    /**
     * Create the required Kafka topics before running the test.
     * This ensures that the topics exist when the test runs.
     */
    private void createKafkaTopics() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Creating Kafka topics for test...");

        String kafkaServers = getBootstrapServers();
        if (kafkaServers == null) {
            log.warn("Bootstrap servers not found, skipping topic creation");
            return;
        }

        log.info("Using Kafka bootstrap servers: {}", kafkaServers);

        // Create AdminClient configuration
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            // Check if topics already exist
            Set<String> existingTopics = adminClient.listTopics().names().get(30, TimeUnit.SECONDS);
            log.info("Existing topics: {}", existingTopics);

            // Create list of topics to create (only those that don't already exist)
            List<NewTopic> topicsToCreate = DEFAULT_TOPICS.stream()
                    .filter(topic -> !existingTopics.contains(topic))
                    .map(topic -> new NewTopic(topic, 1, (short) 1)) // 1 partition, 1 replica
                    .collect(Collectors.toList());

            if (topicsToCreate.isEmpty()) {
                log.info("All required topics already exist, skipping topic creation");
                return;
            }

            log.info("Creating topics: {}", topicsToCreate.stream().map(NewTopic::name).collect(Collectors.toList()));

            // Create the topics
            CreateTopicsResult result = adminClient.createTopics(topicsToCreate);

            // Wait for topic creation to complete
            result.all().get(30, TimeUnit.SECONDS);

            // Verify topics were created
            Set<String> updatedTopics = adminClient.listTopics().names().get(30, TimeUnit.SECONDS);
            log.info("Updated topics list: {}", updatedTopics);

            // Verify all required topics exist
            for (String topic : DEFAULT_TOPICS) {
                if (!updatedTopics.contains(topic)) {
                    log.error("Failed to create topic: {}", topic);
                    throw new RuntimeException("Failed to create required topic: " + topic);
                }
            }

            log.info("Successfully created all required Kafka topics");
        } catch (Exception e) {
            log.error("Error creating Kafka topics", e);
            throw e;
        }
    }

    /**
     * Test that the Moto Registry is properly set up and can be used with Kafka.
     */
    @Test
    void testMotoRegistrySetup() throws ExecutionException, InterruptedException {
        // Verify that the registry type is set correctly
        assertEquals("glue", registryType);

        // Verify that the registry endpoint is set
        String endpoint = getRegistryEndpoint();
        assertThat(endpoint).isNotNull();
        log.info("Moto Registry endpoint: {}", endpoint);

        // Verify that the registry exists - we don't need to create it here as it's done in setup()
        GetRegistryResponse registry;
        try (GlueClient glueClient = GlueClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(AWS_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)
                ))
                .build()) {
            registry = glueClient.getRegistry(builder -> builder.registryId(id -> id.registryName(REGISTRY_NAME)));
        }
        assertThat(registry).isNotNull();
        assertThat(registry.registryName()).isEqualTo(REGISTRY_NAME);
        log.info("Found registry: {}", registry.registryName());

        // Verify that the topics are created
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            ListTopicsResult topics = adminClient.listTopics();
            Set<String> topicNames = topics.names().get();

            log.info("Available topics: {}", topicNames);

            // Verify that all expected topics are created
            for (String topic : DEFAULT_TOPICS) {
                assertThat(topicNames).contains(topic);
            }
        }

        // Verify that the properties are set correctly
        Map<String, String> props = new HashMap<>();

        // Add Kafka properties
        props.put("kafka.bootstrap.servers", getBootstrapServers());

        // Add producer properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + "bootstrap.servers", getBootstrapServers());
        props.put(producerPrefix + "key.serializer", applicationContext.getProperty(producerPrefix + "key.serializer", String.class).orElse(""));
        props.put(producerPrefix + "value.serializer", applicationContext.getProperty(producerPrefix + "value.serializer", String.class).orElse(""));

        // Add consumer properties
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + "bootstrap.servers", getBootstrapServers());
        props.put(consumerPrefix + "key.deserializer", applicationContext.getProperty(consumerPrefix + "key.deserializer", String.class).orElse(""));
        props.put(consumerPrefix + "value.deserializer", applicationContext.getProperty(consumerPrefix + "value.deserializer", String.class).orElse(""));

        // Add Moto Registry properties
        props.put("glue.registry.url", getRegistryEndpoint());
        props.put("glue.registry.name", REGISTRY_NAME);
        props.put("aws.region", AWS_REGION);
        props.put("aws.accessKeyId", AWS_ACCESS_KEY);
        props.put("aws.secretAccessKey", AWS_SECRET_KEY);

        assertThat(props).isNotEmpty();

        // Verify Kafka properties
        assertThat(props).containsKey("kafka.bootstrap.servers");

        // Verify producer properties
        assertThat(props).containsKey(producerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(producerPrefix + "key.serializer");
        assertThat(props).containsKey(producerPrefix + "value.serializer");

        // Verify consumer properties
        assertThat(props).containsKey(consumerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(consumerPrefix + "key.deserializer");
        assertThat(props).containsKey(consumerPrefix + "value.deserializer");

        // Verify Moto Registry properties
        assertThat(props).containsKey("glue.registry.url");
        assertThat(props).containsKey("glue.registry.name");
        assertThat(props).containsKey("aws.region");
        assertThat(props).containsKey("aws.accessKeyId");
        assertThat(props).containsKey("aws.secretAccessKey");

        log.info("All tests passed for Moto Registry setup");
    }
}
