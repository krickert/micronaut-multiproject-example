package com.krickert.search.config.consul.validation;

import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PipelineValidator class.
 */
public class PipelineValidatorTest {

    /**
     * Tests that a simple pipeline with no loops is valid.
     */
    @Test
    public void testNoLoop() {
        // Create a pipeline with a simple linear flow
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", null, Collections.singletonList("topic1"));
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"), Collections.singletonList("topic2"));
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        // Verify that there is no loop
        assertFalse(PipelineValidator.hasLoop(pipeline, serviceC));
    }

    /**
     * Tests that a pipeline with a loop is detected.
     */
    @Test
    public void testLoop() {
        // Create a pipeline with a loop
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", Collections.singletonList("topic3"), Collections.singletonList("topic1"));
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"), Collections.singletonList("topic2"));
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2 and publishes to topic3, creating a loop
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), Collections.singletonList("topic3"));

        // Verify that a loop is detected
        assertTrue(PipelineValidator.hasLoop(pipeline, serviceC));

        // Verify that adding the service throws an exception
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            pipeline.addOrUpdateService(serviceC);
        });

        assertTrue(exception.getMessage().contains("would create a loop"));
    }

    /**
     * Tests that a loop is detected when using gRPC forwarding.
     */
    @Test
    public void testLoopWithGrpc() {
        // Create a pipeline with a loop using gRPC forwarding
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A forwards to Service B via gRPC
        PipeStepConfigurationDto serviceA = createServiceWithGrpc("serviceA", null, null, Collections.singletonList("serviceB"));
        pipeline.addOrUpdateService(serviceA);

        // Service B forwards to Service C via gRPC
        PipeStepConfigurationDto serviceB = createServiceWithGrpc("serviceB", null, null, Collections.singletonList("serviceC"));
        pipeline.addOrUpdateService(serviceB);

        // Service C forwards to Service A via gRPC, creating a loop
        PipeStepConfigurationDto serviceC = createServiceWithGrpc("serviceC", null, null, Collections.singletonList("serviceA"));

        // Verify that a loop is detected
        assertTrue(PipelineValidator.hasLoop(pipeline, serviceC));

        // Verify that adding the service throws an exception
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            pipeline.addOrUpdateService(serviceC);
        });

        assertTrue(exception.getMessage().contains("would create a loop"));
    }

    /**
     * Tests that dependent services are correctly identified.
     */
    @Test
    public void testGetDependentServices() {
        // Create a pipeline with dependencies
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", null, Collections.singletonList("topic1"));
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"), Collections.singletonList("topic2"));
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        // Service D forwards to Service E via gRPC
        PipeStepConfigurationDto serviceD = createServiceWithGrpc("serviceD", null, null, Collections.singletonList("serviceE"));
        pipeline.addOrUpdateService(serviceD);

        // Service E
        PipeStepConfigurationDto serviceE = createService("serviceE", null, null);
        pipeline.addOrUpdateService(serviceE);

        // Verify that Service B depends on Service A
        Set<String> dependentsOfA = PipelineValidator.getDependentServices(pipeline, "serviceA");
        assertEquals(1, dependentsOfA.size());
        assertTrue(dependentsOfA.contains("serviceB"));

        // Verify that Service C depends on Service B
        Set<String> dependentsOfB = PipelineValidator.getDependentServices(pipeline, "serviceB");
        assertEquals(1, dependentsOfB.size());
        assertTrue(dependentsOfB.contains("serviceC"));

        // Verify that no services depend on Service C
        Set<String> dependentsOfC = PipelineValidator.getDependentServices(pipeline, "serviceC");
        assertTrue(dependentsOfC.isEmpty());

        // Verify that Service E depends on Service D via gRPC
        Set<String> dependentsOfD = PipelineValidator.getDependentServices(pipeline, "serviceD");
        assertEquals(1, dependentsOfD.size());
        assertTrue(dependentsOfD.contains("serviceE"));
    }

    /**
     * Tests that removing a service with dependents removes all dependent services.
     */
    @Test
    public void testRemoveServiceWithDependents() {
        // Create a pipeline with dependencies
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", null, Collections.singletonList("topic1"));
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"), Collections.singletonList("topic2"));
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        // Verify that all services are in the pipeline
        assertEquals(3, pipeline.getServices().size());
        assertTrue(pipeline.containsService("serviceA"));
        assertTrue(pipeline.containsService("serviceB"));
        assertTrue(pipeline.containsService("serviceC"));

        // Remove Service A with dependents
        Set<String> removedServices = pipeline.removeServiceWithDependents("serviceA");

        // Verify that all services were removed
        assertEquals(3, removedServices.size());
        assertTrue(removedServices.contains("serviceA"));
        assertTrue(removedServices.contains("serviceB"));
        assertTrue(removedServices.contains("serviceC"));

        // Verify that the pipeline is empty
        assertTrue(pipeline.getServices().isEmpty());
    }

    /**
     * Tests that removing a service with no dependents only removes that service.
     */
    @Test
    public void testRemoveServiceWithNoDependents() {
        // Create a pipeline with dependencies
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", null, Collections.singletonList("topic1"));
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"), Collections.singletonList("topic2"));
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        // Verify that all services are in the pipeline
        assertEquals(3, pipeline.getServices().size());
        assertTrue(pipeline.containsService("serviceA"));
        assertTrue(pipeline.containsService("serviceB"));
        assertTrue(pipeline.containsService("serviceC"));

        // Remove Service C with dependents
        Set<String> removedServices = pipeline.removeServiceWithDependents("serviceC");

        // Verify that only Service C was removed
        assertEquals(1, removedServices.size());
        assertTrue(removedServices.contains("serviceC"));

        // Verify that Service A and B are still in the pipeline
        assertEquals(2, pipeline.getServices().size());
        assertTrue(pipeline.containsService("serviceA"));
        assertTrue(pipeline.containsService("serviceB"));
        assertFalse(pipeline.containsService("serviceC"));
    }

    /**
     * Helper method to create a service configuration.
     */
    private PipeStepConfigurationDto createService(String name,
                                                   java.util.List<String> listenTopics,
                                                   java.util.List<String> publishTopics) {
        PipeStepConfigurationDto service = new PipeStepConfigurationDto();
        service.setName(name);
        service.setKafkaListenTopics(listenTopics);
        service.setKafkaPublishTopics(publishTopics);
        service.setConfigParams(new HashMap<String,String>());
        return service;
    }

    /**
     * Helper method to create a service configuration with gRPC forwarding.
     */
    private PipeStepConfigurationDto createServiceWithGrpc(String name,
                                                           java.util.List<String> listenTopics,
                                                           java.util.List<String> publishTopics,
                                                           java.util.List<String> grpcForwardTo) {
        PipeStepConfigurationDto service = createService(name, listenTopics, publishTopics);
        service.setGrpcForwardTo(grpcForwardTo);
        return service;
    }
}
