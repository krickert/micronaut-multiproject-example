package com.krickert.testcontainers.consul; // Adjust package if needed

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

public class ConsulTestResourceProvider extends AbstractTestContainersProvider<ConsulContainer> {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResourceProvider.class);

    // ... (Keep constants like TESTCONTAINERS_PREFIX, CLIENT_PREFIX, etc.)
    public static final String TESTCONTAINERS_PREFIX = "testcontainers";
    public static final String PROPERTY_TESTCONTAINERS_ENABLED = TESTCONTAINERS_PREFIX + ".enabled";
    public static final String PROPERTY_TESTCONTAINERS_CONSUL_ENABLED = TESTCONTAINERS_PREFIX + ".consul";
    public static final String CLIENT_PREFIX = "consul.client";
    public static final String PROPERTY_CONSUL_CLIENT_HOST = CLIENT_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_CLIENT_PORT = CLIENT_PREFIX + ".port";
    public static final String PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE = CLIENT_PREFIX + ".default-zone";
    public static final String DISCOVERY_PREFIX = "consul.client.discovery";
    public static final String PROPERTY_CONSUL_DISCOVERY_HOST = DISCOVERY_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_DISCOVERY_PORT = DISCOVERY_PREFIX + ".port";
    public static final String REGISTRATION_PREFIX = "consul.client.registration";
    public static final String PROPERTY_CONSUL_REGISTRATION_HOST = REGISTRATION_PREFIX + ".host";
    public static final String PROPERTY_CONSUL_REGISTRATION_PORT = REGISTRATION_PREFIX + ".port";

    public static final List<String> RESOLVABLE_PROPERTIES_LIST = Collections.unmodifiableList(Arrays.asList(
            PROPERTY_CONSUL_CLIENT_HOST,
            PROPERTY_CONSUL_CLIENT_PORT,
            PROPERTY_CONSUL_CLIENT_DEFAULT_ZONE,
            PROPERTY_CONSUL_DISCOVERY_HOST,
            PROPERTY_CONSUL_DISCOVERY_PORT,
            PROPERTY_CONSUL_REGISTRATION_HOST,
            PROPERTY_CONSUL_REGISTRATION_PORT
    ));

    public static final String HASHICORP_CONSUL_KV_PROPERTIES_KEY = "containers.hashicorp-consul.kv-properties";
    public static final String DEFAULT_IMAGE = "hashicorp/consul"; // Consider specifying a version, e.g., "hashicorp/consul:1.18"
    public static final int CONSUL_HTTP_PORT = 8500;
    public static final String SIMPLE_NAME = "hashicorp-consul";
    public static final String DISPLAY_NAME = "Consul";


    // ... (Keep getResolvableProperties, getDisplayName, getSimpleName, getDefaultImageName methods as is) ...
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
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


    // --- UPDATED createContainer METHOD ---
    @Override
    protected ConsulContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {

        LOG.info("Creating Consul container with image: {}", imageName);
        // Create a new Consul container with the specified image
        ConsulContainer consulContainer = new ConsulContainer(imageName);
        // The ConsulContainer automatically exposes port 8500 and maps it to a random port
        // No need to explicitly call withExposedPorts

        // Set startup KV properties (keep existing logic)
        // This uses `withConsulCommand` which executes AFTER the container starts
        if (testResourcesConfig.containsKey(HASHICORP_CONSUL_KV_PROPERTIES_KEY)) {
            @SuppressWarnings("unchecked")
            List<String> properties = (List<String>) testResourcesConfig.get(HASHICORP_CONSUL_KV_PROPERTIES_KEY);
            if (properties != null && !properties.isEmpty()) {
                LOG.info("Applying KV properties from configuration: {}", properties);
                properties.forEach((property) -> {
                    // Format for 'consul kv put key value' command line
                    String[] parts = property.split("=", 2);
                    if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                        // Note: withConsulCommand runs AFTER the container starts.
                        // It executes 'consul kv put ...' using the consul CLI inside the running container.
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        LOG.debug("Adding Consul KV: {} = {}", key, value);
                        consulContainer.withConsulCommand("kv put " + key + " " + value);
                    } else {
                        LOG.warn("Skipping invalid KV property format (expected 'key=value'): {}", property);
                    }
                });
            }
        } else {
            LOG.debug("No KV properties found in configuration key '{}'", HASHICORP_CONSUL_KV_PROPERTIES_KEY);
        }

        LOG.info("Consul container configured.");
        return consulContainer;
    }
    // --- END OF UPDATED createContainer METHOD ---


    // ... (Keep resolveProperty and shouldAnswer methods as is) ...
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
            // Default zone format might depend on specific client library needs,
            // adjust if necessary. This format is common.
            return Optional.of(container.getHost() + ":" + container.getMappedPort(CONSUL_HTTP_PORT));
        }

        // Resolve discovery client properties explicitly to the same container
        if (PROPERTY_CONSUL_DISCOVERY_HOST.equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if (PROPERTY_CONSUL_DISCOVERY_PORT.equals(propertyName)) {
            return Optional.of(container.getMappedPort(CONSUL_HTTP_PORT).toString());
        }

        // Resolve registration properties if they explicitly point here
        if (PROPERTY_CONSUL_REGISTRATION_HOST.equals(propertyName)) {
            return Optional.of(container.getHost());
        }
        if (PROPERTY_CONSUL_REGISTRATION_PORT.equals(propertyName)) {
            return Optional.of(container.getMappedPort(CONSUL_HTTP_PORT).toString());
        }

        return Optional.empty(); // Property not handled by this provider
    }

    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Answer if the property is one we can resolve
        return propertyName != null && RESOLVABLE_PROPERTIES_LIST.contains(propertyName);
    }
}
