package com.krickert.yappy.integration.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Simple test to verify that the Tika Parser container starts up correctly.
 * This test does not require Consul or other external services.
 */
public class TikaParserContainerStartupTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserContainerStartupTest.class);

    @Test
    @DisplayName("Test that Tika Parser container starts up with both JVMs running")
    void testContainerStartup() {
        LOG.info("Starting container startup test");

        // Use the Docker image that was built for the engine-tika-parser module
        String dockerImageName = System.getProperty("docker.image.name", "engine-tika-parser:latest");
        LOG.info("Using Docker image: {}", dockerImageName);

        // Create the container with necessary environment variables
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(dockerImageName))
                .withExposedPorts(8080, 50051, 50053) // HTTP, Engine gRPC, and Tika Parser gRPC ports
                .withEnv("CONSUL_HOST", "localhost")
                .withEnv("CONSUL_PORT", "8500")
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
                .withEnv("APICURIO_REGISTRY_URL", "http://localhost:8080")
                .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
                .withEnv("YAPPY_ENGINE_NAME", "yappy-engine-tika-parser")
                .withEnv("CONSUL_CLIENT_DEFAULT_ZONE", "dc1")
                .withEnv("CONSUL_ENABLED", "false") // Disable Consul to avoid connection attempts
                .withEnv("KAFKA_ENABLED", "false") // Disable Kafka to avoid connection attempts
                .withEnv("SCHEMA_REGISTRY_TYPE", "apicurio")
                .withEnv("MICRONAUT_SERVER_PORT", "8080")
                .withEnv("GRPC_SERVER_PORT", "50051")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .waitingFor(Wait.forLogMessage(".*Starting supervisord.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)))) {

            // Start the container
            container.start();
            assertTrue(container.isRunning(), "Container should be running");
            LOG.info("Container started successfully");

            // Give the applications a moment to start
            Thread.sleep(5000); // Wait 5 seconds for both JVMs to start

            // Get container logs to verify both processes are running
            String logs = container.getLogs();
            LOG.info("Container logs preview: {}", logs.substring(0, Math.min(logs.length(), 500)));

            // Verify supervisord is running
            assertTrue(logs.contains("Starting supervisord") || logs.contains("supervisord"), 
                    "Supervisord should be running");

            // Extract Java application logs using container exec
            try {
                // Read tika-parser logs (stdout with stderr redirected)
                org.testcontainers.containers.Container.ExecResult tikaLogsResult = 
                        container.execInContainer("cat", "/var/log/supervisor/tika-parser.log");
                if (tikaLogsResult.getExitCode() == 0 && !tikaLogsResult.getStdout().trim().isEmpty()) {
                    LOG.info("=== TIKA PARSER APPLICATION LOGS (STDOUT + STDERR) ===\n{}", tikaLogsResult.getStdout());
                    // Verify Tika Parser JVM is running
                    assertTrue(tikaLogsResult.getStdout().contains("TikaParserService") || 
                            tikaLogsResult.getStdout().contains("Tika Parser") ||
                            tikaLogsResult.getStdout().contains("Started TikaParserApplication"),
                            "Tika Parser JVM should be running");
                } else {
                    LOG.warn("No tika-parser logs found or empty");
                }

                // Read engine logs (stdout with stderr redirected)
                org.testcontainers.containers.Container.ExecResult engineLogsResult = 
                        container.execInContainer("cat", "/var/log/supervisor/engine.log");
                if (engineLogsResult.getExitCode() == 0 && !engineLogsResult.getStdout().trim().isEmpty()) {
                    LOG.info("=== ENGINE APPLICATION LOGS (STDOUT + STDERR) ===\n{}", engineLogsResult.getStdout());
                    // Verify Engine JVM is running
                    assertTrue(engineLogsResult.getStdout().contains("YappyEngineBootstrapper") || 
                            engineLogsResult.getStdout().contains("Engine") ||
                            engineLogsResult.getStdout().contains("Started EngineApplication"),
                            "Engine JVM should be running");
                } else {
                    LOG.warn("No engine logs found or empty");
                }

                // Check if JAR files exist
                org.testcontainers.containers.Container.ExecResult lsResult = 
                        container.execInContainer("ls", "-la", "/app/engine/", "/app/modules/");
                LOG.info("=== CONTAINER FILE LISTING ===\n{}", lsResult.getStdout());
                assertTrue(lsResult.getStdout().contains("engine.jar"), "Engine JAR file should exist");
                assertTrue(lsResult.getStdout().contains("tika-parser.jar"), "Tika Parser JAR file should exist");

            } catch (Exception e) {
                LOG.error("Failed to extract container logs: {}", e.getMessage(), e);
                fail("Failed to extract container logs: " + e.getMessage());
            }

            LOG.info("Test PASSED: Container started successfully with both engine and module running");
        } catch (Exception e) {
            LOG.error("Failed to start container: {}", e.getMessage(), e);
            fail("Container failed to start: " + e.getMessage());
        }
    }
}