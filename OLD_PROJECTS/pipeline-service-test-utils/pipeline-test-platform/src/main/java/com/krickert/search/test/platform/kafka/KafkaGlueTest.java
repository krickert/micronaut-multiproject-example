package com.krickert.search.test.platform.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.net.URI;
// Removed testcontainers/DockerImageName imports as TestContainerManager handles containers

/**
 * Implementation of KafkaTest using AWS Glue Schema Registry (via Moto), managed by TestContainerManager.
 * This class interacts with the Glue registry (e.g., for reset) using configuration
 * provided by the central TestContainerManager.
 */
public class KafkaGlueTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaGlueTest.class);

    // Registry type identifier
    private static final String REGISTRY_TYPE = "glue";

    // --- Container Management Delegated to TestContainerManager ---
    // REMOVE: motoContainer field and its definition

    // Property key expected from TestContainerManager for the Glue registry endpoint
    // Ensure TestContainerManager sets a property with this key.
    private static final String GLUE_REGISTRY_ENDPOINT_PROP = "aws.glue.endpoint";
    // Property key TestContainerManager might use for AWS region
    private static final String GLUE_AWS_REGION_PROP = "aws.region"; // Assuming TestContainerManager provides region
    // Property key TestContainerManager might use for AWS access key
    private static final String GLUE_AWS_ACCESS_KEY_PROP = "aws.accessKeyId";
    // Property key TestContainerManager might use for AWS secret key
    private static final String GLUE_AWS_SECRET_KEY_PROP = "aws.secretKey";
    // Property key TestContainerManager might use for Registry Name
    private static final String GLUE_REGISTRY_NAME_PROP = "registry.name"; // Assuming TestContainerManager provides registry name

    // Default return class for the deserializer (can be overridden)
    // TestContainerManager should use this value when configuring the Kafka consumer properties
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream"; // Adjust if needed


    // The specific class to deserialize into for this test instance
    private final String returnClass;

    /**
     * Constructor that initializes the test with a specific return class.
     * Informs TestContainerManager about the required registry type and deserializer class.
     *
     * @param returnClass the return class for the deserializer
     */
    public KafkaGlueTest(String returnClass) {
        this.returnClass = returnClass;
        log.debug("Initializing KafkaGlueTest, ensuring TestContainerManager knows type is '{}'", REGISTRY_TYPE);
        // Inform the manager about the registry type needed
        containerManager.setProperty(TestContainerManager.KAFKA_REGISTRY_TYPE_PROP, REGISTRY_TYPE);
        // Provide the return class as a hint for TestContainerManager to configure the consumer
    }

    /**
     * Default constructor that initializes the test with the default return class.
     */
    public KafkaGlueTest() {
        this(DEFAULT_RETURN_CLASS);
    }

    @Override
    public String getRegistryType() {
        return REGISTRY_TYPE;
    }

    /**
     * Retrieves the Glue registry endpoint from the central TestContainerManager.
     *
     * @return The Glue registry endpoint URL.
     * @throws IllegalStateException if the property is not found in TestContainerManager.
     */
    @Override
    public String getRegistryEndpoint() {
        String endpoint = containerManager.getProperties().get(GLUE_REGISTRY_ENDPOINT_PROP);
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("Glue registry endpoint ('{}') not found in TestContainerManager properties. Forcing manager init.", GLUE_REGISTRY_ENDPOINT_PROP);
            TestContainerManager.getInstance(); // Ensure init
            endpoint = containerManager.getProperties().get(GLUE_REGISTRY_ENDPOINT_PROP);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("Glue Registry endpoint property ('" + GLUE_REGISTRY_ENDPOINT_PROP + "') not found in TestContainerManager properties after retry.");
            }
        }
        log.trace("Retrieved Glue endpoint: {}", endpoint);
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
        log.trace("Checked container status via TestContainerManager for Kafka, Glue, Consul: {}", running);
        return running;
    }

    /**
     * Resets the Glue registry state by deleting and recreating the registry.
     * This is useful for ensuring test isolation.
     */
    @Override
    public void resetContainers() {
        log.info("Resetting Glue registry state...");
        // Stopping/starting containers is handled globally by TestContainerManager's shutdown hook.
        // Reset here means cleaning up registry state specific to Glue.
        deleteRegistry();
        initializeRegistry();
        // No need to clear/reset properties in containerManager here, as that's central.
        log.info("Glue registry reset complete.");
    }

    // --- Glue Registry Specific Methods ---

    /**
     * Initialize the Glue registry (create if not exists).
     * Uses configuration provided by TestContainerManager.
     */
    private void initializeRegistry() {
        String registryName = getRegistryNameOrFail();
        log.info("Initializing Glue registry '{}' via endpoint: {}", registryName, getRegistryEndpoint());
        try (GlueClient glueClient = createGlueClient()) {
            try {
                // Check if registry exists
                glueClient.getRegistry(GetRegistryRequest.builder() // Use correct builder pattern
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
            log.error("Error initializing Glue registry '{}': {}", registryName, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Glue registry: " + registryName, e);
        }
    }

    /**
     * Delete the Glue registry.
     * Uses configuration provided by TestContainerManager.
     */
    private void deleteRegistry() {
        String registryName = getRegistryNameOrFail();
        log.info("Deleting Glue registry '{}' via endpoint: {}", registryName, getRegistryEndpoint());
        try (GlueClient glueClient = createGlueClient()) {
            try {
                // Delete registry if it exists
                glueClient.deleteRegistry(DeleteRegistryRequest.builder() // Use correct builder pattern
                        .registryId(id -> id.registryName(registryName))
                        .build());
                log.info("Deleted registry '{}'", registryName);
            } catch (EntityNotFoundException e) {
                log.info("Registry '{}' does not exist, nothing to delete.", registryName);
            }
        } catch (Exception e) {
            log.error("Error deleting Glue registry '{}': {}", registryName, e.getMessage(), e);
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
        String region = containerManager.getProperties().getOrDefault(GLUE_AWS_REGION_PROP, "us-east-1"); // Get from manager or default
        String accessKey = containerManager.getProperties().get(GLUE_AWS_ACCESS_KEY_PROP); // Get from manager
        String secretKey = containerManager.getProperties().get(GLUE_AWS_SECRET_KEY_PROP); // Get from manager

        if (accessKey == null || secretKey == null) {
            log.warn("AWS Credentials (accessKeyId/secretKey) not found in TestContainerManager properties. Glue operations might fail if not implicitly configured.");
            // Depending on environment, implicit credentials might work, but for Moto it's safer to provide them.
            // Throw error if explicitly needed for Moto
            throw new IllegalStateException("AWS Credentials ('" + GLUE_AWS_ACCESS_KEY_PROP + "', '" + GLUE_AWS_SECRET_KEY_PROP + "') are required for GlueClient but not found in TestContainerManager properties.");
        }

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
        String registryName = containerManager.getProperties().get(GLUE_REGISTRY_NAME_PROP);
        if (registryName == null || registryName.isBlank()) {
            log.error("Glue registry name ('{}') not found in TestContainerManager properties.", GLUE_REGISTRY_NAME_PROP);
            throw new IllegalStateException("Required property '" + GLUE_REGISTRY_NAME_PROP + "' not found in TestContainerManager properties.");
        }
        return registryName;
    }

    // REMOVE: setupProperties method - Handled by TestContainerManager
    // REMOVE: updateSystemProperties method - Handled by TestContainerManager via client config
    // REMOVE: registryEndpoint field - Use getRegistryEndpoint()
}