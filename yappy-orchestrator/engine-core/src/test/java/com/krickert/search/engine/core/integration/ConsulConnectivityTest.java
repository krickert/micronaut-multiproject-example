package com.krickert.search.engine.core.integration;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify Consul connectivity from containers on the shared network.
 */
@MicronautTest
public class ConsulConnectivityTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulConnectivityTest.class);
    
    @Test
    void testConsulConnectivityFromSharedNetwork() throws Exception {
        // Get test resources client to ensure Consul is started
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        assertThat(consulHost).as("Consul host should be resolved").isPresent();
        assertThat(consulPort).as("Consul port should be resolved").isPresent();
        
        LOG.info("Consul is available at {}:{}", consulHost.get(), consulPort.get());
        
        // Create a simple test container that will try to connect to Consul
        // Note: We need to get the network details from the running Consul container
        try (GenericContainer<?> testContainer = new GenericContainer<>("alpine:3.18")
                .withNetworkAliases("consul-test-client")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withCommand("sh", "-c", 
                    "apk add --no-cache curl && " +
                    "echo 'Testing connectivity to consul on shared network...' && " +
                    "curl -f http://consul:8500/v1/status/leader && " +
                    "echo 'SUCCESS: Connected to Consul!' || " +
                    "echo 'FAILED: Could not connect to Consul'"
                )
                .waitingFor(Wait.forLogMessage(".*SUCCESS.*|.*FAILED.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))) {
            
            testContainer.start();
            
            // Check the logs
            String logs = testContainer.getLogs();
            LOG.info("Container logs:\n{}", logs);
            
            assertThat(logs).contains("SUCCESS: Connected to Consul!");
        }
        
        LOG.info("Consul connectivity test passed!");
    }
    
    @Test
    void testEngineContainerCanConnectToConsul() throws Exception {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        assertThat(consulHost).isPresent();
        
        // Create a minimal engine container that just tests Consul connectivity
        try (GenericContainer<?> engineTest = new GenericContainer<>("yappy-orchestrator:latest")
                .withExposedPorts(8080, 50000)
                .withNetworkAliases("engine-consul-test")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withEnv("MICRONAUT_ENVIRONMENTS", "docker,test")
                .withEnv("CONSUL_CLIENT_HOST", "consul")
                .withEnv("CONSUL_CLIENT_PORT", "8500")
                .withEnv("CONSUL_CLIENT_ENABLED", "true")
                .waitingFor(Wait.forLogMessage(".*Consul.*|.*consul.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            
            engineTest.start();
            
            // Give it a moment to connect
            Thread.sleep(5000);
            
            // Check if the container is still running
            assertThat(engineTest.isRunning()).as("Engine container should still be running").isTrue();
            
            // Check logs for Consul connection
            String logs = engineTest.getLogs();
            LOG.info("Engine container logs (first 2000 chars):\n{}", 
                    logs.length() > 2000 ? logs.substring(0, 2000) + "..." : logs);
            
            // The engine should have connected to Consul without errors
            assertThat(logs.toLowerCase()).doesNotContain("connection refused");
            assertThat(logs.toLowerCase()).doesNotContain("consul: connection error");
        }
        
        LOG.info("Engine container Consul connectivity test passed!");
    }
}