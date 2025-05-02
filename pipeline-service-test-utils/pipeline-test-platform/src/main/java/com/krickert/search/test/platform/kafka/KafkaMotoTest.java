package com.krickert.search.test.platform.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.net.URI;

/**
 * Implementation of KafkaTest using AWS Glue Schema Registry via Moto, managed by TestContainerManager.
 * This class interacts with the Moto registry (e.g., for reset) using configuration
 * provided by the central TestContainerManager.
 */
public class KafkaMotoTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaMotoTest.class);

    // Registry type identifier
    private static final String REGISTRY_TYPE = "moto";

    // Property key expected from TestContainerManager for the Moto registry endpoint
    private static final String MOTO_REGISTRY_ENDPOINT_PROP = "moto.registry.url";
    // Property key TestContainerManager might use for AWS region
    private static final String MOTO_AWS_REGION_PROP = "aws.region";
    // Property key TestContainerManager might use for AWS access key
    private static final String MOTO_AWS_ACCESS_KEY_PROP = "aws.accessKeyId";
    // Property key TestContainerManager might use for AWS secret key
    private static final String MOTO_AWS_SECRET_KEY_PROP = "aws.secretAccessKey";
    // Property key TestContainerManager might use for Registry Name
    private static final String MOTO_REGISTRY_NAME_PROP = "moto.registry.name";

    // Default return class for the deserializer (can be overridden)
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

    // The specific class to deserialize into for this test instance
    private final String returnClass;

    /**
     * Constructor that initializes the test with a specific return class.
     * Informs TestContainerManager about the required registry type and deserializer class.
     *
     * @param returnClass the return class for the deserializer
     */
    public KafkaMotoTest(String returnClass) {
        this.returnClass = returnClass;
        log.debug("Initializing KafkaMotoTest, ensuring TestContainerManager knows type is '{}'", REGISTRY_TYPE);
        // Inform the manager about the registry type needed
        containerManager.setProperty(TestContainerManager.KAFKA_REGISTRY_TYPE_PROP, REGISTRY_TYPE);
    }

    /**
     * Default constructor that initializes the test with the default return class.
     */
    public KafkaMotoTest() {
        this(DEFAULT_RETURN_CLASS);
    }

    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }

    /**
     * Retrieves the Moto registry endpoint from the central TestContainerManager.
     *
     * @return The Moto registry endpoint URL.
     * @throws IllegalStateException if the property is not found in TestContainerManager.
     */
    @Override
    public String getRegistryEndpoint() {
        String endpoint = containerManager.getProperties().get(MOTO_REGISTRY_ENDPOINT_PROP);
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("Moto registry endpoint ('{}') not found in TestContainerManager properties. Forcing manager init.", MOTO_REGISTRY_ENDPOINT_PROP);
            TestContainerManager.getInstance(); // Ensure init
            endpoint = containerManager.getProperties().get(MOTO_REGISTRY_ENDPOINT_PROP);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Moto Registry endpoint property ('" + MOTO_REGISTRY_ENDPOINT_PROP + "') not found in TestContainerManager properties after retry.");
            }
        }
        log.trace("Retrieved Moto endpoint: {}", endpoint);
        return endpoint;
    }

    /**
     * Ensures that the TestContainerManager singleton is initialized,
     * which handles starting all necessary containers (Kafka, Moto, Consul).
     */
    @Override
    public void startContainers() {
        log.debug("Requesting container start via TestContainerManager.getInstance()...");
        // Getting the instance triggers the initialization logic within TestContainerManager
        TestContainerManager.getInstance();
        log.debug("TestContainerManager instance obtained, containers should be starting/running.");
        // Initialize the registry after ensuring containers are up
        initializeRegistry();
    }

    /**
     * Checks if the essential containers managed by TestContainerManager are running.
     */
    @Override
    public boolean areContainersRunning() {
        // Delegate check to TestContainerManager
        boolean running = containerManager.areEssentialContainersRunning("kafka", REGISTRY_TYPE, "consul");
        log.trace("Checked container status via TestContainerManager for Kafka, Moto, Consul: {}", running);
        return running;
    }

    /**
     * Resets the Moto registry state by deleting and recreating the registry.
     * This is useful for ensuring test isolation.
     */
    @Override
    public void resetContainers() {
        log.info("Resetting Moto registry state...");
        // Stopping/starting containers is handled globally by TestContainerManager's shutdown hook.
        // Reset here means cleaning up registry state specific to Moto.
        deleteRegistry();
        initializeRegistry();
        log.info("Moto registry reset complete.");
    }

    /**
     * Initialize the Moto registry (create if not exists).
     * Uses configuration provided by TestContainerManager.
     */
    private void initializeRegistry() {
        String registryName = getRegistryNameOrFail();
        log.info("Initializing Moto registry '{}' via endpoint: {}", registryName, getRegistryEndpoint());
        try (GlueClient glueClient = createGlueClient()) {
            try {
                // Check if registry exists
                glueClient.getRegistry(GetRegistryRequest.builder()
                        .registryId(id -> id.registryName(registryName))
                        .build());
                log.info("Registry '{}' already exists.", registryName);
            } catch (EntityNotFoundException e) {
                // Create registry if it doesn't exist
                log.info("Registry '{}' not found, creating...", registryName);
                CreateRegistryRequest request = CreateRegistryRequest.builder()
                        .registryName(registryName)
                        .description("Test registry for Kafka tests (managed by TestContainerManager)")
                        .build();

                CreateRegistryResponse response = glueClient.createRegistry(request);
                log.info("Created registry '{}' with ARN: {}", registryName, response.registryArn());
            }
        } catch (Exception e) {
            log.error("Error initializing Moto registry '{}': {}", registryName, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Moto registry: " + registryName, e);
        }
    }

    /**
     * Delete the Moto registry.
     * Uses configuration provided by TestContainerManager.
     */
    private void deleteRegistry() {
        String registryName = getRegistryNameOrFail();
        log.info("Deleting Moto registry '{}' via endpoint: {}", registryName, getRegistryEndpoint());
        try (GlueClient glueClient = createGlueClient()) {
            try {
                // Delete registry if it exists
                glueClient.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(id -> id.registryName(registryName))
                        .build());
                log.info("Deleted registry '{}'", registryName);
            } catch (EntityNotFoundException e) {
                log.info("Registry '{}' does not exist, nothing to delete.", registryName);
            }
        } catch (Exception e) {
            log.error("Error deleting Moto registry '{}': {}", registryName, e.getMessage(), e);
            // Log error but continue, maybe cleanup failed but tests might still run
        }
    }

    /**
     * Create a Glue client configured using properties from TestContainerManager.
     *
     * @return the Glue client
     */
    private GlueClient createGlueClient() {
        String endpoint = getRegistryEndpoint(); // Get dynamically from manager
        String region = containerManager.getProperties().getOrDefault(MOTO_AWS_REGION_PROP, "us-east-1");
        String accessKey = containerManager.getProperties().getOrDefault(MOTO_AWS_ACCESS_KEY_PROP, "test");
        String secretKey = containerManager.getProperties().getOrDefault(MOTO_AWS_SECRET_KEY_PROP, "test");

        return GlueClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .build();
    }

    /**
     * Helper to get the configured registry name from TestContainerManager properties.
     * @return The registry name.
     * @throws IllegalStateException if the property is not set.
     */
    private String getRegistryNameOrFail() {
        String registryName = containerManager.getProperties().get(MOTO_REGISTRY_NAME_PROP);
        if (registryName == null || registryName.isBlank()) {
            log.error("Moto registry name ('{}') not found in TestContainerManager properties.", MOTO_REGISTRY_NAME_PROP);
            throw new IllegalStateException("Required property '" + MOTO_REGISTRY_NAME_PROP + "' not found in TestContainerManager properties.");
        }
        return registryName;
    }
}