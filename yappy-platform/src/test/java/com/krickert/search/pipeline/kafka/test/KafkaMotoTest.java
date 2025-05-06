package com.krickert.search.pipeline.kafka.test;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.*;

import java.net.URI;
import java.util.Map;

/**
 * Implementation of KafkaTest using AWS Glue Schema Registry via Moto, managed by TestContainerManager.
 * This class interacts with the Moto registry (e.g., for reset) using configuration
 * provided by the central TestContainerManager.
 */
@Singleton
public class KafkaMotoTest implements TestPropertyProvider {
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

    private final GlueService glueService;

   /**
     * Constructor that initializes the test with a specific return class.
     * Informs TestContainerManager about the required registry type and deserializer class.
     *
     */
    public KafkaMotoTest(GlueService glueService) {
        this.glueService = glueService;
        log.debug("Initializing KafkaMotoTest, ensuring TestContainerManager knows type is '{}'", REGISTRY_TYPE);
        // Inform the manager about the registry type needed
    }

    public String getRegistryType() {
        return REGISTRY_TYPE;
    }


    /**
     * Allows dynamically providing properties for a test.
     *
     * @return A map of properties
     */
    @Override
    public @NonNull Map<String, String> getProperties() {
        return Map.of("registry.type", REGISTRY_TYPE);
    }
}