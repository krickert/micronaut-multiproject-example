package com.krickert.search.test.platform.kafka.registry;

import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.CreateRegistryRequest;
import software.amazon.awssdk.services.glue.model.CreateRegistryResponse;
import software.amazon.awssdk.services.glue.model.EntityNotFoundException;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of SchemaRegistry using Moto for AWS Glue Schema Registry.
 * This class manages the Moto container and provides configuration for tests.
 */
@Requires(env = "test")
@Singleton
public class MotoSchemaRegistry {
    private static final Logger log = LoggerFactory.getLogger(MotoSchemaRegistry.class);
    private static final String REGISTRY_NAME = "default";
    private static final GenericContainer<?> motoContainer;
    private static final String endpoint;
    private static boolean initialized = false;

    static {
        // Initialize the Moto container
        motoContainer = new GenericContainer<>(DockerImageName.parse("motoserver/moto:latest"))
                .withExposedPorts(5000)
                .withAccessToHost(true)
                .withCommand("-H0.0.0.0")
                .withEnv(Map.of(
                        "MOTO_SERVICE", "glue",
                        "TEST_SERVER_MODE", "true"
                ))
                .withStartupTimeout(Duration.ofSeconds(30))
                .withReuse(false);

        // Start the container
        motoContainer.start();

        // Set the endpoint
        endpoint = "http://" + motoContainer.getHost() + ":" + motoContainer.getMappedPort(5000);
        log.info("Moto endpoint: {}", endpoint);
    }

    /**
     * Constructor that initializes the registry.
     */
    public MotoSchemaRegistry() {
        start();
        updateSystemProperties();
    }

    /**
     * Update system properties with the Moto endpoint.
     * This is necessary for the AWS SDK to find the Moto endpoint correctly.
     */
    private void updateSystemProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.putAll(Map.of(
                "aws.accessKeyId", "test",
                "aws.secretAccessKey", "test",
                "aws.sessionToken", "test-session",
                AWSSchemaRegistryConstants.AWS_ENDPOINT, endpoint,
                "aws.endpoint", endpoint,
                "kafka.consumers.default.aws.endpoint", endpoint,
                "kafka.producers.default.aws.endpoint", endpoint,
                "software.amazon.awssdk.regions.region", "us-east-1",
                "aws.region", "us-east-1",
                "software.amazon.awssdk.endpoints.endpoint-url", endpoint));
        properties.putAll(
                Map.of("software.amazon.awssdk.glue.endpoint", endpoint,
                "software.amazon.awssdk.glue.endpoint-url", endpoint,
                "aws.glue.endpoint", endpoint,
                "aws.serviceEndpoint", endpoint,
                "aws.endpointUrl", endpoint,
                "aws.endpointDiscoveryEnabled", "false"
                ));

        properties.forEach(System::setProperty);
    }

    public @NonNull String getEndpoint() {
        return endpoint;
    }

    public @NonNull String getRegistryName() {
        return REGISTRY_NAME;
    }

    public void start() {
        if (!initialized) {
            initializeRegistry();
            initialized = true;
        }
    }

    public boolean isRunning() {
        return motoContainer.isRunning();
    }

    /**
     * Initialize the Glue registry.
     */
    private void initializeRegistry() {
        GlueClient glueClient = GlueClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();

        try {
            CreateRegistryRequest createRegistryRequest = CreateRegistryRequest.builder()
                    .registryName(REGISTRY_NAME)
                    .description("Default registry for integration tests")
                    .build();
            CreateRegistryResponse response = glueClient.createRegistry(createRegistryRequest);
            log.info("Registry created: {}", response.registryArn());
        } catch (EntityNotFoundException e) {
            log.info("Registry '{}' already exists.", REGISTRY_NAME);
        } catch (Exception e) {
            log.error("Failed to create registry: {}", e.getMessage());
            throw new RuntimeException("Registry creation failed", e);
        }
    }

    public @NonNull Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>();

        // AWS credentials and region
        props.put("aws.accessKeyId", "test");
        props.put("aws.secretAccessKey", "test");
        props.put("aws.sessionToken", "test-session");
        props.put("aws.region", "us-east-1");

        // AWS endpoints
        props.put(AWSSchemaRegistryConstants.AWS_ENDPOINT, endpoint);
        props.put("aws.endpoint", endpoint);
        props.put("software.amazon.awssdk.regions.region", "us-east-1");
        props.put("software.amazon.awssdk.endpoints.endpoint-url", endpoint);
        props.put("software.amazon.awssdk.glue.endpoint", endpoint);
        props.put("software.amazon.awssdk.glue.endpoint-url", endpoint);
        props.put("aws.glue.endpoint", endpoint);
        props.put("aws.serviceEndpoint", endpoint);
        props.put("aws.endpointUrl", endpoint);
        props.put("aws.endpointDiscoveryEnabled", "false");

        // Kafka producer AWS Glue Schema Registry properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + AWSSchemaRegistryConstants.AWS_REGION, "us-east-1");
        props.put(producerPrefix + AWSSchemaRegistryConstants.AWS_ENDPOINT, endpoint);
        props.put(producerPrefix + AWSSchemaRegistryConstants.REGISTRY_NAME, getRegistryName());
        props.put(producerPrefix + AWSSchemaRegistryConstants.DATA_FORMAT, "PROTOBUF");
        props.put(producerPrefix + AWSSchemaRegistryConstants.PROTOBUF_MESSAGE_TYPE, "POJO");
        props.put(producerPrefix + AWSSchemaRegistryConstants.COMPATIBILITY_SETTING, "FULL");
        props.put(producerPrefix + AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, "true");

        // Kafka consumer AWS Glue Schema Registry properties
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + AWSSchemaRegistryConstants.AWS_REGION, "us-east-1");
        props.put(consumerPrefix + AWSSchemaRegistryConstants.AWS_ENDPOINT, endpoint);
        props.put(consumerPrefix + AWSSchemaRegistryConstants.REGISTRY_NAME, getRegistryName());
        props.put(consumerPrefix + AWSSchemaRegistryConstants.DATA_FORMAT, "PROTOBUF");
        props.put(consumerPrefix + AWSSchemaRegistryConstants.PROTOBUF_MESSAGE_TYPE, "POJO");
        props.put(consumerPrefix + AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, "true");
        props.put(consumerPrefix + AWSSchemaRegistryConstants.COMPATIBILITY_SETTING, "FULL");

        return props;
    }

    public @NonNull String getSerializerClass() {
        return GlueSchemaRegistryKafkaSerializer.class.getName();
    }

    public @NonNull String getDeserializerClass() {
        return GlueSchemaRegistryKafkaDeserializer.class.getName();
    }
}