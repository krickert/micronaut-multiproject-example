package com.krickert.testcontainers.moto;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// No longer need GenericContainer directly here, as MotoContainer extends it.
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn a Moto server test container
 * using the MotoContainer class.
 * It provides properties for AWS service emulation, particularly for Glue Schema Registry.
 */
public class MotoTestResourceProvider extends AbstractTestContainersProvider<MotoContainer> { // Changed to MotoContainer
    private static final Logger LOG = LoggerFactory.getLogger(MotoTestResourceProvider.class);

    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_MOTO_ENABLED = TESTCONTAINERS_PREFIX + ".moto";

    // Moto Properties (often used as AWS service endpoint)
    public static final String GLUE_PREFIX = "glue"; // Example prefix, could be generic AWS
    public static final String PROPERTY_MOTO_REGISTRY_URL = GLUE_PREFIX + ".registry.url"; // Example specific property
    public static final String PROPERTY_MOTO_REGISTRY_NAME = GLUE_PREFIX + ".registry.name"; // Example specific property

    // AWS Generic Properties
    public static final String PROPERTY_AWS_ACCESS_KEY = "aws.access-key-id";
    public static final String PROPERTY_AWS_SECRET_KEY = "aws.secret-access-key";
    public static final String PROPERTY_AWS_SESSION_TOKEN = "aws.session-token";
    public static final String PROPERTY_AWS_REGION = "aws.region";
    public static final String PROPERTY_AWS_ENDPOINT = "aws.endpoint"; // Generic AWS endpoint

    // AWS SDK Specific Properties (often used by AWS SDK v2)
    public static final String PROPERTY_AWS_SDK_REGION = "software.amazon.awssdk.regions.region";
    public static final String PROPERTY_AWS_SDK_ENDPOINT_URL = "software.amazon.awssdk.endpoints.endpoint-url"; // Generic SDK endpoint override
    public static final String PROPERTY_AWS_SDK_GLUE_ENDPOINT = "software.amazon.awssdk.glue.endpoint"; // SDK Glue specific endpoint (less common, usually endpoint-url is used)
    public static final String PROPERTY_AWS_SDK_GLUE_ENDPOINT_URL = "software.amazon.awssdk.glue.endpoint-url"; // SDK Glue specific endpoint override

    // Other AWS related properties that might point to Moto
    public static final String PROPERTY_AWS_GLUE_ENDPOINT = "aws.glue.endpoint"; // Another form for Glue endpoint
    public static final String PROPERTY_AWS_SERVICE_ENDPOINT = "aws.service-endpoint"; // Generic service endpoint
    public static final String PROPERTY_AWS_ENDPOINT_URL = "aws.endpoint-url"; // Yet another generic endpoint URL form
    public static final String PROPERTY_AWS_ENDPOINT_DISCOVERY_ENABLED = "aws.endpoint-discover-enabled";

    // Kafka Producer AWS Properties (for AWS Glue Schema Registry via Kafka Connect or clients)
    public static final String KAFKA_PREFIX = "kafka";
    public static final String PRODUCER_PREFIX = KAFKA_PREFIX + ".producers.default";
    public static final String CONSUMER_PREFIX = KAFKA_PREFIX + ".consumers.default";

    public static final String PROPERTY_PRODUCER_AWS_REGION = PRODUCER_PREFIX + ".avro.registry.region";
    public static final String PROPERTY_PRODUCER_AWS_ENDPOINT = PRODUCER_PREFIX + ".avro.registry.url";
    public static final String PROPERTY_PRODUCER_REGISTRY_NAME = PRODUCER_PREFIX + ".registry.name";
    public static final String PROPERTY_PRODUCER_DATA_FORMAT = PRODUCER_PREFIX + ".data.format";
    public static final String PROPERTY_PRODUCER_PROTOBUF_MESSAGE_TYPE = PRODUCER_PREFIX + ".protobuf.message.type";
    public static final String PROPERTY_PRODUCER_COMPATIBILITY = PRODUCER_PREFIX + ".compatibility";
    public static final String PROPERTY_PRODUCER_AUTO_REGISTRATION = PRODUCER_PREFIX + ".auto.registration";

