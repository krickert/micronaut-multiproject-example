// <llm-snippet-file>pipeline-service-test-utils/pipeline-test-platform/src/main/java/com/krickert/search/test/platform/consul/ConsulContainer.java</llm-snippet-file>
package com.krickert.search.test.platform.consul;

import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;

/**
 * Manages a Consul container instance for testing by extending GenericContainer.
 * Designed to be used by TestContainerManager.
 */
// Extend GenericContainer, using self-type for fluent methods
public class ConsulContainer extends GenericContainer<ConsulContainer> {
    private static final Logger log = LoggerFactory.getLogger(ConsulContainer.class);

    private static final int CONSUL_PORT = 8500;
    public static final String DEFAULT_CONSUL_CONFIG_PATH_PREFIX = "config/pipeline/"; // Keep if useful
    public static final String NETWORK_ALIAS = "consul"; // Network alias

    // Cached endpoint after start
    private String endpoint = null;

    /**
     * Creates the Consul container definition.
     * The container is configured but NOT started here. Starting is handled by TestContainerManager.
     * @param network The shared Testcontainers network.
     */
    public ConsulContainer(@NonNull Network network) {
        // 1. Call super constructor with the image name
        super(DockerImageName.parse("hashicorp/consul:latest"));

        log.info("Defining Consul Testcontainer (extending GenericContainer)...");

        // 2. Configure the container using inherited fluent methods
        this.withExposedPorts(CONSUL_PORT)
                .withNetwork(network)
                .withNetworkAliases(NETWORK_ALIAS)
                .withCommand("agent", "-dev", "-client", "0.0.0.0", "-log-level", "info")
                .waitingFor(Wait.forHttp("/v1/status/leader") // Wait for leadership election
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(90))) // Apply timeout to wait strategy
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CONSUL"))
                .withStartupTimeout(Duration.ofSeconds(120)); // Overall container startup timeout
    }

    /**
     * Overrides the start method to calculate and cache the endpoint after starting.
     * Delegates to the superclass start method.
     */
    @Override
    public void start() {
        log.info("Attempting to start Consul container (ConsulContainer.start())...");
        super.start(); // Call the actual start logic from GenericContainer
        // Calculate and cache endpoint *after* successful start
        this.endpoint = "http://" + this.getHost() + ":" + this.getMappedPort(CONSUL_PORT);
        log.info("Consul container started successfully. Endpoint: {}", this.endpoint);
    }
    
    /**
     * Deletes a key-value pair from Consul.
     *
     * @param key the key to delete
     * @return true if deletion was successful, false otherwise
     */
    public boolean deleteKv(String key) {
        if (!isRunning()) {
            return false;
        }
        
        try {
            // Execute the Consul CLI command to delete the key
            ExecResult execResult = this.execInContainer(
                "consul", "kv", "delete", key
            );
            
            // Check if the command succeeded (exit code 0)
            return execResult.getExitCode() == 0;
        } catch (Exception e) {
            log.error("Error deleting KV pair '{}' from Consul", key, e);
            return false;
        }
    }

    /**
     * Gets the HTTP endpoint URL (e.g., "http://localhost:RANDOM_PORT").
     * Throws IllegalStateException if the container is not running or endpoint wasn't set.
     */
    @NonNull
    public String getEndpoint() {
        if (!isRunning()) {
            throw new IllegalStateException("Consul container not running, cannot get endpoint.");
        }
        if (this.endpoint == null) {
            // This might happen if start() was somehow bypassed or failed before setting endpoint
            throw new IllegalStateException("Consul container is running but endpoint is null. Was start() called correctly?");
        }
        return this.endpoint;
    }

    /**
     * Gets the host and mapped port (e.g., "localhost:RANDOM_PORT").
     * Throws IllegalStateException if the container is not running.
     * Note: Relies on inherited methods getHost() and getMappedPort().
     */
    @NonNull
    public String getHostAndPort() {
        if (!isRunning()) {
            // GenericContainer throws exception if not running when calling getMappedPort
            // but adding this check for clarity before combining them.
            throw new IllegalStateException("Consul container not running, cannot get host and port.");
        }
        // Directly use inherited methods
        return getHost() + ":" + getMappedPort(CONSUL_PORT);
    }

    // --- Methods for interacting with Consul KV store ---
    // Now use 'this.execInContainer' as the method is inherited

    public boolean putKv(String keyPath, String value) {
        if (!isRunning()) {
            log.error("Cannot put KV pair, Consul container is not running.");
            return false;
        }
        try {
            String escapedValue = value.replace("'", "'\\''");
            log.debug("Executing consul kv put '{}'='<value>' in container", keyPath);

            // Use inherited execInContainer
            ExecResult result = this.execInContainer(
                    "consul", "kv", "put", keyPath, escapedValue
            );

            if (result.getExitCode() == 0) {
                log.trace("Successfully put KV: {} = <value>", keyPath);
                return true;
            } else {
                log.error("Failed to put KV pair '{}'. Exit code: {}. Stdout: [{}]. Stderr: [{}]",
                        keyPath, result.getExitCode(), result.getStdout().trim(), result.getStderr().trim());
                return false;
            }
        } catch (IOException | InterruptedException | UnsupportedOperationException e) {
            log.error("Error executing 'consul kv put' in container for key '{}'", keyPath, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public boolean deleteKvRecursive(String keyPath) {
        if (!isRunning()) {
            log.error("Cannot delete KV path, Consul container is not running.");
            return false;
        }
        try {
            log.debug("Executing consul kv delete -recurse '{}' in container", keyPath);

            // Use inherited execInContainer
            ExecResult result = this.execInContainer(
                    "consul", "kv", "delete", "-recurse", keyPath
            );

            if (result.getExitCode() == 0) {
                log.trace("Successfully deleted KV path recursively: {}", keyPath);
                return true;
            } else {
                String stderr = result.getStderr();
                if (stderr != null && stderr.contains("No key found")) {
                    log.warn("Attempted to delete non-existent KV path '{}'. Considering successful for cleanup.", keyPath);
                    return true;
                }
                log.error("Failed to delete KV path '{}'. Exit code: {}. Stdout: [{}]. Stderr: [{}]",
                        keyPath, result.getExitCode(), result.getStdout().trim(), stderr.trim());
                return false;
            }
        } catch (IOException | InterruptedException | UnsupportedOperationException e) {
            log.error("Error executing 'consul kv delete -recurse' in container for path '{}'", keyPath, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}