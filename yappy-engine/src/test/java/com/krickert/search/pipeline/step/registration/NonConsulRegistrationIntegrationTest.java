package com.krickert.search.pipeline.step.registration;

import com.google.protobuf.Empty;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.pipeline.test.dummy.DummyPipeStepProcessor;
import com.krickert.search.pipeline.test.dummy.StandaloneDummyGrpcServer;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the non-Consul (in-memory) module registration.
 * This test demonstrates that the system works without Consul for simpler deployments.
 */
@MicronautTest(environments = {"test", "non-consul-test"})
@Property(name = "consul.client.enabled", value = "false") // Disable Consul
@Property(name = "kafka.enabled", value = "false") // Disable Kafka for this test
@Property(name = "app.engine.bootstrapper.enabled", value = "false")
@Property(name = "grpc.server.port", value = "50055") // Different port to avoid conflicts
public class NonConsulRegistrationIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(NonConsulRegistrationIntegrationTest.class);
    
    private static final int MODULE_PORT_1 = 50071;
    private static final int MODULE_PORT_2 = 50072;
    private static final int ENGINE_PORT = 50055;
    
    @Inject
    InMemoryModuleRegistrationService registrationService;
    
    private ManagedChannel engineChannel;
    private YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub registrationStub;
    
    private StandaloneDummyGrpcServer moduleServer1;
    private StandaloneDummyGrpcServer moduleServer2;
    
    @BeforeEach
    void setUp() throws Exception {
        LOG.info("=== Setting up non-Consul registration test ===");
        
        // Clear any previous registrations
        registrationService.clearAll();
        
        // Start two module servers
        LOG.info("Starting module servers on ports {} and {}", MODULE_PORT_1, MODULE_PORT_2);
        
        DummyPipeStepProcessor processor1 = new DummyPipeStepProcessor("append", " [MODULE-1]");
        moduleServer1 = new StandaloneDummyGrpcServer(MODULE_PORT_1, processor1);
        moduleServer1.start();
        
        DummyPipeStepProcessor processor2 = new DummyPipeStepProcessor("uppercase", "");
        moduleServer2 = new StandaloneDummyGrpcServer(MODULE_PORT_2, processor2);
        moduleServer2.start();
        
        await().atMost(Duration.ofSeconds(5))
                .until(() -> moduleServer1.isRunning() && moduleServer2.isRunning());
        
        LOG.info("Module servers started successfully");
        
        // Initialize engine channel and registration stub
        engineChannel = ManagedChannelBuilder
                .forAddress("localhost", ENGINE_PORT)
                .usePlaintext()
                .build();
        
        registrationStub = YappyModuleRegistrationServiceGrpc.newBlockingStub(engineChannel);
    }
    
    @Test
    @DisplayName("Should register modules without Consul")
    void testBasicRegistrationWithoutConsul() {
        // Register first module using direct gRPC call
        LOG.info("Registering first module via direct gRPC call");
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("dummy-processor-instance-1")
                .setHost("localhost")
                .setPort(MODULE_PORT_1)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setModuleSoftwareVersion("1.0.0")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        assertTrue(response.getSuccess());
        
        // Verify registration in memory
        var instances = registrationService.getModuleInstances();
        assertEquals(1, instances.size());
        
        var instance = instances.values().iterator().next();
        assertEquals("dummy-processor", instance.implementationId());
        assertEquals("dummy-processor-instance-1", instance.instanceName());
        assertEquals(MODULE_PORT_1, instance.registration().port());
        
        LOG.info("First module registered successfully: {}", instance.serviceId());
        
        // Register second module directly via gRPC
        LOG.info("Registering second module via direct gRPC call");
        
        RegisterModuleRequest request2 = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("dummy-processor-instance-2")
                .setHost("localhost")
                .setPort(MODULE_PORT_2)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setModuleSoftwareVersion("2.0.0")
                .setInstanceCustomConfigJson("{\"behavior\": \"uppercase\"}")
                .build();
        
        RegisterModuleResponse response2 = registrationStub.registerModule(request2);
        
        assertTrue(response2.getSuccess());
        assertNotNull(response2.getRegisteredServiceId());
        assertFalse(response2.getCalculatedConfigDigest().isEmpty());
        
        LOG.info("Second module registered: {}", response2.getRegisteredServiceId());
        
        // Verify both modules are registered
        instances = registrationService.getModuleInstances();
        assertEquals(2, instances.size());
        
        // Verify we can find instances by implementation
        var dummyInstances = registrationService.findInstancesByImplementation("dummy-processor");
        assertEquals(2, dummyInstances.size());
    }
    
    @Test
    @DisplayName("Should handle module querying and registration flow")
    void testFullRegistrationFlow() {
        // Step 1: Query module for its registration info
        LOG.info("Querying module for registration info");
        
        ManagedChannel moduleChannel = ManagedChannelBuilder
                .forAddress("localhost", MODULE_PORT_1)
                .usePlaintext()
                .build();
        
        ServiceRegistrationData moduleData;
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                    PipeStepProcessorGrpc.newBlockingStub(moduleChannel);
            
            moduleData = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            assertEquals("dummy-processor", moduleData.getModuleName());
            assertTrue(moduleData.hasJsonConfigSchema());
            
            LOG.info("Module reports name: {}", moduleData.getModuleName());
            
        } finally {
            moduleChannel.shutdown();
            try {
                moduleChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                moduleChannel.shutdownNow();
            }
        }
        
        // Step 2: Register with engine using the module's info
        LOG.info("Registering module with engine");
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId(moduleData.getModuleName())
                .setInstanceServiceName("test-instance")
                .setHost("localhost")
                .setPort(MODULE_PORT_1)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson(moduleData.getJsonConfigSchema())
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        
        assertTrue(response.getSuccess());
        assertNotNull(response.getRegisteredServiceId());
        
        // Verify canonical config was processed
        assertTrue(response.hasCanonicalConfigJsonBase64());
        String canonicalJson = new String(
                java.util.Base64.getDecoder().decode(response.getCanonicalConfigJsonBase64())
        );
        assertTrue(canonicalJson.contains("behavior"));
        
        LOG.info("Registration successful: {}", response.getRegisteredServiceId());
    }
    
    @Test
    @DisplayName("Should handle duplicate registrations")
    void testDuplicateRegistrations() {
        // Register a module
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("test-instance")
                .setHost("localhost")
                .setPort(MODULE_PORT_1)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setModuleSoftwareVersion("1.0.0")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        assertTrue(response.getSuccess());
        
        assertEquals(1, registrationService.getModuleInstances().size());
        
        // Register same module again with same instance name
        RegisterModuleResponse response2 = registrationStub.registerModule(request);
        assertTrue(response2.getSuccess());
        
        // Should have 2 instances (different service IDs)
        assertEquals(2, registrationService.getModuleInstances().size());
        
        // But still only one module registration
        assertEquals(1, registrationService.getRegisteredModules().size());
    }
    
    @Test
    @DisplayName("Should unregister module instances")
    void testModuleUnregistration() {
        // Register a module
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("test-instance")
                .setHost("localhost")
                .setPort(MODULE_PORT_1)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setModuleSoftwareVersion("1.0.0")
                .build();
        
        RegisterModuleResponse response = registrationStub.registerModule(request);
        assertTrue(response.getSuccess());
        
        var instances = registrationService.getModuleInstances();
        assertEquals(1, instances.size());
        String serviceId = instances.keySet().iterator().next();
        
        // Unregister the instance
        boolean removed = registrationService.unregisterInstance(serviceId);
        assertTrue(removed);
        
        // Verify it's gone
        assertEquals(0, registrationService.getModuleInstances().size());
        assertEquals(0, registrationService.getRegisteredModules().size());
    }
    
    @Test
    @DisplayName("Should handle multiple instances of same module")
    void testMultipleInstancesOfSameModule() {
        // Register multiple instances of the same module
        for (int i = 1; i <= 3; i++) {
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId("dummy-processor")
                    .setInstanceServiceName("instance-" + i)
                    .setHost("localhost")
                    .setPort(MODULE_PORT_1)
                    .setHealthCheckType(HealthCheckType.GRPC)
                    .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                    .setModuleSoftwareVersion("1.0.0")
                    .build();
            
            RegisterModuleResponse response = registrationStub.registerModule(request);
            assertTrue(response.getSuccess());
        }
        
        // Should have 3 instances
        assertEquals(3, registrationService.getModuleInstances().size());
        
        // But only 1 module registration
        assertEquals(1, registrationService.getRegisteredModules().size());
        
        // Find all instances of dummy-processor
        var dummyInstances = registrationService.findInstancesByImplementation("dummy-processor");
        assertEquals(3, dummyInstances.size());
        
        // Unregister one instance
        String firstServiceId = dummyInstances.keySet().iterator().next();
        registrationService.unregisterInstance(firstServiceId);
        
        // Should still have the module registration (2 instances left)
        assertEquals(2, registrationService.getModuleInstances().size());
        assertEquals(1, registrationService.getRegisteredModules().size());
        
        // Unregister remaining instances
        for (String serviceId : registrationService.getModuleInstances().keySet()) {
            registrationService.unregisterInstance(serviceId);
        }
        
        // Now module registration should be gone too
        assertEquals(0, registrationService.getModuleInstances().size());
        assertEquals(0, registrationService.getRegisteredModules().size());
    }
    
    @Test
    @DisplayName("Should work with different module configurations")
    void testDifferentModuleConfigurations() {
        // Module 1: append behavior
        RegisterModuleRequest request1 = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("append-instance")
                .setHost("localhost")
                .setPort(MODULE_PORT_1)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson("{\"behavior\": \"append\", \"delay_ms\": 0}")
                .build();
        
        RegisterModuleResponse response1 = registrationStub.registerModule(request1);
        assertTrue(response1.getSuccess());
        String digest1 = response1.getCalculatedConfigDigest();
        
        // Module 2: uppercase behavior
        RegisterModuleRequest request2 = RegisterModuleRequest.newBuilder()
                .setImplementationId("dummy-processor")
                .setInstanceServiceName("uppercase-instance")
                .setHost("localhost")
                .setPort(MODULE_PORT_2)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson("{\"behavior\": \"uppercase\", \"delay_ms\": 100}")
                .build();
        
        RegisterModuleResponse response2 = registrationStub.registerModule(request2);
        assertTrue(response2.getSuccess());
        String digest2 = response2.getCalculatedConfigDigest();
        
        // Different configs should have different digests
        assertNotEquals(digest1, digest2);
        
        // Verify both are registered
        assertEquals(2, registrationService.getModuleInstances().size());
        
        LOG.info("Registered modules with different configs - digests: {} vs {}", 
                digest1, digest2);
    }
    
    @AfterEach
    void tearDown() {
        LOG.info("Cleaning up test");
        
        if (moduleServer1 != null) {
            try {
                moduleServer1.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (moduleServer2 != null) {
            try {
                moduleServer2.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (engineChannel != null) {
            engineChannel.shutdown();
            try {
                if (!engineChannel.awaitTermination(5, TimeUnit.SECONDS)) {
                    engineChannel.shutdownNow();
                }
            } catch (InterruptedException e) {
                engineChannel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        registrationService.clearAll();
        
        LOG.info("Cleanup complete");
    }
}