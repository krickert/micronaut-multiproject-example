package com.krickert.yappy.integration.container;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simple test to verify that our Docker images can start.
 */
@Testcontainers
public class SimpleContainerStartupTest {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleContainerStartupTest.class);
    
    @Test
    void testTikaParserContainerStarts() {
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("yappy-tika-parser:1.0.0-SNAPSHOT"))
                .withExposedPorts(50053)
                .withEnv("GRPC_PORT", "50053")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("TIKA"))
                .withStartupTimeout(Duration.ofMinutes(2))) {
            
            container.start();
            assertTrue(container.isRunning(), "Tika parser container should be running");
            
            // Check logs for successful startup
            String logs = container.getLogs();
            log.info("Tika parser container logs: {}", logs);
        }
    }
    
    @Test
    void testChunkerContainerStarts() {
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("yappy-chunker:1.0.0-SNAPSHOT"))
                .withExposedPorts(50052)
                .withEnv("GRPC_PORT", "50052")
                .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CHUNKER"))
                .withStartupTimeout(Duration.ofMinutes(2))) {
            
            container.start();
            assertTrue(container.isRunning(), "Chunker container should be running");
            
            // Check logs for successful startup
            String logs = container.getLogs();
            log.info("Chunker container logs: {}", logs);
        }
    }
    
    @Test
    void testEngineContainerStarts() {
        // Start Consul first since engine needs it
        try (GenericContainer<?> consul = new GenericContainer<>(DockerImageName.parse("hashicorp/consul:latest"))
                .withExposedPorts(8500)
                .withCommand("agent", "-dev", "-client=0.0.0.0")
                .waitingFor(Wait.forHttp("/v1/status/leader").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(1))) {
            
            consul.start();
            
            try (GenericContainer<?> engine = new GenericContainer<>(DockerImageName.parse("yappy-engine:1.0.0-SNAPSHOT"))
                    .withExposedPorts(8080, 50050)
                    .withEnv("CONSUL_HOST", consul.getHost())
                    .withEnv("CONSUL_PORT", String.valueOf(consul.getMappedPort(8500)))
                    .withEnv("CONSUL_ENABLED", "true")
                    .withEnv("KAFKA_ENABLED", "false") // Disable Kafka for simple test
                    .withEnv("YAPPY_ENGINE_NAME", "test-engine")
                    .withEnv("YAPPY_CLUSTER_NAME", "test-cluster")
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("ENGINE"))
                    .withStartupTimeout(Duration.ofMinutes(2))) {
                
                engine.start();
                assertTrue(engine.isRunning(), "Engine container should be running");
                
                // Check logs for successful startup
                String logs = engine.getLogs();
                log.info("Engine container logs: {}", logs);
            }
        }
    }
}