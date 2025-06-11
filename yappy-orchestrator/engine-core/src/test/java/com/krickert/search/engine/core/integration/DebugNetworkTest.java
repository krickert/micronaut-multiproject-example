package com.krickert.search.engine.core.integration;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import io.micronaut.testresources.testcontainers.TestContainers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug test to understand network connectivity issues.
 */
@MicronautTest
public class DebugNetworkTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(DebugNetworkTest.class);
    
    @Test
    void debugNetworkSetup() throws Exception {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        LOG.info("Consul host: {}, port: {}", consulHost.orElse("NOT RESOLVED"), consulPort.orElse("NOT RESOLVED"));
        
        // Get the shared network
        Network testNetwork = TestContainers.network("test-network");
        LOG.info("Test network: {}", testNetwork.getId());
        
        // Let's list all networks managed by test resources
        Map<String, Network> networks = TestContainers.getNetworks();
        LOG.info("All test resource networks: {}", networks.keySet());
        
        // Create two test containers on the same network
        try (GenericContainer<?> server = new GenericContainer<>("alpine:3.18")
                .withNetwork(testNetwork)
                .withNetworkAliases("test-server")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withCommand("sh", "-c", 
                    "echo 'Server starting...' && " +
                    "while true; do echo 'Server is running'; sleep 5; done"
                )
                .waitingFor(Wait.forLogMessage(".*Server is running.*", 1))) {
            
            server.start();
            LOG.info("Server container started with ID: {}", server.getContainerId());
            
            // Now create a client that tries to ping the server
            try (GenericContainer<?> clientContainer = new GenericContainer<>("alpine:3.18")
                    .withNetwork(testNetwork)
                    .withNetworkAliases("test-client")
                    .withLogConsumer(new Slf4jLogConsumer(LOG))
                    .withCommand("sh", "-c", 
                        "echo 'Client starting...' && " +
                        "ping -c 3 test-server && " +
                        "echo 'SUCCESS: Can ping test-server!' || " +
                        "echo 'FAILED: Cannot ping test-server'"
                    )
                    .waitingFor(Wait.forLogMessage(".*SUCCESS.*|.*FAILED.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(30)))) {
                
                clientContainer.start();
                
                String logs = clientContainer.getLogs();
                LOG.info("Client logs:\n{}", logs);
                
                // This should pass if containers can communicate
                assertThat(logs).contains("SUCCESS: Can ping test-server!");
            }
        }
    }
    
    @Test
    void inspectConsulContainer() throws Exception {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        assertThat(consulHost).isPresent();
        assertThat(consulPort).isPresent();
        
        LOG.info("Consul is accessible at {}:{}", consulHost.get(), consulPort.get());
        
        // Use docker inspect to see consul container's network settings
        try (GenericContainer<?> inspector = new GenericContainer<>("docker:24-cli")
                .withPrivilegedMode(true)
                .withFileSystemBind("/var/run/docker.sock", "/var/run/docker.sock")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withCommand("sh", "-c", 
                    "echo 'Listing all containers:' && " +
                    "docker ps -a | grep -E '(consul|test-network)' && " +
                    "echo '\\nFinding consul container:' && " +
                    "CONSUL_ID=$(docker ps -a | grep consul | grep -v grep | awk '{print $1}' | head -1) && " +
                    "echo \"Consul container ID: $CONSUL_ID\" && " +
                    "if [ -n \"$CONSUL_ID\" ]; then " +
                    "  echo '\\nConsul container networks:' && " +
                    "  docker inspect $CONSUL_ID | grep -A 20 '\"Networks\"' | head -30; " +
                    "else " +
                    "  echo 'No Consul container found'; " +
                    "fi"
                )
                .waitingFor(Wait.forLogMessage(".*Consul container.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))) {
            
            inspector.start();
            Thread.sleep(2000); // Give it time to complete
            
            String logs = inspector.getLogs();
            LOG.info("Docker inspect output:\n{}", logs);
        }
    }
}