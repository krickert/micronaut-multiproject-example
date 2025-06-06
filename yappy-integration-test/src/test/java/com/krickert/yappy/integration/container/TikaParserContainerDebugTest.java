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
 * Debug test to extract detailed logs from the container.
 */
public class TikaParserContainerDebugTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserContainerDebugTest.class);

    @Test
    @DisplayName("Debug test to extract detailed logs from the container")
    void testContainerLogs() {
        LOG.info("Starting container debug test");

        // Use the Docker image that was built for the engine-tika-parser module
        String dockerImageName = System.getProperty("docker.image.name", "engine-tika-parser:latest");
        LOG.info("Using Docker image: {}", dockerImageName);

        // Create the container with necessary environment variables
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(dockerImageName))
                .withExposedPorts(8080, 50051, 50053) // HTTP, Engine gRPC, and Tika Parser gRPC ports
                .withEnv("CONSUL_HOST", "host.docker.internal") // Use host.docker.internal to access host machine
                .withEnv("CONSUL_PORT", "8500")
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
                .withEnv("APICURIO_REGISTRY_URL", "http://localhost:8080")
                .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
                .withEnv("YAPPY_ENGINE_NAME", "yappy-engine-tika-parser")
                .withEnv("CONSUL_CLIENT_DEFAULT_ZONE", "dc1")
                .withEnv("CONSUL_ENABLED", "true") // Enable Consul to test connection
                .withEnv("KAFKA_ENABLED", "true")
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
            Thread.sleep(10000); // Wait 10 seconds for both JVMs to start

            // Extract Java application logs using container exec
            try {
                // Read tika-parser logs (stdout with stderr redirected)
                org.testcontainers.containers.Container.ExecResult tikaLogsResult = 
                        container.execInContainer("cat", "/var/log/supervisor/tika-parser.log");
                if (tikaLogsResult.getExitCode() == 0 && !tikaLogsResult.getStdout().trim().isEmpty()) {
                    LOG.info("=== TIKA PARSER APPLICATION LOGS (STDOUT + STDERR) ===\n{}", tikaLogsResult.getStdout());
                } else {
                    LOG.warn("No tika-parser logs found or empty");
                }

                // Read engine logs (stdout with stderr redirected)
                org.testcontainers.containers.Container.ExecResult engineLogsResult = 
                        container.execInContainer("cat", "/var/log/supervisor/engine.log");
                if (engineLogsResult.getExitCode() == 0 && !engineLogsResult.getStdout().trim().isEmpty()) {
                    String engineLogs = engineLogsResult.getStdout();
                    LOG.info("=== ENGINE APPLICATION LOGS (STDOUT + STDERR) ===");

                    // Split logs by line and print each line with a debug prefix
                    String[] logLines = engineLogs.split("\\n");
                    for (String line : logLines) {
                        LOG.error("[DEBUG_LOG] ENGINE: {}", line);
                    }
                } else {
                    LOG.warn("No engine logs found or empty");
                }

                // Check if JAR files exist
                org.testcontainers.containers.Container.ExecResult lsResult = 
                        container.execInContainer("ls", "-la", "/app/engine/", "/app/modules/");
                LOG.info("=== CONTAINER FILE LISTING ===\n{}", lsResult.getStdout());

                // Check supervisord status
                org.testcontainers.containers.Container.ExecResult supervisorctlResult = 
                        container.execInContainer("supervisorctl", "status");
                LOG.info("=== SUPERVISORCTL STATUS ===\n{}", supervisorctlResult.getStdout());

            } catch (Exception e) {
                LOG.error("Failed to extract container logs: {}", e.getMessage(), e);
                fail("Failed to extract container logs: " + e.getMessage());
            }

        } catch (Exception e) {
            LOG.error("Failed to start container: {}", e.getMessage(), e);
            fail("Container failed to start: " + e.getMessage());
        }
    }
}
