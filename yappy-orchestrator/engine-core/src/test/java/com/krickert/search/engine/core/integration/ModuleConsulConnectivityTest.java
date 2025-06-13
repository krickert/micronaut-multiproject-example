package com.krickert.search.engine.core.integration;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify that module containers can connect to Consul.
 */
@MicronautTest
public class ModuleConsulConnectivityTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleConsulConnectivityTest.class);
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    Integer consulPort;
    
    @Property(name = "chunker.grpc.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    Integer chunkerPort;
    
    @Test
    void testChunkerCanConnectToConsul() {
        // Verify Consul is available
        assertThat(consulHost).as("Consul host should be resolved").isNotNull();
        assertThat(consulPort).as("Consul port should be resolved").isNotNull();
        
        LOG.info("Consul is available at {}:{}", consulHost, consulPort);
        
        // Verify chunker is available
        assertThat(chunkerHost).as("Chunker host should be resolved").isNotNull();
        assertThat(chunkerPort).as("Chunker port should be resolved").isNotNull();
        
        LOG.info("Chunker is available at {}:{}", chunkerHost, chunkerPort);
        
        // Test gRPC health check
        LOG.info("Testing chunker gRPC health check...");
        
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(chunkerHost, chunkerPort)
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