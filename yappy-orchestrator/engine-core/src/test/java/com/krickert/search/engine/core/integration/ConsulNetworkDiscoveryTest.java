package com.krickert.search.engine.core.integration;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import io.micronaut.testresources.testcontainers.TestContainers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to discover the actual network that Consul is using and connect to it.
 */
@MicronautTest
public class ConsulNetworkDiscoveryTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulNetworkDiscoveryTest.class);
    
    @Test
    void testConnectToConsulActualNetwork() throws Exception {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        assertThat(consulHost).isPresent();
        assertThat(consulPort).isPresent();
        
        LOG.info("Consul is available at {}:{}", consulHost.get(), consulPort.get());
        
        // Get Docker client to find Consul container
        DockerClient dockerClient = DockerClientFactory.instance().client();
        
        // Find Consul container
        List<Container> containers = dockerClient.listContainersCmd()
            .withShowAll(false) // Only running containers
            .exec();
        
        Container consulContainer = null;
        for (Container container : containers) {
            LOG.info("Found container: {} with image: {}", container.getNames()[0], container.getImage());
            if (container.getImage().contains("consul")) {
                consulContainer = container;
                break;
            }
        }
        
        assertThat(consulContainer).as("Consul container should be found").isNotNull();
        LOG.info("Found Consul container: {}", consulContainer.getId());
        
        // Get detailed container info
        InspectContainerResponse containerInfo = dockerClient.inspectContainerCmd(consulContainer.getId()).exec();
        
        // Get the network ID
        String networkId = null;
        String foundNetworkName = null;
        Map<String, com.github.dockerjava.api.model.ContainerNetwork> networks = 
            containerInfo.getNetworkSettings().getNetworks();
        
        for (Map.Entry<String, com.github.dockerjava.api.model.ContainerNetwork> entry : networks.entrySet()) {
            // Skip bridge and host networks
            if (!"bridge".equals(entry.getKey()) && !"host".equals(entry.getKey())) {
                foundNetworkName = entry.getKey();
                networkId = entry.getValue().getNetworkID();
                LOG.info("Consul is on network '{}' with ID: {}", foundNetworkName, networkId);
                break;
            }
        }
        
        final String networkName = foundNetworkName;
        
        assertThat(networkId).as("Network ID should be found").isNotNull();
        
        // The key insight: we need to get the network that test resources is managing
        // Since it's using a UUID-based network name, we need to connect to it directly
        
        // Test connectivity by running a container with docker directly
        try (GenericContainer<?> testContainer = new GenericContainer<>("alpine:3.18")
                .withCreateContainerCmdModifier(cmd -> {
                    // Connect to the same network as Consul
                    cmd.getHostConfig().withNetworkMode(networkName);
                })
                .withNetworkAliases("consul-connectivity-test")
                .withLogConsumer(new Slf4jLogConsumer(LOG))
                .withCommand("sh", "-c", 
                    "apk add --no-cache curl && " +
                    "echo 'Testing connectivity to consul...' && " +
                    "curl -f http://consul:8500/v1/status/leader && " +
                    "echo 'SUCCESS: Connected to Consul via network alias!' || " +
                    "echo 'FAILED: Could not connect to Consul'"
                )
                .waitingFor(Wait.forLogMessage(".*SUCCESS.*|.*FAILED.*", 1)
                    .withStartupTimeout(Duration.ofSeconds(30)))) {
            
            testContainer.start();
            
            String logs = testContainer.getLogs();
            LOG.info("Test container logs:\n{}", logs);
            
            assertThat(logs).contains("SUCCESS: Connected to Consul via network alias!");
        }
        
        LOG.info("✅ Successfully connected to Consul on its actual network!");
    }
}