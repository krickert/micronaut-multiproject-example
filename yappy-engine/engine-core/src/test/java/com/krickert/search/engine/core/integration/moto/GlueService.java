package com.krickert.search.engine.core.integration.moto;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.net.URI;

@Requires(property = "aws.glue.endpoint")
@Requires(property = "kafka.enabled", value = "true")
@Requires(property = "glue.enabled", value = "true")
@Singleton
public class GlueService {
    private static final Logger log = LoggerFactory.getLogger(GlueService.class);

    // Registry type identifier
    private static final String REGISTRY_TYPE = "moto";
    private static final String REGISTRY_NAME = "default";
    private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

    private final String glueEndpoint;


    public GlueService(@Value("${aws.glue.endpoint}") String glueEndpoint) {
        this.glueEndpoint = glueEndpoint;
    }

    /**
     * Delete the Moto registry.
     * Uses configuration provided by TestContainerManager.
     */
    public void deleteRegistry() {
        log.info("Deleting Moto registry '{}' via endpoint: {}", REGISTRY_NAME, glueEndpoint);
        try (GlueClient glueClient = createGlueClient()) {
            try {
                // Delete registry if it exists
                glueClient.deleteRegistry(DeleteRegistryRequest.builder()
                        .registryId(id -> id.registryName(REGISTRY_NAME))
                        .build());
                log.info("Deleted registry '{}'", REGISTRY_NAME);
            } catch (EntityNotFoundException e) {
                log.info("Registry '{}' does not exist, nothing to delete.", REGISTRY_NAME);
            }
        } catch (Exception e) {
            log.error("Error deleting Moto registry '{}': {}", REGISTRY_NAME, e.getMessage(), e);
            // Log error but continue, maybe cleanup failed but tests might still run
        }
    }

    /**
     * Create a Glue client configured using properties from TestContainerManager.
     *
     * @return the Glue client
     */

    private GlueClient createGlueClient() {
        // Get dynamically from manager
        String region = "us-east-1";
        String accessKey = "test";
        String secretKey = "test";

        return GlueClient.builder()
                .endpointOverride(URI.create(glueEndpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .build();
    }

    /**
     * Initialize the Moto registry (create if not exists).
     * Uses configuration provided by TestContainerManager.
     */
    public void initializeRegistry() {
        String registryName = REGISTRY_NAME;
        log.info("Initializing Moto registry '{}' via endpoint: {}", registryName, glueEndpoint);
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
     * Resets the Moto registry state by deleting and recreating the registry.
     * This is useful for ensuring test isolation.
     */
    public void resetGlue() {
        log.info("Resetting Moto registry state...");
        // Stopping/starting containers is handled globally by TestContainerManager's shutdown hook.
        // Reset here means cleaning up registry state specific to Moto.
        deleteRegistry();
        initializeRegistry();
        log.info("Glue registry reset complete.");
    }

}
