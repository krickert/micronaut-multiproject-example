package com.krickert.testcontainers.moto;
import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn a Moto server test container.
 * It provides properties for AWS Glue Schema Registry emulation.
 */
public class MotoTestResourceProvider extends AbstractTestContainersProvider<GenericContainer<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(MotoTestResourceProvider.class);

    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_MOTO_ENABLED = TESTCONTAINERS_PREFIX + ".moto";

    // Moto Properties
    public static final String MOTO_PREFIX = "moto";
    public static final String PROPERTY_MOTO_REGISTRY_URL = MOTO_PREFIX + ".registry.url";
    public static final String PROPERTY_MOTO_REGISTRY_NAME = MOTO_PREFIX + ".registry.name";

    // AWS Properties
    public static final String PROPERTY_AWS_ACCESS_KEY = "aws.access-key-id";
    public static final String PROPERTY_AWS_SECRET_KEY = "aws.secret-access-key";
    public static final String PROPERTY_AWS_SESSION_TOKEN = "aws.session-token";
    public static final String PROPERTY_AWS_REGION = "aws.region";
    public static final String PROPERTY_AWS_ENDPOINT = "aws.endpoint";

    // AWS SDK Properties
    public static final String PROPERTY_AWS_SDK_REGION = "software.amazon.awssdk.regions.region";
    public static final String PROPERTY_AWS_SDK_ENDPOINT_URL = "software.amazon.awssdk.endpoints.endpoint-url";
    public static final String PROPERTY_AWS_SDK_GLUE_ENDPOINT = "software.amazon.awssdk.glue.endpoint";
    public static final String PROPERTY_AWS_SDK_GLUE_ENDPOINT_URL = "software.amazon.awssdk.glue.endpoint-url";

    // AWS Glue Properties
    public static final String PROPERTY_AWS_GLUE_ENDPOINT = "aws.glue.endpoint";
    public static final String PROPERTY_AWS_SERVICE_ENDPOINT = "aws.service-endpoint";
    public static final String PROPERTY_AWS_ENDPOINT_URL = "aws.endpoint-url";
    public static final String PROPERTY_AWS_ENDPOINT_DISCOVERY_ENABLED = "aws.endpoint-discover-enabled";

    // Kafka Producer AWS Properties
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

    // Combined list of properties this provider can resolve
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

    public static final String DEFAULT_IMAGE = "motoserver/moto:latest";
    public static final int MOTO_PORT = 5000;
    public static final String SIMPLE_NAME = "moto-server";
    public static final String DISPLAY_NAME = "Moto Server";
    public static final String DEFAULT_REGISTRY_NAME = "default";

    /**
     * Checks if this container is enabled based on configuration.
     *
     * @param testResourcesConfig the test resources configuration
     * @return true if the container is enabled, false otherwise
     */
    protected boolean isContainerEnabled(Map<String, Object> testResourcesConfig) {
        // Check if testcontainers are globally enabled
        Object globalEnabled = testResourcesConfig.get(PROPERTY_TESTCONTAINERS_ENABLED);
        if (globalEnabled != null) {
            if (globalEnabled instanceof Boolean) {
                if (!(Boolean) globalEnabled) {
                    LOG.debug("Test containers are globally disabled via {}", PROPERTY_TESTCONTAINERS_ENABLED);
                    return false;
                }
            } else if (globalEnabled instanceof String) {
                if ("false".equalsIgnoreCase((String) globalEnabled)) {
                    LOG.debug("Test containers are globally disabled via {}", PROPERTY_TESTCONTAINERS_ENABLED);
                    return false;
                }
            }
        }

        // Check if this specific container is enabled
        Object motoEnabled = testResourcesConfig.get(PROPERTY_TESTCONTAINERS_MOTO_ENABLED);
        if (motoEnabled != null) {
            if (motoEnabled instanceof Boolean) {
                return (Boolean) motoEnabled;
            } else if (motoEnabled instanceof String) {
                return Boolean.parseBoolean((String) motoEnabled);
            } else if (motoEnabled instanceof Map) {
                // Check if there's an 'enabled' property in the map
                @SuppressWarnings("unchecked")
                Map<String, Object> enabledMap = (Map<String, Object>) motoEnabled;
                Object enabledValue = enabledMap.get("enabled");
                if (enabledValue != null) {
                    if (enabledValue instanceof Boolean) {
                        return (Boolean) enabledValue;
                    } else if (enabledValue instanceof String) {
                        return Boolean.parseBoolean((String) enabledValue);
                    }
                }
                // If there's no 'enabled' property, but the map exists, consider it enabled
                return true;
            }
        }

        // Default to enabled
        return true;
    }

    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Moto container is disabled, returning empty list of resolvable properties");
            return Collections.emptyList();
        }
        // Return all properties we can resolve
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
        return DEFAULT_IMAGE;
    }

    @Override
    protected GenericContainer<?> createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Moto container is disabled, not creating container");
            return null;
        }
        // Create a new Moto server container with the specified image
        return new GenericContainer<>(imageName)
                .withExposedPorts(MOTO_PORT)
                .withAccessToHost(true)
                .withCommand("-H0.0.0.0")
                .withEnv(Map.of(
                        "MOTO_SERVICE", "glue",
                        "TEST_SERVER_MODE", "true"
                ))
                .withStartupTimeout(java.time.Duration.ofSeconds(30))
                .withReuse(false);
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        // Get the Moto endpoint URL
        String endpoint = String.format("http://%s:%d",
                container.getHost(),
                container.getMappedPort(MOTO_PORT));

        // Resolve Moto registry URL property
        if (PROPERTY_MOTO_REGISTRY_URL.equals(propertyName) ||
            PROPERTY_AWS_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_SDK_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_AWS_SDK_GLUE_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_SDK_GLUE_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_AWS_GLUE_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_SERVICE_ENDPOINT.equals(propertyName) ||
            PROPERTY_AWS_ENDPOINT_URL.equals(propertyName) ||
            PROPERTY_PRODUCER_AWS_ENDPOINT.equals(propertyName) ||
            PROPERTY_CONSUMER_AWS_ENDPOINT.equals(propertyName)) {
            return Optional.of(endpoint);
        }

        // Resolve registry name
        if (PROPERTY_MOTO_REGISTRY_NAME.equals(propertyName) ||
            PROPERTY_PRODUCER_REGISTRY_NAME.equals(propertyName) ||
            PROPERTY_CONSUMER_REGISTRY_NAME.equals(propertyName)) {
            return Optional.of(DEFAULT_REGISTRY_NAME);
        }

        // Resolve AWS credentials
        if (PROPERTY_AWS_ACCESS_KEY.equals(propertyName)) {
            return Optional.of("test");
        }
        if (PROPERTY_AWS_SECRET_KEY.equals(propertyName)) {
            return Optional.of("test");
        }
        if (PROPERTY_AWS_SESSION_TOKEN.equals(propertyName)) {
            return Optional.of("test-session");
        }

        // Resolve AWS region
        if (PROPERTY_AWS_REGION.equals(propertyName) ||
            PROPERTY_AWS_SDK_REGION.equals(propertyName) ||
            PROPERTY_PRODUCER_AWS_REGION.equals(propertyName) ||
            PROPERTY_CONSUMER_AWS_REGION.equals(propertyName)) {
            return Optional.of("us-east-1");
        }

        // Resolve endpoint discovery
        if (PROPERTY_AWS_ENDPOINT_DISCOVERY_ENABLED.equals(propertyName)) {
            return Optional.of("false");
        }

        // Resolve data format
        if (PROPERTY_PRODUCER_DATA_FORMAT.equals(propertyName) ||
            PROPERTY_CONSUMER_DATA_FORMAT.equals(propertyName)) {
            return Optional.of("PROTOBUF");
        }

        // Resolve protobuf message type
        if (PROPERTY_PRODUCER_PROTOBUF_MESSAGE_TYPE.equals(propertyName) ||
            PROPERTY_CONSUMER_PROTOBUF_MESSAGE_TYPE.equals(propertyName)) {
            return Optional.of("POJO");
        }

        // Resolve compatibility
        if (PROPERTY_PRODUCER_COMPATIBILITY.equals(propertyName) ||
            PROPERTY_CONSUMER_COMPATIBILITY.equals(propertyName)) {
            return Optional.of("FULL");
        }

        // Resolve auto registration
        if (PROPERTY_PRODUCER_AUTO_REGISTRATION.equals(propertyName) ||
            PROPERTY_CONSUMER_AUTO_REGISTRATION.equals(propertyName)) {
            return Optional.of("true");
        }

        return Optional.empty(); // Property not handled by this provider
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Moto container is disabled, not answering property {}", propertyName);
            return false;
        }
        // Answer if the property is one we can resolve
        return propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
    }
}
