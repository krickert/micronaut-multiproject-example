package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.exception.PipelineVersionConflictException;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pipeline versioning functionality.
 */
@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineVersioningTest implements TestPropertyProvider {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PipelineVersioningTest.class);
    @Inject
    private ConsulKvService consulKvService;
    private PipelineConfig pipelineConfig;

    @BeforeEach
    void setUp() {
        // Create a PipelineConfig with the TestConsulKvService
        pipelineConfig = new PipelineConfig(consulKvService);
    }

    /**
     * Test that a pipeline can be successfully updated when the versions match.
     */
    @Test
    void testSuccessfulUpdate() {
        // Create a pipeline
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");
        System.out.println("Initial pipeline version: " + pipeline.getPipelineVersion());

        // Add a service to the pipeline
        PipeStepConfigurationDto service = new PipeStepConfigurationDto();
        service.setName("test-service");
        pipeline.addOrUpdateService(service);

        // Add the pipeline to the PipelineConfig
        pipelineConfig.addOrUpdatePipeline(pipeline).block();

        // Get the pipeline from the PipelineConfig
        PipelineConfigDto retrievedPipeline = pipelineConfig.getPipeline("test-pipeline");
        System.out.println("Retrieved pipeline version after first update: " + retrievedPipeline.getPipelineVersion());

        // Verify that the pipeline was added
        assertNotNull(retrievedPipeline);
        assertEquals("test-pipeline", retrievedPipeline.getName());
        assertEquals(1, retrievedPipeline.getPipelineVersion());

        // Update the pipeline
        retrievedPipeline.addOrUpdateService(service);

        // Update the pipeline in the PipelineConfig
        pipelineConfig.addOrUpdatePipeline(retrievedPipeline).block();

        // Get the updated pipeline
        PipelineConfigDto updatedPipeline = pipelineConfig.getPipeline("test-pipeline");
        System.out.println("Retrieved pipeline version after second update: " + updatedPipeline.getPipelineVersion());

        // Verify that the pipeline was updated and the version was incremented
        assertNotNull(updatedPipeline);
        assertEquals("test-pipeline", updatedPipeline.getName());
        assertEquals(2, updatedPipeline.getPipelineVersion());
    }

    /**
     * Test that a PipelineVersionConflictException is thrown when the versions don't match.
     */
    @Test
    void testVersionConflict() {
        // Create a pipeline
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Add a service to the pipeline
        PipeStepConfigurationDto service = new PipeStepConfigurationDto();
        service.setName("test-service");
        pipeline.addOrUpdateService(service);

        // Add the pipeline to the PipelineConfig
        pipelineConfig.addOrUpdatePipeline(pipeline).block();

        // Get the pipeline from the PipelineConfig (Session 1)
        PipelineConfigDto session1Pipeline = pipelineConfig.getPipeline("test-pipeline");

        // Get the pipeline from the PipelineConfig again (Session 2)
        PipelineConfigDto session2Pipeline = pipelineConfig.getPipeline("test-pipeline");

        // Update the pipeline in Session 2
        session2Pipeline.addOrUpdateService(service);

        // Update the pipeline in the PipelineConfig from Session 2
        pipelineConfig.addOrUpdatePipeline(session2Pipeline).block();

        // Update the pipeline in Session 1
        session1Pipeline.addOrUpdateService(service);

        // Try to update the pipeline in the PipelineConfig from Session 1
        // This should throw a PipelineVersionConflictException
        PipelineVersionConflictException exception = assertThrows(
            PipelineVersionConflictException.class,
            () -> pipelineConfig.addOrUpdatePipeline(session1Pipeline).block()
        );

        // Verify the exception details
        assertEquals("test-pipeline", exception.getPipelineName());
        assertEquals(1, exception.getExpectedVersion());
        assertEquals(2, exception.getActualVersion());
        assertNotNull(exception.getLastUpdated());
    }

    /**
     * Test that a service can be deleted from a pipeline.
     */
    @Test
    void testServiceDeletion() {
        // Create a pipeline
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Add a service to the pipeline
        PipeStepConfigurationDto service1 = new PipeStepConfigurationDto();
        service1.setName("service1");
        pipeline.addOrUpdateService(service1);

        // Add another service to the pipeline
        PipeStepConfigurationDto service2 = new PipeStepConfigurationDto();
        service2.setName("service2");
        pipeline.addOrUpdateService(service2);

        // Add the pipeline to the PipelineConfig
        pipelineConfig.addOrUpdatePipeline(pipeline).block();

        // Get the pipeline from the PipelineConfig (Session 1)
        PipelineConfigDto session1Pipeline = pipelineConfig.getPipeline("test-pipeline");

        // Get the pipeline from the PipelineConfig again (Session 2)
        PipelineConfigDto session2Pipeline = pipelineConfig.getPipeline("test-pipeline");

        // Remove service1 in Session 1
        session1Pipeline.removeService("service1");

        // Update the pipeline in the PipelineConfig from Session 1
        pipelineConfig.addOrUpdatePipeline(session1Pipeline).block();

        // Try to update service1 in Session 2
        PipeStepConfigurationDto updatedService1 = new PipeStepConfigurationDto();
        updatedService1.setName("service1");
        session2Pipeline.addOrUpdateService(updatedService1);

        // Try to update the pipeline in the PipelineConfig from Session 2
        // This should throw a PipelineVersionConflictException
        PipelineVersionConflictException exception = assertThrows(
            PipelineVersionConflictException.class,
            () -> pipelineConfig.addOrUpdatePipeline(session2Pipeline).block()
        );

        // Verify the exception details
        assertEquals("test-pipeline", exception.getPipelineName());
        assertEquals(1, exception.getExpectedVersion());
        assertEquals(2, exception.getActualVersion());
        assertNotNull(exception.getLastUpdated());
    }

    /**
     * Allows dynamically providing properties for a test.
     *
     * @return A map of properties
     */
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        // Get base properties from the container
        Map<String, String> properties = new HashMap<>(container.getProperties());

        // Add test-specific properties
        properties.put("consul.data.seeding.enabled", "true");
        properties.put("consul.data.seeding.file", "seed-data.yaml");
        properties.put("consul.data.seeding.skip-if-exists", "true");

        return properties;
    }
}
