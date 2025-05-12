package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.model.KafkaRouteTarget;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "pipeline.step.name", value = "test-service")
@Property(name = "pipeline.name", value = "test-pipeline")
@Property(name = "pipeline.step.implementation", value = "com.example.TestService")
@Property(name = "pipeline.listen.topics", value = "input-topic-1, input-topic-2")
@Property(name = "pipeline.publish.topics", value = "output-topic")
@Property(name = "pipeline.grpc.forward.to", value = "forward-service")
@Property(name = "pipeline.step.registration.enabled", value = "true")
/**
 * Tests for PipeStepRegistrationManager with registration enabled.
 * Tests that require different property values are in PipeStepRegistrationManagerDisabledTest.
 */
class PipeStepRegistrationManagerTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStepRegistrationManagerTest.class);

    @Inject
    private PipeStepRegistrationManager pipeStepRegistrationManager;

    @Inject
    private PipelineService pipelineService;

    @Inject
    private ConsulKvService consulKvService;

    @Test
    @Order(1)
    @DisplayName("Service registers when pipeline exists and service is not registered")
    void testServiceRegistersWhenPipelineExistsAndServiceNotRegistered() {
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
            assertTrue(updatedPipeline.getServices().containsKey("test-service"), "Pipeline should contain the test service");

            PipeStepConfigurationDto serviceConfig = updatedPipeline.getServices().get("test-service");
            assertEquals("test-service", serviceConfig.getName());
            assertEquals("com.example.TestService", serviceConfig.getServiceImplementation());
            assertEquals(List.of("input-topic-1", "input-topic-2"), serviceConfig.getKafkaListenTopics());
            assertEquals(List.of(new KafkaRouteTarget("output-topic", null)), serviceConfig.getKafkaPublishTopics());
            assertEquals(List.of("forward-service"), serviceConfig.getGrpcForwardTo());
        } catch (Exception e) {
            fail("Failed to get pipeline after registration: " + e.getMessage());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Service does not register when already registered")
    void testServiceDoesNotRegisterWhenAlreadyRegistered() {
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

        // Add a service to the pipeline
        String serviceNameKey = consulKvService.getFullPath("pipeline.configs.test-pipeline.services.test-service.name");
        String serviceImplKey = consulKvService.getFullPath("pipeline.configs.test-pipeline.services.test-service.serviceImplementation");
        consulKvService.putValue(serviceNameKey, "test-service").block();
        consulKvService.putValue(serviceImplKey, "com.example.ExistingService").block();

        // Get the version before registration attempt
        PipelineConfigDto beforePipeline = pipelineService.getPipeline("test-pipeline").block();
        assertNotNull(beforePipeline, "Pipeline should exist before registration attempt");
        long versionBefore = beforePipeline.getPipelineVersion();

        // Act - Trigger service registration
        pipeStepRegistrationManager.onApplicationEvent(null);

        // Wait a bit for async operations to complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Assert - Verify the service was not re-registered (version should not change)
        try {
            PipelineConfigDto afterPipeline = pipelineService.getPipeline("test-pipeline").block();
            assertNotNull(afterPipeline, "Pipeline should exist after registration attempt");
            assertEquals(versionBefore, afterPipeline.getPipelineVersion(), "Pipeline version should not change");

            // Verify the service configuration was not changed
            PipeStepConfigurationDto serviceConfig = afterPipeline.getServices().get("test-service");
            assertEquals("test-service", serviceConfig.getName());
            assertEquals("com.example.ExistingService", serviceConfig.getServiceImplementation());
        } catch (Exception e) {
            fail("Failed to get pipeline after registration: " + e.getMessage());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Service creates pipeline when it doesn't exist")
    void testServiceCreatesPipelineWhenItDoesntExist() {
        // Arrange - Ensure pipeline doesn't exist
        try {
            pipelineService.getPipeline("test-pipeline").block();
            // If we get here, the pipeline exists, so delete it
            pipelineService.deletePipeline("test-pipeline").block();
            LOG.info("Deleted existing pipeline for test");
        } catch (Exception e) {
            // Pipeline doesn't exist, which is also fine
            LOG.info("Pipeline not found as expected: {}", e.getMessage());
        }

        // Verify pipeline doesn't exist after deletion
        try {
            pipelineService.getPipeline("test-pipeline").block();
            fail("Pipeline should not exist at this point");
        } catch (Exception e) {
            // Expected exceptions
            LOG.info("Confirmed pipeline does not exist: {}", e.getMessage());
        }

        // Act - Trigger service registration
        pipeStepRegistrationManager.onApplicationEvent(null);

        // Assert - Verify the pipeline was created and service was registered
        PipelineConfigDto createdPipeline = pipelineService.getPipeline("test-pipeline").block();
        assertNotNull(createdPipeline, "Pipeline should be created");
        assertEquals("test-pipeline", createdPipeline.getName());
        assertFalse(createdPipeline.getServices().isEmpty(), "Pipeline should have services");
        assertTrue(createdPipeline.getServices().containsKey("test-service"), "Pipeline should contain the test service");

        PipeStepConfigurationDto serviceConfig = createdPipeline.getServices().get("test-service");
        assertEquals("test-service", serviceConfig.getName());
        assertEquals("com.example.TestService", serviceConfig.getServiceImplementation());
    }

    // Tests for disabled registration and empty pipeline name have been moved to PipeStepRegistrationManagerDisabledTest

    @Override
    public Map<String, String> getProperties() {
        Map<String,String> properties = new HashMap<>();
        properties.put("micronaut.config-client.enabled", "false");
        properties.put("consul.client.registration.enabled", "true");
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.discovery.enabled", "true");
        properties.put("consul.client.watch.enabled", "false");
        properties.put("consul.data.seeding.enabled", "false");
        return properties;
    }

}
