package com.krickert.search.config.consul.validation;

import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List; // Import List
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
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1 (with dummy target step)
        PipeStepConfigurationDto serviceA = createService("serviceA", null,
                Collections.singletonList(new KafkaRouteTarget("topic1", "dummyTargetStepA"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"),
                Collections.singletonList(new KafkaRouteTarget("topic2", "dummyTargetStepC"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        assertFalse(PipelineValidator.hasLoop(pipeline, serviceC));
    }

    /**
     * Tests that a pipeline with a loop is detected.
     */
    @Test
    public void testLoop() {
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A listens to topic3, publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", Collections.singletonList("topic3"),
                Collections.singletonList(new KafkaRouteTarget("topic1", "serviceB"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1, publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"),
                Collections.singletonList(new KafkaRouteTarget("topic2", "serviceC"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2, publishes to topic3 (creating loop)
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"),
                Collections.singletonList(new KafkaRouteTarget("topic3", "serviceA"))); // <<< CHANGE HERE

        assertTrue(PipelineValidator.hasLoop(pipeline, serviceC));
        Exception exception = assertThrows(IllegalArgumentException.class, () -> pipeline.addOrUpdateService(serviceC));
        assertTrue(exception.getMessage().contains("would create a loop"));
    }

    /**
     * Tests that a loop is detected when using gRPC forwarding.
     */
    @Test
    public void testLoopWithGrpc() {
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A forwards to Service B via gRPC (publishTopics is null here)
        PipeStepConfigurationDto serviceA = createServiceWithGrpc("serviceA", null, null, Collections.singletonList("serviceB"));
        pipeline.addOrUpdateService(serviceA);

        // Service B forwards to Service C via gRPC
        PipeStepConfigurationDto serviceB = createServiceWithGrpc("serviceB", null, null, Collections.singletonList("serviceC"));
        pipeline.addOrUpdateService(serviceB);

        // Service C forwards to Service A via gRPC, creating a loop
        PipeStepConfigurationDto serviceC = createServiceWithGrpc("serviceC", null, null, Collections.singletonList("serviceA"));

        assertTrue(PipelineValidator.hasLoop(pipeline, serviceC));
        Exception exception = assertThrows(IllegalArgumentException.class, () -> pipeline.addOrUpdateService(serviceC));
        assertTrue(exception.getMessage().contains("would create a loop"));
    }

    /**
     * Tests that dependent services are correctly identified.
     */
    @Test
    public void testGetDependentServices() {
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1
        PipeStepConfigurationDto serviceA = createService("serviceA", null,
                Collections.singletonList(new KafkaRouteTarget("topic1", "serviceB"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1 and publishes to topic2
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"),
                Collections.singletonList(new KafkaRouteTarget("topic2", "serviceC"))); // <<< CHANGE HERE
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

        // Verify dependencies
        Set<String> dependentsOfA = PipelineValidator.getDependentServices(pipeline, "serviceA");
        assertEquals(Collections.singleton("serviceB"), dependentsOfA);

        Set<String> dependentsOfB = PipelineValidator.getDependentServices(pipeline, "serviceB");
        assertEquals(Collections.singleton("serviceC"), dependentsOfB);

        Set<String> dependentsOfC = PipelineValidator.getDependentServices(pipeline, "serviceC");
        assertTrue(dependentsOfC.isEmpty());

        Set<String> dependentsOfD = PipelineValidator.getDependentServices(pipeline, "serviceD");
        assertEquals(Collections.singleton("serviceE"), dependentsOfD);
    }

    /**
     * Tests that removing a service with dependents removes all dependent services.
     */
    @Test
    public void testRemoveServiceWithDependents() {
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1 -> targets serviceB
        PipeStepConfigurationDto serviceA = createService("serviceA", null,
                Collections.singletonList(new KafkaRouteTarget("topic1", "serviceB"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1, publishes to topic2 -> targets serviceC
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"),
                Collections.singletonList(new KafkaRouteTarget("topic2", "serviceC"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        assertEquals(3, pipeline.getServices().size());

        // Remove Service A with dependents
        Set<String> removedServices = pipeline.removeServiceWithDependents("serviceA");

        assertEquals(Set.of("serviceA", "serviceB", "serviceC"), removedServices);
        assertTrue(pipeline.getServices().isEmpty());
    }

    /**
     * Tests that removing a service with no dependents only removes that service.
     */
    @Test
    public void testRemoveServiceWithNoDependents() {
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");

        // Service A publishes to topic1 -> targets serviceB
        PipeStepConfigurationDto serviceA = createService("serviceA", null,
                Collections.singletonList(new KafkaRouteTarget("topic1", "serviceB"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceA);

        // Service B listens to topic1, publishes to topic2 -> targets serviceC
        PipeStepConfigurationDto serviceB = createService("serviceB", Collections.singletonList("topic1"),
                Collections.singletonList(new KafkaRouteTarget("topic2", "serviceC"))); // <<< CHANGE HERE
        pipeline.addOrUpdateService(serviceB);

        // Service C listens to topic2
        PipeStepConfigurationDto serviceC = createService("serviceC", Collections.singletonList("topic2"), null);
        pipeline.addOrUpdateService(serviceC);

        assertEquals(3, pipeline.getServices().size());

        // Remove Service C (no dependents)
        Set<String> removedServices = pipeline.removeServiceWithDependents("serviceC");

        assertEquals(Collections.singleton("serviceC"), removedServices);
        assertEquals(2, pipeline.getServices().size());
        assertTrue(pipeline.containsService("serviceA"));
        assertTrue(pipeline.containsService("serviceB"));
        assertFalse(pipeline.containsService("serviceC"));
    }

    /**
     * Helper method to create a service configuration.
     * NOW accepts List<KafkaRouteTarget> for publishTopics.
     */
    // ---vvv CHANGE PARAMETER TYPE HERE vvv---
    private PipeStepConfigurationDto createService(String name,
                                                   List<String> listenTopics,
                                                   List<KafkaRouteTarget> publishTopics) {
        // ---^^^ CHANGE PARAMETER TYPE HERE ^^^---
        PipeStepConfigurationDto service = new PipeStepConfigurationDto();
        service.setName(name);
        service.setKafkaListenTopics(listenTopics);
        service.setKafkaPublishTopics(publishTopics); // Assign the correct type
        service.setConfigParams(new HashMap<String,String>());
        return service;
    }

    /**
     * Helper method to create a service configuration with gRPC forwarding.
     * Needs adaptation if publishTopics are needed simultaneously.
     */
    // ---vvv CHANGE PARAMETER TYPE HERE vvv---
    private PipeStepConfigurationDto createServiceWithGrpc(String name,
                                                           List<String> listenTopics,
                                                           List<KafkaRouteTarget> publishTopics, // Changed type
                                                           List<String> grpcForwardTo) {
        // ---^^^ CHANGE PARAMETER TYPE HERE ^^^---
        // Calls the updated createService method
        PipeStepConfigurationDto service = createService(name, listenTopics, publishTopics);
        service.setGrpcForwardTo(grpcForwardTo);
        return service;
    }
}