    // Kafka Consumer AWS Properties
    public static final String PROPERTY_CONSUMER_AWS_REGION = CONSUMER_PREFIX + ".avro.registry.region";
    public static final String PROPERTY_CONSUMER_AWS_ENDPOINT = CONSUMER_PREFIX + ".avro.registry.url";
    public static final String PROPERTY_CONSUMER_REGISTRY_NAME = CONSUMER_PREFIX + ".registry.name";
    public static final String PROPERTY_CONSUMER_DATA_FORMAT = CONSUMER_PREFIX + ".data-format";
    public static final String PROPERTY_CONSUMER_PROTOBUF_MESSAGE_TYPE = CONSUMER_PREFIX + ".protobuf.message.type";
    public static final String PROPERTY_CONSUMER_COMPATIBILITY = CONSUMER_PREFIX + ".compatibility";
    public static final String PROPERTY_CONSUMER_AUTO_REGISTRATION = CONSUMER_PREFIX + ".auto.registration";

    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_MOTO_REGISTRY_URL,
            PROPERTY_MOTO_REGISTRY_NAME,
            PROPERTY_AWS_ACCESS_KEY,
            PROPERTY_AWS_SECRET_KEY,
            PROPERTY_AWS_SESSION_TOKEN,
            PROPERTY_AWS_REGION,
            PROPERTY_AWS_ENDPOINT,
            PROPERTY_AWS_SDK_REGION,
            PROPERTY_AWS_SDK_ENDPOINT_URL,
            PROPERTY_AWS_SDK_GLUE_ENDPOINT,
            PROPERTY_AWS_SDK_GLUE_ENDPOINT_URL,
            PROPERTY_AWS_GLUE_ENDPOINT,
            PROPERTY_AWS_SERVICE_ENDPOINT,
            PROPERTY_AWS_ENDPOINT_URL,
            PROPERTY_AWS_ENDPOINT_DISCOVERY_ENABLED,
            PROPERTY_PRODUCER_AWS_REGION,
            PROPERTY_PRODUCER_AWS_ENDPOINT,
            PROPERTY_PRODUCER_REGISTRY_NAME,
            PROPERTY_PRODUCER_DATA_FORMAT,
            PROPERTY_PRODUCER_PROTOBUF_MESSAGE_TYPE,
            PROPERTY_PRODUCER_COMPATIBILITY,
            PROPERTY_PRODUCER_AUTO_REGISTRATION,
            PROPERTY_CONSUMER_AWS_REGION,
            PROPERTY_CONSUMER_AWS_ENDPOINT,
            PROPERTY_CONSUMER_REGISTRY_NAME,
            PROPERTY_CONSUMER_DATA_FORMAT,
            PROPERTY_CONSUMER_PROTOBUF_MESSAGE_TYPE,
            PROPERTY_CONSUMER_COMPATIBILITY,
            PROPERTY_CONSUMER_AUTO_REGISTRATION
    ));

    // These can be sourced from MotoContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString()
    // and MotoContainer.MOTO_HTTP_PORT if preferred, to centralize defaults.
    public static final String DEFAULT_IMAGE = "motoserver/moto:latest";
    public static final String SIMPLE_NAME = "moto-server";
    public static final String DISPLAY_NAME = "Moto Server";
    public static final String DEFAULT_REGISTRY_NAME = "default"; // For Glue Schema Registry name

    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        return RESOLVABLE_PROPERTIES_LIST;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    protected String getSimpleName() {
        return SIMPLE_NAME;
    }

    @Override
    protected String getDefaultImageName() {
        // return MotoContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString(); // Alternative
        return DEFAULT_IMAGE;
    }

    @Override
    protected MotoContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        LOG.info("Creating Moto container with image: {}", imageName);
        // Instantiate MotoContainer directly.
        // The MotoContainer constructor handles its specific setup (exposed ports, env, command, wait strategy).
        // The withStartupTimeout from the previous GenericContainer setup is now part of MotoContainer's WaitStrategy.
        // withReuse(false) is generally handled by the test resources framework.
        return new MotoContainer(imageName);
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, MotoContainer container) { // Changed to MotoContainer
        LOG.debug("Resolving property '{}' for Moto container: {}", propertyName, container.getContainerName());

        // Use the helper method from MotoContainer
        String endpoint = container.getEndpointUrl();
        Optional<String> resolvedValue = Optional.empty();

        // Resolve Moto/AWS endpoint URL properties
        if (PROPERTY_MOTO_REGISTRY_URL.equals(propertyName) ||
            PROPERTY_AWS_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_SDK_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_AWS_SDK_GLUE_ENDPOINT.equals(propertyName) || // Less common, usually endpoint-url is used
            PROPERTY_AWS_SDK_GLUE_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_AWS_GLUE_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_SERVICE_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_PRODUCER_AWS_ENDPOINT.equals(propertyName) ||
            PROPERTY_CONSUMER_AWS_ENDPOINT.equals(propertyName)) {
            resolvedValue = Optional.of(endpoint);
        }
        // Resolve registry name (for AWS Glue Schema Registry)
        else if (PROPERTY_MOTO_REGISTRY_NAME.equals(propertyName) ||
            PROPERTY_PRODUCER_REGISTRY_NAME.equals(propertyName) ||
            PROPERTY_CONSUMER_REGISTRY_NAME.equals(propertyName)) {
            resolvedValue = Optional.of(DEFAULT_REGISTRY_NAME);
        }
        // Resolve AWS credentials (Moto uses dummy credentials by default)
        else if (PROPERTY_AWS_ACCESS_KEY.equals(propertyName)) {
            resolvedValue = Optional.of("test"); // Standard Moto dummy access key
        }
        else if (PROPERTY_AWS_SECRET_KEY.equals(propertyName)) {
            resolvedValue = Optional.of("test"); // Standard Moto dummy secret key
        }
        else if (PROPERTY_AWS_SESSION_TOKEN.equals(propertyName)) {
            resolvedValue = Optional.of("test-session"); // Standard Moto dummy session token
        }
        // Resolve AWS region
        else if (PROPERTY_AWS_REGION.equals(propertyName) ||
            PROPERTY_AWS_SDK_REGION.equals(propertyName) ||
            PROPERTY_PRODUCER_AWS_REGION.equals(propertyName) ||
            PROPERTY_CONSUMER_AWS_REGION.equals(propertyName)) {
            resolvedValue = Optional.of("us-east-1"); // Common default/dummy region
        }
        // Resolve endpoint discovery (should be disabled for local endpoints like Moto)
        else if (PROPERTY_AWS_ENDPOINT_DISCOVERY_ENABLED.equals(propertyName)) {
            resolvedValue = Optional.of("false");
        }
        // Resolve Kafka SerDe data format (if using Glue Schema Registry with Kafka)
        else if (PROPERTY_PRODUCER_DATA_FORMAT.equals(propertyName) ||
            PROPERTY_CONSUMER_DATA_FORMAT.equals(propertyName)) {
            resolvedValue = Optional.of("PROTOBUF"); // Example default, adjust as needed
        }
        // Resolve Kafka SerDe Protobuf message type
        else if (PROPERTY_PRODUCER_PROTOBUF_MESSAGE_TYPE.equals(propertyName) ||
            PROPERTY_CONSUMER_PROTOBUF_MESSAGE_TYPE.equals(propertyName)) {
            resolvedValue = Optional.of("POJO"); // Example default
        }
        // Resolve Kafka SerDe compatibility
        else if (PROPERTY_PRODUCER_COMPATIBILITY.equals(propertyName) ||
            PROPERTY_CONSUMER_COMPATIBILITY.equals(propertyName)) {
            resolvedValue = Optional.of("FULL"); // Example default
        }
        // Resolve Kafka SerDe auto registration
        else if (PROPERTY_PRODUCER_AUTO_REGISTRATION.equals(propertyName) ||
            PROPERTY_CONSUMER_AUTO_REGISTRATION.equals(propertyName)) {
            resolvedValue = Optional.of("true"); // Example default
        }

        if (resolvedValue.isPresent()) {
            LOG.info("MotoTestResourceProvider resolved property '{}' to '{}'", propertyName, resolvedValue.get());
        }
        return resolvedValue;
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        boolean canAnswer = propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
        if (canAnswer) {
            LOG.debug("MotoTestResourceProvider will attempt to answer for property: {}", propertyName);
        }
        return canAnswer;
    }
}