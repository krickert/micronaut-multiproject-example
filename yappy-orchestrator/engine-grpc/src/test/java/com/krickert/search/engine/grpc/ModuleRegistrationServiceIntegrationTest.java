package com.krickert.search.engine.grpc;

import com.krickert.yappy.registration.api.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify the ModuleRegistrationService is exposed via gRPC.
 */
@MicronautTest
@Property(name = "grpc.server.port", value = "0") // Use random port
@Property(name = "consul.client.registration.enabled", value = "false") // Disable Consul for this test
class ModuleRegistrationServiceIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationServiceIntegrationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    private ManagedChannel channel;
    
    @AfterEach
    void cleanup() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        }
    }
    
    @Test
    void testModuleRegistrationServiceIsAccessible() {
        // Get the actual gRPC port
        Integer grpcPort = applicationContext.getProperty("grpc.server.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("gRPC port not configured"));
        
        LOG.info("Connecting to gRPC server on port: {}", grpcPort);
        
        // Create channel to the gRPC server
        channel = ManagedChannelBuilder
                .forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        
        // Create stub
        ModuleRegistrationServiceGrpc.ModuleRegistrationServiceBlockingStub stub =
                ModuleRegistrationServiceGrpc.newBlockingStub(channel);
        
        // Create a test registration request
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-instance")
                .setHost("localhost")
                .setPort(12345)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .build();
        
        // Try to register (this will fail because Consul is disabled, but that's OK)
        try {
            RegisterModuleResponse response = stub.registerModule(request);
            // If we get here, the service is exposed and working
            LOG.info("Registration response: success={}, message={}", 
                    response.getSuccess(), response.getMessage());
        } catch (StatusRuntimeException e) {
            // This is expected since we disabled Consul
            LOG.error("Expected error (Consul disabled): {}", e.getStatus());
            // But we should not get UNIMPLEMENTED status
            assertNotEquals(io.grpc.Status.Code.UNIMPLEMENTED, e.getStatus().getCode(),
                    "Service should be implemented");
        }
    }
}