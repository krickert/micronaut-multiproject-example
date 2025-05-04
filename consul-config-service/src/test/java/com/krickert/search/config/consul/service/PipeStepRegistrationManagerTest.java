package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
            assertEquals(List.of("output-topic"), serviceConfig.getKafkaPublishTopics());
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
            fail("Exception should have been thrown");
        } catch (Exception e) {
            // Pipeline doesn't exist, which is what we want
            LOG.info("Pipeline not found as expected: {}", e.getMessage());
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

    @Test
    @Order(4)
    @DisplayName("Service registration is skipped when disabled")
    @Property(name = "pipeline.step.registration.enabled", value = "false")
    void testServiceRegistrationSkippedWhenDisabled() {
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

        // Assert - Verify the pipeline was not created
        Mono<PipelineConfigDto> checkPipeline = pipelineService.getPipeline("test-pipeline");
        assertThrows(Exception.class, () -> checkPipeline.block(), "Pipeline should not be created when registration is disabled");
    }

    @Test
    @Order(5)
    @DisplayName("Service can run without pipeline configuration")
    @Property(name = "pipeline.name", value = "")
    void testServiceCanRunWithoutPipelineConfiguration() {
        // Act - Trigger service registration with empty pipeline name
        pipeStepRegistrationManager.onApplicationEvent(null);

        // No assertions needed - we're just verifying that the service doesn't throw an exception
        // and can run without being registered to a pipeline

        // Try to get all pipelines to verify no new pipeline was created
        List<String> pipelines = pipelineService.listPipelines().block();
        assertNotNull(pipelines, "Pipeline list should not be null");

        // If any pipelines exist, verify none of them contain our service
        for (String pipelineName : pipelines) {
            try {
                PipelineConfigDto pipeline = pipelineService.getPipeline(pipelineName).block();
                if (pipeline != null && pipeline.getServices() != null) {
                    assertFalse(pipeline.getServices().containsKey("test-service"), 
                        "Service should not be registered to any pipeline when pipeline name is empty");
                }
            } catch (Exception e) {
                // Ignore exceptions when getting pipelines
                LOG.info("Error getting pipeline {}: {}", pipelineName, e.getMessage());
            }
        }
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String,String> properties = new HashMap<>();
        properties.put("pipeline.step.name", "test-service");
        properties.put("pipeline.name", "test-pipeline");
        properties.put("pipeline.step.implementation", "com.example.TestService");
        properties.put("pipeline.listen.topics", "input-topic-1, input-topic-2");
        properties.put("pipeline.publish.topics", "output-topic");
        properties.put("pipeline.grpc.forward.to", "forward-service");
        properties.put("consul.data.seeding.enabled", "false");
        properties.put("pipeline.step.registration.enabled", "false");
        return properties;
    }

}
