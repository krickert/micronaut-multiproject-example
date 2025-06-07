package com.krickert.yappy.integration;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.RegistrationService;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies the full registration flow:
 * 1. Module (tika-parser) exposes GetServiceRegistration
 * 2. Registration utility queries module
 * 3. Registration utility registers with engine
 * 4. Engine accepts registration
 */
@MicronautTest
@DisabledIfEnvironmentVariable(named = "CI", matches = "true") // Skip in CI until containers are set up
public class RegistrationFlowIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationFlowIntegrationTest.class);
    
    @Inject
    RegistrationService registrationService;
    
    @Test
    void testFullRegistrationFlow() throws InterruptedException {
        // This test assumes:
        // 1. tika-parser module is running on localhost:50051
        // 2. engine is running on localhost:50050
        // 3. Consul is available
        
        String moduleHost = "localhost";
        int modulePort = 50051;
        String engineEndpoint = "localhost:50050";
        
        // First verify the module is responding
        ManagedChannel moduleChannel = ManagedChannelBuilder
                .forAddress(moduleHost, modulePort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub moduleStub = 
                    PipeStepProcessorGrpc.newBlockingStub(moduleChannel);
            
            // Test GetServiceRegistration
            LOG.info("Testing module GetServiceRegistration...");
            ServiceRegistrationData moduleData = moduleStub.getServiceRegistration(Empty.getDefaultInstance());
            assertNotNull(moduleData);
            assertEquals("tika-parser", moduleData.getModuleName());
            assertTrue(moduleData.hasJsonConfigSchema());
            LOG.info("Module responded correctly: {}", moduleData.getModuleName());
            
        } finally {
            moduleChannel.shutdown();
            moduleChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // Now test the full registration flow
        LOG.info("Testing full registration flow...");
        registrationService.registerModule(
                moduleHost, 
                modulePort, 
                engineEndpoint,
                "tika-parser-test-instance",
                "GRPC",
                "grpc.health.v1.Health/Check",
                "1.0.0-TEST"
        );
        
        // Verify registration succeeded by checking with engine directly
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forTarget(engineEndpoint)
                .usePlaintext()
                .build();
        
        try {
            YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub engineStub =
                    YappyModuleRegistrationServiceGrpc.newBlockingStub(engineChannel);
            
            // Try registering again - should succeed (idempotent)
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId("tika-parser")
                    .setInstanceServiceName("tika-parser-test-instance-2")
                    .setHost(moduleHost)
                    .setPort(modulePort)
                    .setHealthCheckType(com.krickert.yappy.registration.api.HealthCheckType.GRPC)
                    .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                    .setModuleSoftwareVersion("1.0.0-TEST")
                    .build();
            
            RegisterModuleResponse response = engineStub.registerModule(request);
            assertTrue(response.getSuccess());
            assertNotNull(response.getRegisteredServiceId());
            LOG.info("Direct engine registration successful: {}", response.getMessage());
            
        } finally {
            engineChannel.shutdown();
            engineChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testModuleGetServiceRegistration() {
        // This test only requires the module to be running
        String moduleHost = "localhost";
        int modulePort = 50051;
        
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(moduleHost, modulePort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                    PipeStepProcessorGrpc.newBlockingStub(channel);
            
            ServiceRegistrationData response = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            assertNotNull(response);
            assertEquals("tika-parser", response.getModuleName());
            assertTrue(response.hasJsonConfigSchema());
            
            // Verify the schema is valid JSON
            String schema = response.getJsonConfigSchema();
            assertNotNull(schema);
            assertFalse(schema.isEmpty());
            assertTrue(schema.contains("\"type\""));
            assertTrue(schema.contains("\"properties\""));
            
            LOG.info("Module registration data retrieved successfully");
            LOG.info("Module name: {}", response.getModuleName());
            LOG.info("Has schema: {}", response.hasJsonConfigSchema());
            
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        }
    }
}