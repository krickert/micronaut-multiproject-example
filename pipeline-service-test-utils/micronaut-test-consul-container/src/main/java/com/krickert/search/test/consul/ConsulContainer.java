package com.krickert.search.test.consul;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.discovery.consul.client.v1.ConsulClient;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of a Consul container for testing.
 * This class manages a Consul container and provides configuration for tests.
 */
@Requires(env = "test")
@Singleton
public class ConsulContainer implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(ConsulContainer.class);
    private static final GenericContainer<?> consulContainer;
    private static final String endpoint;
    private static boolean initialized = false;
    private static final int CONSUL_PORT = 8500;

    static {
        try {
            // Initialize the Consul container
            consulContainer = new GenericContainer<>(DockerImageName.parse("hashicorp/consul:latest"))
                    .withExposedPorts(CONSUL_PORT)
                    .withAccessToHost(true)
                    .withEnv(Map.of(
                            "CONSUL_BIND_INTERFACE", "eth0",
                            "CONSUL_CLIENT_INTERFACE", "eth0"
                    ))
                    .withStartupTimeout(Duration.ofSeconds(60))
                    .withReuse(false);

            // Start the container
            consulContainer.start();

            // Set the endpoint
            endpoint = "http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(CONSUL_PORT);
            log.info("Consul endpoint: {}", endpoint);
        } catch (Exception e) {
            log.error("Failed to start Consul container", e);
            throw new RuntimeException("Failed to start Consul container", e);
        }
    }

    /**
     * Constructor that initializes the Consul container.
     */
    public ConsulContainer() {
        start();
        log.info("ConsulContainer initialized with endpoint: {}", endpoint);
        log.info("ConsulContainer host and port: {}", getHostAndPort());
        log.info("Service registration is disabled to prevent connection attempts to default port");
    }

    /**
     * Get the endpoint URL for the Consul server.
     * 
     * @return the endpoint URL as a string
     */
    @NonNull
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Start the Consul container if it's not already running.
     * This method is idempotent.
     */
    public void start() {
        if (!initialized) {
            // No additional initialization needed for Consul
            initialized = true;
        }
    }

    /**
     * Check if the Consul container is running.
     * 
     * @return true if the container is running, false otherwise
     */
    public boolean isRunning() {
        return consulContainer.isRunning();
    }

    /**
     * Get the host and port of the Consul server in the format "host:port".
     * 
     * @return the host and port as a string
     */
    @NonNull
    public String getHostAndPort() {
        return consulContainer.getHost() + ":" + consulContainer.getMappedPort(CONSUL_PORT);
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        Map<String, String> props = new HashMap<>();

        // Set the application name
        props.put("micronaut.application.name", "my-app");

        // Enable Consul client
        props.put("consul.client.enabled", "true");

        // Disable service registration to prevent connection attempts to default port
        props.put("consul.client.registration.enabled", "false");

        // Enable config client
        props.put("micronaut.config-client.enabled", "true");

        // Set Consul client properties
        props.put("consul.client.defaultZone", getHostAndPort());

        // Set Consul config format and path
        props.put("consul.client.config.format", "YAML");
        props.put("consul.client.config.path", "/config");

        // Enable Consul watch
        props.put("consul.client.watch.enabled", "true");

        log.info("Setting Consul properties: consul.client.defaultZone={}, registration.enabled=false", getHostAndPort());

        return props;
    }
}
