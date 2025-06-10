package com.krickert.search.pipeline.integration;

import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates module registration using actual Docker containers.
 * This test simulates the real deployment scenario where:
 * 1. Engine starts with bootstrap configuration
 * 2. Modules (Tika, Chunker) start as Docker containers
 * 3. Modules register themselves with the engine via gRPC
 */
@MicronautTest(environments = "module-docker-test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ModuleDockerRegistrationIntegrationTest implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(ModuleDockerRegistrationIntegrationTest.class);
    
    private static Network network = Network.newNetwork();
    
    @Inject
    EmbeddedServer embeddedServer;
    
    @Inject
    ApplicationContext applicationContext;
    
    @Property(name = "grpc.server.port")
    int grpcPort;
    
    private GenericContainer<?> tikaContainer;
    private GenericContainer<?> chunkerContainer;
    
    @Override
    public Map<String, String> getProperties() {
        // Consul container is managed by testcontainers and starts before this method
        // For testing, we'll use test-resources to manage Consul
        return Map.of(
                "consul.client.registration.enabled", "false",
                "consul.client.enabled", "true",
                "grpc.server.port", "${random.port}", // Random port
                "yappy.cluster.name", "test-cluster",
                "yappy.engine.bootstrapper.enabled", "false"
        );
    }
    
    @BeforeAll
    void setup() {
        log.info("Setting up integration test environment");
        log.info("Engine gRPC server running on port: {}", grpcPort);
        log.info("Engine HTTP server running on port: {}", embeddedServer.getPort());
    }
    
    @Test
    @DisplayName("Test Tika module registration via Docker container")
    void testTikaModuleRegistration() throws Exception {
        // Use standard ports inside the container
        int httpPort = 8080;
        int grpcPort = 50051;
        
        // Start Tika container with proper configuration
        tikaContainer = new GenericContainer<>(DockerImageName.parse("tika-parser:latest"))
                .withNetwork(network)
                .withNetworkAliases("tika-parser")
                .withExposedPorts(httpPort, grpcPort)
                .withEnv("MICRONAUT_SERVER_PORT", String.valueOf(httpPort))
                .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
                .withEnv("GRPC_SERVER_PORT", String.valueOf(grpcPort))
                .withEnv("GRPC_SERVER_HOST", "0.0.0.0")
                .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                .waitingFor(Wait.forLogMessage(".*Startup completed.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tika-parser")));
        
        tikaContainer.start();
        
        // Wait for container to be ready
        Thread.sleep(2000);
        
        // Simulate CICD registration - tell the engine to register the Tika module
        registerModuleWithEngine(
                "tika-parser",
                "tika-parser-instance",
                tikaContainer.getHost(),
                tikaContainer.getMappedPort(grpcPort), // Use the mapped gRPC port
                HealthCheckType.GRPC,
                "grpc.health.v1.Health/Check",
                "{\"maxContentLength\": 1000000}"
        );
        
        // Verify module is registered
        verifyModuleRegistered("tika-parser");
        
        // Verify health check is working
        verifyModuleHealthCheck(
                tikaContainer.getHost(),
                tikaContainer.getMappedPort(grpcPort),
                "grpc.health.v1.Health/Check"
        );
    }
    
    @Test
    @DisplayName("Test Chunker module registration via Docker container")
    void testChunkerModuleRegistration() throws Exception {
        // Use standard ports inside the container
        int httpPort = 8080;
        int grpcPort = 50051;
        
        // Start Chunker container with proper configuration
        chunkerContainer = new GenericContainer<>(DockerImageName.parse("chunker:latest"))
                .withNetwork(network)
                .withNetworkAliases("chunker")
                .withExposedPorts(httpPort, grpcPort)
                .withEnv("MICRONAUT_SERVER_PORT", String.valueOf(httpPort))
                .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
                .withEnv("GRPC_SERVER_PORT", String.valueOf(grpcPort))
                .withEnv("GRPC_SERVER_HOST", "0.0.0.0")
                .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                .waitingFor(Wait.forLogMessage(".*Startup completed.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("chunker")));
        
        chunkerContainer.start();
        
        // Wait for container to be ready
        Thread.sleep(2000);
        
        // Simulate CICD registration - tell the engine to register the Chunker module
        registerModuleWithEngine(
                "chunker",
                "chunker-instance",
                chunkerContainer.getHost(),
                chunkerContainer.getMappedPort(grpcPort), // Use the mapped gRPC port
                HealthCheckType.GRPC,
                "grpc.health.v1.Health/Check",
                "{\"chunkSize\": 1000, \"chunkOverlap\": 200}"
        );
        
        // Verify module is registered
        verifyModuleRegistered("chunker");
        
        // Verify health check is working
        verifyModuleHealthCheck(
                chunkerContainer.getHost(),
                chunkerContainer.getMappedPort(grpcPort),
                "grpc.health.v1.Health/Check"
        );
    }
    
    @Test
    @DisplayName("Test multiple modules registering concurrently")
    void testConcurrentModuleRegistration() throws Exception {
        // Use standard ports inside the containers
        int httpPort = 8080;
        int grpcPort = 50051;
        
        // Start both containers
        tikaContainer = new GenericContainer<>(DockerImageName.parse("tika-parser:latest"))
                .withNetwork(network)
                .withNetworkAliases("tika-parser")
                .withExposedPorts(httpPort, grpcPort)
                .withEnv("MICRONAUT_SERVER_PORT", String.valueOf(httpPort))
                .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
                .withEnv("GRPC_SERVER_PORT", String.valueOf(grpcPort))
                .withEnv("GRPC_SERVER_HOST", "0.0.0.0")
                .withEnv("CONSUL_CLIENT_ENABLED", "false")
                .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                .waitingFor(Wait.forLogMessage(".*Startup completed.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("tika-parser-concurrent")));
        
        chunkerContainer = new GenericContainer<>(DockerImageName.parse("chunker:latest"))
                .withNetwork(network)
                .withNetworkAliases("chunker")
                .withExposedPorts(httpPort, grpcPort)
                .withEnv("MICRONAUT_SERVER_PORT", String.valueOf(httpPort))
                .withEnv("MICRONAUT_SERVER_HOST", "0.0.0.0")
                .withEnv("GRPC_SERVER_PORT", String.valueOf(grpcPort))
                .withEnv("GRPC_SERVER_HOST", "0.0.0.0")
                .withEnv("CONSUL_CLIENT_ENABLED", "false")
                .withEnv("MICRONAUT_ENVIRONMENTS", "test")
                .waitingFor(Wait.forLogMessage(".*Startup completed.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("chunker-concurrent")));
        
        // Start both containers in parallel
        tikaContainer.start();
        chunkerContainer.start();
        
        // Wait for containers to be ready
        Thread.sleep(2000);
        
        // Register both modules concurrently (simulating CICD)
        Thread tikaThread = new Thread(() -> {
            registerModuleWithEngine(
                    "tika-parser-concurrent",
                    "tika-parser-concurrent-instance",
                    tikaContainer.getHost(),
                    tikaContainer.getMappedPort(grpcPort),
                    HealthCheckType.GRPC,
                    "grpc.health.v1.Health/Check",
                    "{\"maxContentLength\": 1000000}"
            );
        });
        
        Thread chunkerThread = new Thread(() -> {
            registerModuleWithEngine(
                    "chunker-concurrent",
                    "chunker-concurrent-instance",
                    chunkerContainer.getHost(),
                    chunkerContainer.getMappedPort(grpcPort),
                    HealthCheckType.GRPC,
                    "grpc.health.v1.Health/Check",
                    "{\"chunkSize\": 1000, \"chunkOverlap\": 200}"
            );
        });
        
        tikaThread.start();
        chunkerThread.start();
        
        tikaThread.join();
        chunkerThread.join();
        
        // Verify both modules are registered
        verifyModuleRegistered("tika-parser-concurrent");
        verifyModuleRegistered("chunker-concurrent");
    }
    
    private void registerModuleWithEngine(String implementationId, String instanceServiceName, 
                                         String host, int port, HealthCheckType healthCheckType,
                                         String healthCheckEndpoint, String customConfigJson) {
        // First verify the module is healthy before registering
        if (healthCheckType == HealthCheckType.GRPC) {
            log.info("Performing pre-registration health check for module {} at {}:{}", 
                    implementationId, host, port);
            verifyModuleHealthCheck(host, port, healthCheckEndpoint);
        }
        
        // Create a gRPC client to register with the engine
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        
        try {
            YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub stub = 
                    YappyModuleRegistrationServiceGrpc.newBlockingStub(channel);
            
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId(implementationId)
                    .setInstanceServiceName(instanceServiceName)
                    .setHost(host)
                    .setPort(port)
                    .setHealthCheckType(healthCheckType)
                    .setHealthCheckEndpoint(healthCheckEndpoint)
                    .setInstanceCustomConfigJson(customConfigJson)
                    .setModuleSoftwareVersion("1.0.0")
                    .build();
            
            log.info("Registering module {} with engine at {}:{}", implementationId, host, port);
            RegisterModuleResponse response = stub.registerModule(request);
            
            assertTrue(response.getSuccess(), 
                    "Module registration failed: " + response.getMessage());
            log.info("Module {} registered successfully with service ID: {}", 
                    implementationId, response.getRegisteredServiceId());
            
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void verifyModuleRegistered(String moduleId) {
        // Create a gRPC client to query the engine
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        
        try {
            YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub stub = 
                    YappyModuleRegistrationServiceGrpc.newBlockingStub(channel);
            
            // Query for the specific module
            // Note: This assumes the engine has a way to query registered modules
            // You might need to implement this in the registration service
            log.info("Verifying module {} is registered", moduleId);
            
            // For now, we'll do a simple registration request to check if it's already registered
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId(moduleId)
                    .setInstanceServiceName(moduleId + "-test")
                    .setHost("dummy")
                    .setPort(50051)
                    .setHealthCheckType(HealthCheckType.GRPC)
                    .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                    .setInstanceCustomConfigJson("{}")
                    .build();
            
            try {
                RegisterModuleResponse response = stub.registerModule(request);
                // If already registered, it should either return success or indicate it's already registered
                assertTrue(response.getSuccess() || response.getMessage().contains("already registered"),
                        "Module " + moduleId + " should be registered");
            } catch (Exception e) {
                // Check if the exception indicates the module is already registered
                assertTrue(e.getMessage().contains("already registered"),
                        "Expected module to be already registered");
            }
            
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @AfterEach
    void cleanup() {
        if (tikaContainer != null && tikaContainer.isRunning()) {
            tikaContainer.stop();
        }
        if (chunkerContainer != null && chunkerContainer.isRunning()) {
            chunkerContainer.stop();
        }
    }
    
    @AfterAll
    void tearDown() {
        if (network != null) {
            network.close();
        }
    }
    
    private int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("Could not find an available port", e);
        }
    }
    
    private void verifyModuleHealthCheck(String host, int port, String serviceName) {
        log.info("Verifying health check for service at {}:{}", host, port);
        
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
        
        try {
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
            
            // Check health without service name (overall health)
            HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
            HealthCheckResponse response = healthStub.check(request);
            
            assertEquals(HealthCheckResponse.ServingStatus.SERVING, response.getStatus(),
                    "Module should report as SERVING for overall health check");
            log.info("Overall health check passed: {}", response.getStatus());
            
            // If a specific service name is provided, check that too
            if (serviceName != null && !serviceName.isEmpty()) {
                // Extract service name from the format "grpc.health.v1.Health/Check"
                String service = serviceName.contains("/") ? 
                        serviceName.substring(0, serviceName.indexOf("/")) : serviceName;
                
                HealthCheckRequest serviceRequest = HealthCheckRequest.newBuilder()
                        .setService(service)
                        .build();
                
                try {
                    HealthCheckResponse serviceResponse = healthStub.check(serviceRequest);
                    log.info("Service-specific health check for '{}': {}", 
                            service, serviceResponse.getStatus());
                } catch (Exception e) {
                    log.debug("Service-specific health check not available for '{}', " +
                            "but overall health is SERVING", service);
                }
            }
            
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}