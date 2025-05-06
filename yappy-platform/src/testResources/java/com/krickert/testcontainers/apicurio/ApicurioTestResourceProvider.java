package com.krickert.testcontainers.apicurio;
import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn an Apicurio Registry test container.
 * It provides properties for the Apicurio Registry URL and related configuration.
 */
public class ApicurioTestResourceProvider extends AbstractTestContainersProvider<GenericContainer<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(ApicurioTestResourceProvider.class);

    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_APICURIO_ENABLED = TESTCONTAINERS_PREFIX + ".apicurio";

    // Apicurio Properties
    public static final String APICURIO_PREFIX = "apicurio";
    public static final String PROPERTY_APICURIO_REGISTRY_URL = APICURIO_PREFIX + ".registry.url";

    // Kafka SerDe Properties
    public static final String KAFKA_PREFIX = "kafka";
    public static final String PRODUCER_PREFIX = KAFKA_PREFIX + ".producers.default";
    public static final String CONSUMER_PREFIX = KAFKA_PREFIX + ".consumers.default";

    // SerDe Config Properties
    public static final String PROPERTY_PRODUCER_REGISTRY_URL = PRODUCER_PREFIX + ".registry.url";
    public static final String PROPERTY_PRODUCER_AUTO_REGISTER_ARTIFACT = PRODUCER_PREFIX + ".auto.register.artifact";
    public static final String PROPERTY_CONSUMER_REGISTRY_URL = CONSUMER_PREFIX + ".registry.url";

    // Combined list of properties this provider can resolve
    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_APICURIO_REGISTRY_URL,
            PROPERTY_PRODUCER_REGISTRY_URL,
            PROPERTY_PRODUCER_AUTO_REGISTER_ARTIFACT,
            PROPERTY_CONSUMER_REGISTRY_URL
    ));

    public static final String DEFAULT_IMAGE = "apicurio/apicurio-registry:3.0.7";
    public static final int APICURIO_PORT = 8080;
    public static final String SIMPLE_NAME = "apicurio-registry";
    public static final String DISPLAY_NAME = "Apicurio Registry";

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
        Object apicurioEnabled = testResourcesConfig.get(PROPERTY_TESTCONTAINERS_APICURIO_ENABLED);
        if (apicurioEnabled != null) {
            if (apicurioEnabled instanceof Boolean) {
                return (Boolean) apicurioEnabled;
            } else if (apicurioEnabled instanceof String) {
                return Boolean.parseBoolean((String) apicurioEnabled);
            } else if (apicurioEnabled instanceof Map) {
                // Check if there's an 'enabled' property in the map
                @SuppressWarnings("unchecked")
                Map<String, Object> enabledMap = (Map<String, Object>) apicurioEnabled;
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
            LOG.debug("Apicurio container is disabled, returning empty list of resolvable properties");
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
            LOG.debug("Apicurio container is disabled, not creating container");
            return null;
        }
        // Create a new Apicurio Registry container with the specified image
        return new GenericContainer<>(imageName)
                .withExposedPorts(APICURIO_PORT)
                .withEnv("QUARKUS_PROFILE", "prod")
                .withStartupTimeout(java.time.Duration.ofSeconds(120));
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, GenericContainer<?> container) {
        // Get the registry URL
        String registryUrl = String.format("http://%s:%d/apis/registry/v3",
                container.getHost(),
                container.getMappedPort(APICURIO_PORT));

        // Resolve Apicurio registry URL property
        if (PROPERTY_APICURIO_REGISTRY_URL.equals(propertyName) ||
            PROPERTY_PRODUCER_REGISTRY_URL.equals(propertyName) ||
            PROPERTY_CONSUMER_REGISTRY_URL.equals(propertyName)) {
            return Optional.of(registryUrl);
        }

        // Set auto-register artifact to true
        if (PROPERTY_PRODUCER_AUTO_REGISTER_ARTIFACT.equals(propertyName)) {
            return Optional.of("true");
        }

        return Optional.empty(); // Property not handled by this provider
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Apicurio container is disabled, not answering property {}", propertyName);
            return false;
        }
        // Answer if the property is one we can resolve
        return propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
    }
}
