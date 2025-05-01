package com.krickert.search.config.consul.container;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton class to manage a shared ConsulContainer for tests.
 * This centralizes container management to avoid starting multiple containers
 * across different test classes.
 */
public class ConsulTestContainer {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestContainer.class);
    private static final String CONSUL_IMAGE = "hashicorp/consul:latest";
    private static final int CONSUL_PORT = 8500;

    // Static singleton instance
    private static final ConsulTestContainer INSTANCE = new ConsulTestContainer();

    // The actual container
    private final ConsulContainer consulContainer;

    // Container properties cache
    private final Map<String, String> properties;

    // Private constructor to enforce singleton pattern
    private ConsulTestContainer() {
        LOG.info("Initializing ConsulTestContainer singleton");
        consulContainer = new ConsulContainer(DockerImageName.parse(CONSUL_IMAGE))
                .withExposedPorts(CONSUL_PORT);

        // Start the container immediately
        if (!consulContainer.isRunning()) {
            LOG.info("Starting Consul container");
            consulContainer.start();
        }

        // Initialize properties
        Map<String, String> props = new HashMap<>();
        props.put("consul.host", consulContainer.getHost());
        props.put("consul.port", String.valueOf(consulContainer.getMappedPort(CONSUL_PORT)));
        props.put("consul.client.host", consulContainer.getHost());
        props.put("consul.client.port", String.valueOf(consulContainer.getMappedPort(CONSUL_PORT)));
        props.put("consul.client.config.enabled", "true");
        props.put("consul.client.config.format", "yaml");
        props.put("consul.client.config.path", "config/pipeline");
        props.put("micronaut.config-client.enabled", "false");

        // Make properties unmodifiable
        properties = Collections.unmodifiableMap(props);

        LOG.info("ConsulTestContainer initialized with properties: {}", properties);
    }

    /**
     * Get the singleton instance.
     *
     * @return the singleton instance
     */
    public static ConsulTestContainer getInstance() {
        return INSTANCE;
    }

    /**
     * Get the ConsulContainer.
     *
     * @return the ConsulContainer
     */
    public ConsulContainer getContainer() {
        // Ensure the container is running
        if (!consulContainer.isRunning()) {
            LOG.info("Container not running, starting it");
            consulContainer.start();
        }
        return consulContainer;
    }

    /**
     * Get the container properties as an unmodifiable map.
     * These properties can be used in test classes' getProperties() methods.
     *
     * @return unmodifiable map of container properties
     */
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Get a property value by key.
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getProperty(String key) {
        return properties.get(key);
    }

    /**
     * Get the container URL.
     *
     * @return the container URL
     */
    public String getContainerUrl() {
        return "http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(CONSUL_PORT);
    }

    /**
     * Add additional properties to the base properties.
     * This returns a new map with the additional properties added.
     *
     * @param additionalProperties additional properties to add
     * @return a new map with all properties
     */
    public Map<String, String> getPropertiesWith(Map<String, String> additionalProperties) {
        Map<String, String> combinedProperties = new HashMap<>(properties);
        combinedProperties.putAll(additionalProperties);
        return combinedProperties;
    }

    /**
     * Get properties with test config path.
     * This sets consul.client.config.path to "config/test".
     *
     * @return a new map with test config path
     */
    public Map<String, String> getPropertiesWithTestConfigPath() {
        Map<String, String> props = new HashMap<>();
        props.put("consul.client.config.path", "config/test");
        return getPropertiesWith(props);
    }

    /**
     * Get properties with data seeding enabled.
     * This enables data seeding with default settings.
     *
     * @return a new map with data seeding enabled
     */
    public Map<String, String> getPropertiesWithDataSeeding() {
        Map<String, String> props = new HashMap<>();
        props.put("consul.data.seeding.enabled", "true");
        props.put("consul.data.seeding.file", "seed-data.yaml");
        props.put("consul.data.seeding.skip-if-exists", "false");
        return getPropertiesWith(props);
    }

    /**
     * Get properties with data seeding enabled and custom settings.
     *
     * @param seedFile the seed file to use
     * @param skipIfExists whether to skip if exists
     * @return a new map with data seeding enabled and custom settings
     */
    public Map<String, String> getPropertiesWithCustomDataSeeding(String seedFile, boolean skipIfExists) {
        Map<String, String> props = new HashMap<>();
        props.put("consul.data.seeding.enabled", "true");
        props.put("consul.data.seeding.file", seedFile);
        props.put("consul.data.seeding.skip-if-exists", String.valueOf(skipIfExists));
        return getPropertiesWith(props);
    }

    /**
     * Get properties with data seeding disabled.
     *
     * @return a new map with data seeding disabled
     */
    public Map<String, String> getPropertiesWithoutDataSeeding() {
        Map<String, String> props = new HashMap<>();
        props.put("consul.data.seeding.enabled", "false");
        return getPropertiesWith(props);
    }

    /**
     * Get properties with test config path and data seeding enabled.
     * This combines the test config path and data seeding properties.
     *
     * @return a new map with test config path and data seeding enabled
     */
    public Map<String, String> getPropertiesWithTestConfigPathAndDataSeeding() {
        Map<String, String> props = new HashMap<>(getPropertiesWithTestConfigPath());
        props.putAll(getPropertiesWithDataSeeding());
        return props;
    }

    /**
     * Get properties with test config path and data seeding disabled.
     * This combines the test config path and disables data seeding.
     *
     * @return a new map with test config path and data seeding disabled
     */
    public Map<String, String> getPropertiesWithTestConfigPathWithoutDataSeeding() {
        Map<String, String> props = new HashMap<>(getPropertiesWithTestConfigPath());
        props.putAll(getPropertiesWithoutDataSeeding());
        return props;
    }
}
