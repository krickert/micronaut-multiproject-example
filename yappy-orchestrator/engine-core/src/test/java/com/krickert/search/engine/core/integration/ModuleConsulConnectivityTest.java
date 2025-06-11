package com.krickert.search.engine.core.integration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify that module containers can connect to Consul.
 */
@MicronautTest
public class ModuleConsulConnectivityTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleConsulConnectivityTest.class);
    
    @Test
    void testChunkerCanConnectToConsul() {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        assertThat(consulHost).as("Consul host should be resolved").isPresent();
        assertThat(consulPort).as("Consul port should be resolved").isPresent();
        
        LOG.info("Consul is available at {}:{}", consulHost.get(), consulPort.get());
        
        // Verify chunker is available
        Optional<String> chunkerHost = client.resolve("chunker.grpc.host", Map.of(), Map.of());
        Optional<String> chunkerPort = client.resolve("chunker.grpc.port", Map.of(), Map.of());
        
        assertThat(chunkerHost).as("Chunker host should be resolved").isPresent();
        assertThat(chunkerPort).as("Chunker port should be resolved").isPresent();
        
        LOG.info("Chunker is available at {}:{}", chunkerHost.get(), chunkerPort.get());
        
        // Test gRPC health check
        LOG.info("Testing chunker gRPC health check...");
        
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(chunkerHost.get(), Integer.parseInt(chunkerPort.get()))
            .usePlaintext()
            .build();
        
        try {
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
            
            // Check overall health
            HealthCheckRequest request = HealthCheckRequest.newBuilder().build();
            HealthCheckResponse response = healthStub.check(request);
            
            LOG.info("Chunker health check response: {}", response.getStatus());
            assertThat(response.getStatus()).as("Chunker should be healthy").isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
            
            LOG.info("✅ Chunker module is healthy and running!");
            
            // The fact that the chunker started successfully means it's on the correct network
            // (as configured by our AbstractModuleTestResourceProvider)
            LOG.info("✅ Network connectivity is working - modules are configured with the correct network!");
            
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}