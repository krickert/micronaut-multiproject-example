package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the port mapping concept that the user mentioned:
 * "we would intercept the mapped port for the services right? and the engine test setup sorta has to do the registration because the engine isn't in the same network"
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PortMappingDemoTest {

    private static final Logger logger = LoggerFactory.getLogger(PortMappingDemoTest.class);

    @Container
    static GenericContainer<?> consulContainer = new GenericContainer<>("hashicorp/consul:1.18")
        .withExposedPorts(8500)
        .withCommand("agent", "-dev", "-client=0.0.0.0")
        .waitingFor(Wait.forHttp("/v1/status/leader").forPort(8500));

    @Container
    static GenericContainer<?> nginxContainer = new GenericContainer<>("nginx:alpine")
        .withExposedPorts(80)
        .waitingFor(Wait.forHttp("/").forPort(80));

    private ConsulClient consulClient;

    @BeforeAll
    void setup() {
        // Create Consul client with mapped port
        String consulHost = consulContainer.getHost();
        Integer consulPort = consulContainer.getMappedPort(8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        logger.info("=== Port Mapping Demonstration ===");
        logger.info("Consul container internal port: 8500");
        logger.info("Consul container mapped port: {}", consulPort);
        logger.info("Consul accessible at: {}:{}", consulHost, consulPort);
        
        logger.info("Nginx container internal port: 80");
        logger.info("Nginx container mapped port: {}", nginxContainer.getMappedPort(80));
    }

    @Test
    void demonstratePortMappingConcept() {
        // This demonstrates the key insight the user had:
        // The containers expose services on their internal ports (e.g., 8500, 80)
        // But from the test environment, we must use the mapped ports
        
        // 1. Register the nginx service in Consul with its MAPPED port
        NewService nginxService = new NewService();
        nginxService.setId("nginx-demo");
        nginxService.setName("nginx");
        nginxService.setAddress(nginxContainer.getHost()); // Usually "localhost"
        nginxService.setPort(nginxContainer.getMappedPort(80)); // The MAPPED port, not 80!
        nginxService.setTags(List.of("demo", "http"));
        
        consulClient.agentServiceRegister(nginxService);
        
        // 2. Verify the service is registered with the correct mapped port
        var services = consulClient.getAgentServices();
        var registeredNginx = services.getValue().get("nginx-demo");
        
        assertThat(registeredNginx).isNotNull();
        assertThat(registeredNginx.getPort()).isEqualTo(nginxContainer.getMappedPort(80));
        assertThat(registeredNginx.getPort()).isNotEqualTo(80); // NOT the internal port!
        
        logger.info("✓ Service registered with mapped port: {}", registeredNginx.getPort());
        logger.info("✓ This is why the engine must do the registration - it knows the mapped ports!");
        
        // 3. The key insight: If a service inside a container tried to register itself,
        // it would register with port 80, which would be wrong from the test's perspective
        logger.info("\nKey Insight:");
        logger.info("- Services inside containers don't know their mapped ports");
        logger.info("- The test environment (engine) must register services with mapped ports");
        logger.info("- This allows proper communication across the network boundary");
    }

    @Test
    void demonstrateNetworkBoundary() {
        // This shows why containers and tests are on different networks
        
        logger.info("\n=== Network Boundary Demonstration ===");
        
        // From inside the container's perspective
        logger.info("Inside nginx container:");
        logger.info("  - Service listens on: 0.0.0.0:80");
        logger.info("  - Container hostname: {}", nginxContainer.getContainerInfo().getConfig().getHostName());
        
        // From the test's perspective
        logger.info("From test environment:");
        logger.info("  - Service accessible at: {}:{}", 
            nginxContainer.getHost(), 
            nginxContainer.getMappedPort(80));
        logger.info("  - Cannot access container's internal port 80 directly");
        
        // The bridge
        logger.info("\nDocker provides the bridge:");
        logger.info("  - Maps container port 80 → random host port {}", 
            nginxContainer.getMappedPort(80));
        logger.info("  - This is why we need mapped ports in service registration!");
    }

    @AfterAll
    void cleanup() {
        if (consulClient != null) {
            consulClient.agentServiceDeregister("nginx-demo");
        }
    }
}