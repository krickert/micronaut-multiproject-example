package com.krickert.search.engine.registration;

import com.krickert.yappy.registration.api.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for enhanced module registration with validation.
 */
@MicronautTest(environments = {"test", "test-registration"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EnhancedModuleRegistrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EnhancedModuleRegistrationTest.class);
    
    @Container
    static GenericContainer<?> tikaContainer = new GenericContainer<>("ghcr.io/krickert/tika-parser:latest")
            .withExposedPorts(8080, 50051)
            .withEnv("GRPC_SERVER_PORT", "50051")
            .withEnv("MICRONAUT_SERVER_PORT", "8080");
    
    private ManagedChannel channel;
    private YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub registrationStub;
    private static String registeredServiceId;
    
    @BeforeEach
    void setup(EmbeddedServer embeddedServer) {
        int grpcPort = embeddedServer.getApplicationContext()
                .getProperty("grpc.server.port", Integer.class)
                .orElse(50050);
        
        channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        
        registrationStub = YappyModuleRegistrationServiceGrpc.newBlockingStub(channel);
    }
    
    @AfterEach
    void cleanup() {
        if (channel != null) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Test
    @Order(1)
    void testValidationFailure_MissingRequiredFields() {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("")  // Invalid - empty
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{}")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Implementation ID is required"));
        LOG.info("Validation correctly rejected request with missing implementation ID");
    }
    
    @Test
    @Order(2)
    void testValidationFailure_InvalidJson() {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{invalid json")  // Invalid JSON
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Invalid JSON format"));
        LOG.info("Validation correctly rejected request with invalid JSON");
    }
    
    @Test
    @Order(3)
    void testValidationFailure_UnreachableHost() {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("unreachable.host.invalid")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"test\": \"config\"}")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Cannot connect"));
        LOG.info("Validation correctly rejected request with unreachable host");
    }
    
    @Test
    @Order(4)
    void testSuccessfulRegistrationWithValidation() {
        // Use the actual Tika container
        String host = tikaContainer.getHost();
        int grpcPort = tikaContainer.getMappedPort(50051);
        int httpPort = tikaContainer.getMappedPort(8080);
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("tika-parser")
                .setInstanceServiceName("tika-parser-service")
                .setInstanceIdHint("tika-test")
                .setHost(host)
                .setPort(grpcPort)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("")  // gRPC health check doesn't need endpoint
                .setInstanceCustomConfigJson("{\"maxContentLength\": \"10000000\"}")
                .putAdditionalTags("test", "enhanced-registration")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        
        assertTrue(response.getSuccess(), "Registration should succeed");
        assertNotNull(response.getRegisteredServiceId());
        assertNotNull(response.getCalculatedConfigDigest());
        assertFalse(response.getCalculatedConfigDigest().isEmpty());
        
        registeredServiceId = response.getRegisteredServiceId();
        LOG.info("Successfully registered module with ID: {}", registeredServiceId);
        LOG.info("Config digest: {}", response.getCalculatedConfigDigest());
    }
    
    @Test
    @Order(5)
    void testHealthCheckAfterRegistration(ApplicationContext context) {
        assertNotNull(registeredServiceId, "Service should have been registered in previous test");
        
        ModuleRegistrationService registrationService = context.getBean(ModuleRegistrationService.class);
        
        Boolean isHealthy = registrationService.isModuleHealthy(registeredServiceId)
                .block();
        
        assertTrue(isHealthy, "Registered module should be healthy");
        LOG.info("Module {} health check passed", registeredServiceId);
    }
    
    @Test
    @Order(6)
    void testDeregistration(ApplicationContext context) {
        assertNotNull(registeredServiceId, "Service should have been registered in previous test");
        
        ModuleRegistrationService registrationService = context.getBean(ModuleRegistrationService.class);
        
        // Deregister the module
        registrationService.deregisterModule(registeredServiceId).block();
        
        // Verify it's no longer healthy (doesn't exist)
        Boolean isHealthy = registrationService.isModuleHealthy(registeredServiceId)
                .block();
        
        assertFalse(isHealthy, "Deregistered module should not be found");
        LOG.info("Module {} successfully deregistered", registeredServiceId);
    }
}