package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.model.PipelineConfigDto;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PipeStepRegistrationManager with registration disabled or empty pipeline name.
 * This is separated from the main test class to avoid property conflicts.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "pipeline.step.name", value = "test-service")
@Property(name = "pipeline.step.implementation", value = "com.example.TestService")
@Property(name = "pipeline.listen.topics", value = "input-topic-1, input-topic-2")
@Property(name = "pipeline.publish.topics", value = "output-topic")
@Property(name = "pipeline.grpc.forward.to", value = "forward-service")
public class PipeStepRegistrationManagerDisabledTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStepRegistrationManagerDisabledTest.class);

    @Inject
    private PipeStepRegistrationManager pipeStepRegistrationManager;

    @Inject
    private PipelineService pipelineService;

    @Test
    @Order(1)
    @DisplayName("Service registration is skipped when disabled")
    @Property(name = "pipeline.name", value = "test-pipeline")
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
    @Order(2)
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
        properties.put("micronaut.config-client.enabled", "false");
        properties.put("consul.client.registration.enabled", "true");
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.discovery.enabled", "true");
        properties.put("consul.client.watch.enabled", "false");
        properties.put("consul.data.seeding.enabled", "false");
        return properties;
    }
}