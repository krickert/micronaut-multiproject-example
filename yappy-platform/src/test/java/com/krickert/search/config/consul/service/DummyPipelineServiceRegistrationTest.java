package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that the dummy gRPC service can be registered with Consul.
 */
@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "pipeline.step.name", value = "dummy-grpc-service")
@Property(name = "pipeline.name", value = "test-pipeline")
@Property(name = "pipeline.step.implementation", value = "com.krickert.search.config.consul.service.DummyPipelineServiceImpl")
@Property(name = "pipeline.listen.topics", value = "input-topic-1,input-topic-2")
@Property(name = "pipeline.publish.topics", value = "output-topic")
@Property(name = "pipeline.grpc.forward.to", value = "forward-service")
@Property(name = "pipeline.step.registration.enabled", value = "true")
public class DummyPipelineServiceRegistrationTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DummyPipelineServiceRegistrationTest.class);

    @Inject
    private PipeStepRegistrationManager pipeStepRegistrationManager;

    @Inject
    private PipelineService pipelineService;

    @Inject
    private ConsulKvService consulKvService;

    @BeforeEach
    void setUp() {
        // Clear any existing pipeline configurations
        String configPath = consulKvService.getFullPath("pipeline.configs");
        consulKvService.deleteKeysWithPrefix(configPath).block();
    }

    @Test
    @Order(1)
    @DisplayName("Dummy gRPC service registers when pipeline exists")
    void testDummyGrpcServiceRegistersWithPipeline() {
        // Arrange - Ensure pipeline doesn't exist
        try {
            pipelineService.getPipeline("test-pipeline").block();
            // If we get here, the pipeline exists, so delete it
            pipelineService.deletePipeline("test-pipeline").block();
        } catch (Exception e) {
            // Pipeline doesn't exist, which is what we want
            LOG.info("Pipeline not found as expected: {}", e.getMessage());
        }

        // Create a pipeline manually using ConsulKvService
        String versionKey = consulKvService.getFullPath("pipeline.configs.test-pipeline.version");
        String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs.test-pipeline.lastUpdated");
        consulKvService.putValue(versionKey, "1").block();
        consulKvService.putValue(lastUpdatedKey, LocalDateTime.now().toString()).block();

        // Act - Trigger service registration
        pipeStepRegistrationManager.onApplicationEvent(null);

        // Wait a bit for async operations to complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert - Verify the service was registered
        try {
            PipelineConfigDto updatedPipeline = pipelineService.getPipeline("test-pipeline").block();
            assertNotNull(updatedPipeline, "Pipeline should exist after registration");
            assertFalse(updatedPipeline.getServices().isEmpty(), "Pipeline should have services after registration");
            assertTrue(updatedPipeline.getServices().containsKey("dummy-grpc-service"), 
                    "Pipeline should contain the dummy gRPC service");

            PipeStepConfigurationDto serviceConfig = updatedPipeline.getServices().get("dummy-grpc-service");
            assertEquals("dummy-grpc-service", serviceConfig.getName());
            assertEquals("com.krickert.search.config.consul.service.DummyPipelineServiceImpl", 
                    serviceConfig.getServiceImplementation());
            assertNotNull(serviceConfig.getKafkaListenTopics(), "Kafka listen topics should not be null");
            assertNotNull(serviceConfig.getKafkaPublishTopics(), "Kafka publish topics should not be null");
            assertNotNull(serviceConfig.getGrpcForwardTo(), "gRPC forward to should not be null");
        } catch (Exception e) {
            fail("Failed to get pipeline after registration: " + e.getMessage());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Dummy gRPC service creates pipeline when it doesn't exist")
    void testDummyGrpcServiceCreatesPipeline() {
        // Arrange - Ensure pipeline doesn't exist
        try {
            pipelineService.getPipeline("test-pipeline").block();
            // If we get here, the pipeline exists, so delete it
            pipelineService.deletePipeline("test-pipeline").block();
        } catch (Exception e) {
            // Pipeline doesn't exist, which is what we want
            LOG.info("Pipeline not found as expected: {}", e.getMessage());
        }

        // Act - Trigger service registration
        pipeStepRegistrationManager.onApplicationEvent(null);

        // Wait a bit for async operations to complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert - Verify the pipeline was created and the service was registered
        try {
            PipelineConfigDto createdPipeline = pipelineService.getPipeline("test-pipeline").block();
            assertNotNull(createdPipeline, "Pipeline should be created");
            assertFalse(createdPipeline.getServices().isEmpty(), "Pipeline should have services");
            assertTrue(createdPipeline.getServices().containsKey("dummy-grpc-service"), 
                    "Pipeline should contain the dummy gRPC service");
        } catch (Exception e) {
            fail("Failed to get pipeline after registration: " + e.getMessage());
        }
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("micronaut.config-client.enabled", "false");
        properties.put("consul.client.registration.enabled", "true");
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.discovery.enabled", "true");
        properties.put("consul.client.watch.enabled", "false");
        properties.put("pipeline.service.registration.enabled", "true");
        properties.put("grpc.server.enabled", "true");
        return properties;
    }
}
