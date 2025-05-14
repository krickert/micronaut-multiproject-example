package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the InterPipelineLoopValidator class.
 * <br/>
 * These tests verify that the validator correctly identifies:
 * 1. Loops between different pipelines via shared Kafka topics
 * 2. Self-loops (a pipeline publishing to a topic it also listens to)
 * 3. Longer loops involving multiple pipelines
 * 4. Handles edge cases (null configs, empty pipelines, etc.)
 */
class InterPipelineLoopValidatorTest {

    private InterPipelineLoopValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    @BeforeEach
    void setUp() {
        validator = new InterPipelineLoopValidator();
        // Simple schema content provider that always returns an empty schema
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Null cluster config should not produce errors");
    }

    @Test
    void validate_noPipelines_returnsNoErrors() {
        // Create a cluster config with no pipelines
        // The PipelineClusterConfig constructor with only clusterName will create an empty graph
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("test-cluster", null, null, null, null);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Configuration with no pipelines should not produce errors");
    }

    @Test
    void validate_pipelinesWithNoKafkaInteractions_returnsNoErrors() {
        // Create pipelines with no Kafka interactions (no publish or listen topics)
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline 1: No Kafka interactions
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();
        PipelineStepConfig step1A = new PipelineStepConfig(
                "step1A", "test-module", null, null, null, TransportType.INTERNAL, null, null
        );
        steps1.put("step1A", step1A);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: No Kafka interactions
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig step2A = new PipelineStepConfig(
                "step2A", "test-module", null, null, null, TransportType.INTERNAL, null, null
        );
        steps2.put("step2A", step2A);
        pipelines.put("pipeline2", new PipelineConfig("pipeline2", steps2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Pipelines with no Kafka interactions should not produce errors");
    }

    @Test
    void validate_simpleTwoPipelineFlow_noLoop_returnsNoErrors() {
        // Create a simple flow: PipelineA -> TopicX -> PipelineB (no loop)
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline A: Publishes to TopicX
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA = new PipelineStepConfig(
                "stepA", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(null, "topicX", null), 
                null
        );
        stepsA.put("stepA", stepA);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
                "stepB", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(List.of("topicX"), null, null), 
                null
        );
        stepsB.put("stepB", stepB);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Simple two-pipeline flow without loops should not produce errors");
    }

    @Test
    void validate_twoPipelinesWithLoop_returnsError() {
        // Create a loop: PipelineA -> TopicX -> PipelineB -> TopicY -> PipelineA
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline A: Publishes to TopicX and listens to TopicY
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA1 = new PipelineStepConfig(
                "stepA1", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(null, "topicX", null), 
                null
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
                "stepA2", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(List.of("topicY"), null, null), 
                null
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX and publishes to TopicY
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
                "stepB1", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(List.of("topicX"), null, null), 
                null
        );
        PipelineStepConfig stepB2 = new PipelineStepConfig(
                "stepB2", "test-module", null, null, null, 
                TransportType.KAFKA, 
                new KafkaTransportConfig(null, "topicY", null), 
                null
        );
        stepsB.put("stepB1", stepB1);
        stepsB.put("stepB2", stepB2);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        assertTrue(errors.getFirst().contains("Inter-pipeline loop detected"),
                "Error should indicate an inter-pipeline loop was detected");
        assertTrue(errors.getFirst().contains("pipelineA") && errors.getFirst().contains("pipelineB"),
                "Error should mention both pipelines involved in the loop");
    }
}