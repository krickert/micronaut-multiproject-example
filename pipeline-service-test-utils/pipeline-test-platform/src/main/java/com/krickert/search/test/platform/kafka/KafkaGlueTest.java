package com.krickert.search.test.platform.kafka;

import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
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
 * Implementation of KafkaTest using AWS Glue Schema Registry with Moto.
 * This class manages the Kafka and Moto containers and provides configuration for tests.
 */
public class KafkaGlueTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaGlueTest.class);

    // Registry type
    private static final String REGISTRY_TYPE = "glue";

    // Moto container
    private static final GenericContainer<?> motoContainer = new GenericContainer<>(DockerImageName.parse("motoserver/moto:latest"))
            .withExposedPorts(5000)
            .withAccessToHost(true)
            .withCommand("-H0.0.0.0")
            .withEnv(Map.of(
                    "MOTO_SERVICE", "glue",
                    "TEST_SERVER_MODE", "true"
            ))
            .withStartupTimeout(Duration.ofSeconds(30))
            .withReuse(false);

    // Registry endpoint
    private static String registryEndpoint;

    // AWS credentials
    private static final String AWS_ACCESS_KEY = "test";
    private static final String AWS_SECRET_KEY = "test";
    private static final String AWS_REGION = "us-east-1";

    // Registry name
    private static final String REGISTRY_NAME = "test-registry";

    // Default return class for the deserializer
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

    // Return class for the deserializer
    private final String returnClass;

    /**
     * Constructor that initializes the test with a specific return class.
     * 
     * @param returnClass the return class for the deserializer
     */
    public KafkaGlueTest(String returnClass) {
        this.returnClass = returnClass;
        // Set the registry type in the container manager
        containerManager.setProperty(TestContainerManager.KAFKA_REGISTRY_TYPE_PROP, REGISTRY_TYPE);
    }

    /**
     * Default constructor that initializes the test with the default return class.
     */
    public KafkaGlueTest() {
        this(DEFAULT_RETURN_CLASS);
    }

    /**
     * Get the registry type.
     * 
     * @return the registry type
     */
    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }

    /**
     * Get the registry endpoint.
     * 
     * @return the registry endpoint
     */
    @Override
    public String getRegistryEndpoint() {
        if (registryEndpoint == null) {
            startContainers();
        }
        return registryEndpoint;
    }

    /**
     * Start the Kafka and Moto containers.
     */
    @Override
    public void startContainers() {
        // Start Kafka if not already running
        if (!kafka.isRunning()) {
            log.info("Starting Kafka container...");
            kafka.start();
        }

        // Start Moto if not already running
        if (!motoContainer.isRunning()) {
            log.info("Starting Moto container...");
            motoContainer.start();

            // Set the registry endpoint
            registryEndpoint = "http://" + motoContainer.getHost() + ":" + motoContainer.getMappedPort(5000);
            log.info("Moto endpoint: {}", registryEndpoint);

            // Initialize the registry
            initializeRegistry();
        }

        // Set up properties
        setupProperties();
    }

    /**
     * Check if the containers are running.
     * 
     * @return true if both containers are running, false otherwise
     */
    @Override
    public boolean areContainersRunning() {
        return kafka.isRunning() && motoContainer.isRunning();
    }

    /**
     * Reset the containers between tests.
     */
    @Override
    public void resetContainers() {
        log.info("Resetting containers...");
        // Delete and recreate the registry
        deleteRegistry();
        initializeRegistry();
        // Clear the properties
        containerManager.clearProperties();
        // Set up properties again
        setupProperties();
    }

    /**
     * Initialize the Glue registry.
     */
    private void initializeRegistry() {
        log.info("Initializing Glue registry...");
        try {
            GlueClient glueClient = createGlueClient();

            try {
                // Check if registry exists
                glueClient.getRegistry(builder -> builder.registryId(id -> id.registryName(REGISTRY_NAME)));
                log.info("Registry '{}' already exists", REGISTRY_NAME);
            } catch (EntityNotFoundException e) {
                // Create registry if it doesn't exist
                CreateRegistryRequest request = CreateRegistryRequest.builder()
                        .registryName(REGISTRY_NAME)
                        .description("Test registry for Kafka tests")
                        .build();

                CreateRegistryResponse response = glueClient.createRegistry(request);
                log.info("Created registry '{}' with ARN: {}", REGISTRY_NAME, response.registryArn());
            }

            glueClient.close();
        } catch (Exception e) {
            log.error("Error initializing Glue registry: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Glue registry", e);
        }
    }

    /**
     * Delete the Glue registry.
     */
    private void deleteRegistry() {
        log.info("Deleting Glue registry...");
        try {
            GlueClient glueClient = createGlueClient();

            try {
                // Delete registry if it exists
                glueClient.deleteRegistry(builder -> builder.registryId(id -> id.registryName(REGISTRY_NAME)));
                log.info("Deleted registry '{}'", REGISTRY_NAME);
            } catch (EntityNotFoundException e) {
                log.info("Registry '{}' does not exist, nothing to delete", REGISTRY_NAME);
            }

            glueClient.close();
        } catch (Exception e) {
            log.error("Error deleting Glue registry: {}", e.getMessage(), e);
            // Continue even if deletion fails
        }
    }

    /**
     * Create a Glue client.
     * 
     * @return the Glue client
     */
    private GlueClient createGlueClient() {
        return GlueClient.builder()
                .endpointOverride(URI.create(registryEndpoint))
                .region(Region.of(AWS_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)
                ))
                .build();
    }

    /**
     * Set up the properties for the test.
     */
    private void setupProperties() {
        Map<String, String> props = new HashMap<>();

        // Set up common Kafka properties
        setupKafkaProperties(props);

        // Set up AWS system properties
        updateSystemProperties();

        // Set up Glue Schema Registry properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, GlueSchemaRegistryKafkaSerializer.class.getName());
        props.put(producerPrefix + AWSSchemaRegistryConstants.AWS_REGION, AWS_REGION);
        props.put(producerPrefix + AWSSchemaRegistryConstants.REGISTRY_NAME, REGISTRY_NAME);
        props.put(producerPrefix + AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, "true");
        props.put(producerPrefix + AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, "SPECIFIC_RECORD");

        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, GlueSchemaRegistryKafkaDeserializer.class.getName());
        props.put(consumerPrefix + AWSSchemaRegistryConstants.AWS_REGION, AWS_REGION);
        props.put(consumerPrefix + AWSSchemaRegistryConstants.REGISTRY_NAME, REGISTRY_NAME);
        props.put(consumerPrefix + AWSSchemaRegistryConstants.AVRO_RECORD_TYPE, "SPECIFIC_RECORD");

        // Add properties to container manager
        containerManager.setProperties(props);
    }

    /**
     * Update system properties for AWS.
     */
    private void updateSystemProperties() {
        // Set AWS system properties
        System.setProperty("aws.accessKeyId", AWS_ACCESS_KEY);
        System.setProperty("aws.secretKey", AWS_SECRET_KEY);
        System.setProperty("aws.region", AWS_REGION);
        System.setProperty("aws.disableEc2Metadata", "true");

        // Set endpoint override for Glue
        System.setProperty("aws.glue.endpoint", registryEndpoint);
    }
}
