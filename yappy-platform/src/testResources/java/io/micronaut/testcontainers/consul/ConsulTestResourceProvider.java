package io.micronaut.testcontainers.consul;
import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

/**
 * A test resource provider which will spawn a Consul test container.
 * It provides properties for both the base Consul client and the discovery client.
 */
public class ConsulTestResourceProvider extends AbstractTestContainersProvider<ConsulContainer> {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResourceProvider.class);

    // TestContainers Properties
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_CONSUL_ENABLED = TESTCONTAINERS_PREFIX + ".consul";

    // Base Client Properties
    public static final String CLIENT_PREFIX = "consul.client";
    public static final String PROPERTY_CONSUL_CLIENT_HOST = CLIENT_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_CLIENT_PORT = CLIENT_PREFIX + ".port";
    public static final String PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE = CLIENT_PREFIX + ".default-zone"; // Kept for completeness

    // Discovery Client Properties
    public static final String DISCOVERY_PREFIX = "consul.client.discovery";
    public static final String PROPERTY_CONSUL_DISCOVERY_HOST = DISCOVERY_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_DISCOVERY_PORT = DISCOVERY_PREFIX + ".port";
    // Add registration properties if needed:
    public static final String REGISTRATION_PREFIX = "consul.client.registration";
    public static final String PROPERTY_CONSUL_REGISTRATION_HOST = REGISTRATION_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_REGISTRATION_PORT = REGISTRATION_PREFIX + ".port";


    // Combined list of properties this provider can resolve
    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_CONSUL_CLIENT_HOST,
            PROPERTY_CONSUL_CLIENT_PORT,
            PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE,
            PROPERTY_CONSUL_DISCOVERY_HOST,
            PROPERTY_CONSUL_DISCOVERY_PORT,
            // Add registration properties here if explicitly resolving them
            PROPERTY_CONSUL_REGISTRATION_HOST,
            PROPERTY_CONSUL_REGISTRATION_PORT
    ));

    public static final String HASHICORP_CONSUL_KV_PROPERTIES_KEY = "containers.hashicorp-consul.kv-properties";
    public static final String DEFAULT_IMAGE = "hashicorp/consul";
    public static final int CONSUL_HTTP_PORT = 8500;
    public static final String SIMPLE_NAME = "hashicorp-consul";
    public static final String DISPLAY_NAME = "Consul";

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
        Object consulEnabled = testResourcesConfig.get(PROPERTY_TESTCONTAINERS_CONSUL_ENABLED);
        if (consulEnabled != null) {
            if (consulEnabled instanceof Boolean) {
                return (Boolean) consulEnabled;
            } else if (consulEnabled instanceof String) {
                return Boolean.parseBoolean((String) consulEnabled);
            } else if (consulEnabled instanceof Map) {
                // Check if there's an 'enabled' property in the map
                @SuppressWarnings("unchecked")
                Map<String, Object> enabledMap = (Map<String, Object>) consulEnabled;
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
            LOG.debug("Consul container is disabled, returning empty list of resolvable properties");
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
    protected ConsulContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Consul container is disabled, not creating container");
            return null;
        }

        ConsulContainer consulContainer = new ConsulContainer(imageName);

        // Set startup properties
        if (testResourcesConfig.containsKey(HASHICORP_CONSUL_KV_PROPERTIES_KEY)) {
            @SuppressWarnings("unchecked")
            List<String> properties = (List<String>) testResourcesConfig.get(HASHICORP_CONSUL_KV_PROPERTIES_KEY);
            if(null != properties && !properties.isEmpty()) {
                properties.forEach((property) -> consulContainer.withConsulCommand("kv put " + property.replace("=", " ")));
            }
        }
        return consulContainer;
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, ConsulContainer container) {
        // Resolve base client properties
        if (PROPERTY_CONSUL_CLIENT_HOST.equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if (PROPERTY_CONSUL_CLIENT_PORT.equals(propertyName)) {
            return Optional.of(container.getMappedPort(CONSUL_HTTP_PORT).toString());
        }
        if (PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE.equals(propertyName)) {
            return Optional.of(container.getHost() + ":" + container.getMappedPort(CONSUL_HTTP_PORT));
        }

        // Resolve discovery client properties explicitly to the same container
        if (PROPERTY_CONSUL_DISCOVERY_HOST.equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if (PROPERTY_CONSUL_DISCOVERY_PORT.equals(propertyName)) {
            return Optional.of(container.getMappedPort(CONSUL_HTTP_PORT).toString());
        }

        // Add registration properties if needed
        // if (PROPERTY_CONSUL_REGISTRATION_HOST.equals(propertyName)) {
        //     return Optional.of(container.getHost());
        // }
        // if (PROPERTY_CONSUL_REGISTRATION_PORT.equals(propertyName)) {
        //     return Optional.of(container.getMappedPort(CONSUL_HTTP_PORT).toString());
        // }

        return Optional.empty(); // Property not handled by this provider
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Check if this container is enabled
        if (!isContainerEnabled(testResourcesConfig)) {
            LOG.debug("Consul container is disabled, not answering property {}", propertyName);
            return false;
        }
        // Answer if the property is one we can resolve
        return propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
    }
}
