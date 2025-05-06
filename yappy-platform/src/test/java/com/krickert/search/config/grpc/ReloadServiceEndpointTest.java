package com.krickert.search.config.grpc;

import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify the functionality of the ReloadServiceEndpoint.
 * This test uses the Micronaut context and real gRPC calls.
 */
@MicronautTest(environments = "grpc-test")
public class ReloadServiceEndpointTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadServiceEndpointTest.class);

    @Inject
    private ReloadServiceGrpc.ReloadServiceBlockingStub blockingStub;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("grpc.server.port", "${random.port}");
        properties.put("grpc.server.enabled", "true");

        // Enable TestContainers as per README.md
        properties.put("testcontainers.enabled", "true");
        properties.put("testcontainers.kafka.enabled", "true");
        properties.put("testcontainers.apicurio.enabled", "true");
        properties.put("testcontainers.consul.enabled", "true");

        // Enable Kafka and Schema Registry
        properties.put("kafka.enabled", "true");
        properties.put("schema.registry.type", "apicurio");
        LOG.info("Test Properties for reload service endpoint test: {}", properties);

        return properties;
    }

    @Test
    @DisplayName("reloadPipeline method should invalidate pipeline cache")
    void testReloadPipelineInvalidatesCache() {
        // Arrange
        String pipelineName = "test-pipeline";
        PipelineReloadRequest request = PipelineReloadRequest.newBuilder()
                .setPipelineName(pipelineName)
                .build();

        // Act
        PipelineReloadResponse response = blockingStub.reloadPipeline(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertTrue(response.getMessage().contains(pipelineName), "Response message should contain pipeline name");
    }

    @Test
    @DisplayName("reloadPipeline method should handle empty pipeline name")
    void testReloadPipelineHandlesEmptyPipelineName() {
        // Arrange
        PipelineReloadRequest request = PipelineReloadRequest.newBuilder()
                .setPipelineName("")
                .build();

        // Act
        PipelineReloadResponse response = blockingStub.reloadPipeline(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertFalse(response.getSuccess(), "Response should indicate failure");
        assertTrue(response.getMessage().contains("empty"), "Response message should mention empty pipeline name");
    }

    @Test
    @DisplayName("reloadService method should invalidate service cache")
    void testReloadServiceInvalidatesCache() {
        // Arrange
        String serviceName = "test-service";
        PipeStepReloadRequest request = PipeStepReloadRequest.newBuilder()
                .setServiceName(serviceName)
                .build();

        // Act
        PipeStepReloadResponse response = blockingStub.reloadService(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertTrue(response.getMessage().contains(serviceName), "Response message should contain service name");
    }

    @Test
    @DisplayName("reloadService method should handle empty service name")
    void testReloadServiceHandlesEmptyServiceName() {
        // Arrange
        PipeStepReloadRequest request = PipeStepReloadRequest.newBuilder()
                .setServiceName("")
                .build();

        // Act
        PipeStepReloadResponse response = blockingStub.reloadService(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertFalse(response.getSuccess(), "Response should indicate failure");
        assertTrue(response.getMessage().contains("empty"), "Response message should mention empty service name");
    }

    @Test
    @DisplayName("applicationChanged method should invalidate application cache")
    void testApplicationChangedInvalidatesCache() {
        // Arrange
        String applicationName = "test-application";
        ApplicationChangeEvent request = ApplicationChangeEvent.newBuilder()
                .setApplication(applicationName)
                .build();

        // Act
        ApplicationChangeResponse response = blockingStub.applicationChanged(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertTrue(response.getMessage().contains(applicationName), "Response message should contain application name");
    }

    @Test
    @DisplayName("applicationChanged method should handle empty application name")
    void testApplicationChangedHandlesEmptyApplicationName() {
        // Arrange
        ApplicationChangeEvent request = ApplicationChangeEvent.newBuilder()
                .setApplication("")
                .build();

        // Act
        ApplicationChangeResponse response = blockingStub.applicationChanged(request);

        // Assert
        assertNotNull(response, "Response should not be null");
        assertFalse(response.getSuccess(), "Response should indicate failure");
        assertTrue(response.getMessage().contains("empty"), "Response message should mention empty application name");
    }

    @Factory
    static class Clients {

        @Bean
        ReloadServiceGrpc.ReloadServiceBlockingStub blockingStub(
                @GrpcChannel(GrpcServerChannel.NAME) ManagedChannel channel) {
            return ReloadServiceGrpc.newBlockingStub(
                    channel
            );
        }
    }
}
