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
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("test-cluster");

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
            "step1A", "test-module", null, null, null, null
        );
        steps1.put("step1A", step1A);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: No Kafka interactions
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig step2A = new PipelineStepConfig(
            "step2A", "test-module", null, null, null, null
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
            "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null
        );
        stepsA.put("stepA", stepA);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, List.of("topicX"), null, null
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
            "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
            "stepA2", "test-module", null, List.of("topicY"), null, null
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX and publishes to TopicY
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
            "stepB1", "test-module", null, List.of("topicX"), null, null
        );
        PipelineStepConfig stepB2 = new PipelineStepConfig(
            "stepB2", "test-module", null, null, List.of(new KafkaPublishTopic("topicY")), null
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

    @Test
    void validate_threePipelineLoop_returnsError() {
        // Create a longer loop: PipelineA -> T1 -> PipelineB -> T2 -> PipelineC -> T3 -> PipelineA
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline A: Publishes to T1 and listens to T3
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA1 = new PipelineStepConfig(
            "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("T1")), null
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
            "stepA2", "test-module", null, List.of("T3"), null, null
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to T1 and publishes to T2
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
            "stepB1", "test-module", null, List.of("T1"), null, null
        );
        PipelineStepConfig stepB2 = new PipelineStepConfig(
            "stepB2", "test-module", null, null, List.of(new KafkaPublishTopic("T2")), null
        );
        stepsB.put("stepB1", stepB1);
        stepsB.put("stepB2", stepB2);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        // Pipeline C: Listens to T2 and publishes to T3
        Map<String, PipelineStepConfig> stepsC = new HashMap<>();
        PipelineStepConfig stepC1 = new PipelineStepConfig(
            "stepC1", "test-module", null, List.of("T2"), null, null
        );
        PipelineStepConfig stepC2 = new PipelineStepConfig(
            "stepC2", "test-module", null, null, List.of(new KafkaPublishTopic("T3")), null
        );
        stepsC.put("stepC1", stepC1);
        stepsC.put("stepC2", stepC2);
        pipelines.put("pipelineC", new PipelineConfig("pipelineC", stepsC));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        assertTrue(errors.getFirst().contains("Inter-pipeline loop detected"), 
                "Error should indicate an inter-pipeline loop was detected");
        assertTrue(errors.getFirst().contains("pipelineA") && 
                   errors.getFirst().contains("pipelineB") && 
                   errors.getFirst().contains("pipelineC"), 
                "Error should mention all three pipelines involved in the loop");
    }

    @Test
    void validate_selfLoop_returnsError() {
        // Create a self-loop: PipelineA publishes to TopicX and also listens to TopicX
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline A: Both publishes to and listens to TopicX
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA1 = new PipelineStepConfig(
            "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
            "stepA2", "test-module", null, List.of("topicX"), null, null
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one self-loop");
        assertTrue(errors.getFirst().contains("Inter-pipeline loop detected"), 
                "Error should indicate an inter-pipeline loop was detected");
        assertTrue(errors.getFirst().contains("pipelineA"), 
                "Error should mention the pipeline involved in the self-loop");
    }

    @Test
    void validate_multipleIndependentSets_someWithLoops_returnsErrors() {
        // Create multiple independent sets of pipelines, some with loops, some without
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Set 1: Linear flow (no loop)
        // Pipeline A1 -> TopicX1 -> Pipeline B1
        Map<String, PipelineStepConfig> stepsA1 = new HashMap<>();
        PipelineStepConfig stepA1 = new PipelineStepConfig(
            "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX1")), null
        );
        stepsA1.put("stepA1", stepA1);
        pipelines.put("pipelineA1", new PipelineConfig("pipelineA1", stepsA1));

        Map<String, PipelineStepConfig> stepsB1 = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
            "stepB1", "test-module", null, List.of("topicX1"), null, null
        );
        stepsB1.put("stepB1", stepB1);
        pipelines.put("pipelineB1", new PipelineConfig("pipelineB1", stepsB1));

        // Set 2: With a loop
        // Pipeline A2 -> TopicX2 -> Pipeline B2 -> TopicY2 -> Pipeline A2
        Map<String, PipelineStepConfig> stepsA2 = new HashMap<>();
        PipelineStepConfig stepA2_1 = new PipelineStepConfig(
            "stepA2_1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX2")), null
        );
        PipelineStepConfig stepA2_2 = new PipelineStepConfig(
            "stepA2_2", "test-module", null, List.of("topicY2"), null, null
        );
        stepsA2.put("stepA2_1", stepA2_1);
        stepsA2.put("stepA2_2", stepA2_2);
        pipelines.put("pipelineA2", new PipelineConfig("pipelineA2", stepsA2));

        Map<String, PipelineStepConfig> stepsB2 = new HashMap<>();
        PipelineStepConfig stepB2_1 = new PipelineStepConfig(
            "stepB2_1", "test-module", null, List.of("topicX2"), null, null
        );
        PipelineStepConfig stepB2_2 = new PipelineStepConfig(
            "stepB2_2", "test-module", null, null, List.of(new KafkaPublishTopic("topicY2")), null
        );
        stepsB2.put("stepB2_1", stepB2_1);
        stepsB2.put("stepB2_2", stepB2_2);
        pipelines.put("pipelineB2", new PipelineConfig("pipelineB2", stepsB2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop in set 2");
        assertTrue(errors.getFirst().contains("Inter-pipeline loop detected"), 
                "Error should indicate an inter-pipeline loop was detected");
        assertTrue(errors.getFirst().contains("pipelineA2") && errors.getFirst().contains("pipelineB2"), 
                "Error should mention the pipelines involved in the loop");
    }

    @Test
    void validate_pipelinePublishesToManyTopics_onlyOneCreatesLoop_returnsError() {
        // Create a scenario where a pipeline publishes to many topics, but only one creates a loop
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline A: Publishes to many topics (topic1, topic2, topic3) and listens to topicLoop
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA1 = new PipelineStepConfig(
            "stepA1", "test-module", null, null, 
            List.of(
                new KafkaPublishTopic("topic1"),
                new KafkaPublishTopic("topic2"),
                new KafkaPublishTopic("topic3")
            ), 
            null
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
            "stepA2", "test-module", null, List.of("topicLoop"), null, null
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to topic1 (no loop)
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, List.of("topic1"), null, null
        );
        stepsB.put("stepB", stepB);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        // Pipeline C: Listens to topic2 and publishes to topicLoop (creates loop with Pipeline A)
        Map<String, PipelineStepConfig> stepsC = new HashMap<>();
        PipelineStepConfig stepC1 = new PipelineStepConfig(
            "stepC1", "test-module", null, List.of("topic2"), null, null
        );
        PipelineStepConfig stepC2 = new PipelineStepConfig(
            "stepC2", "test-module", null, null, List.of(new KafkaPublishTopic("topicLoop")), null
        );
        stepsC.put("stepC1", stepC1);
        stepsC.put("stepC2", stepC2);
        pipelines.put("pipelineC", new PipelineConfig("pipelineC", stepsC));

        // Pipeline D: Listens to topic3 (no loop)
        Map<String, PipelineStepConfig> stepsD = new HashMap<>();
        PipelineStepConfig stepD = new PipelineStepConfig(
            "stepD", "test-module", null, List.of("topic3"), null, null
        );
        stepsD.put("stepD", stepD);
        pipelines.put("pipelineD", new PipelineConfig("pipelineD", stepsD));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop between Pipeline A and Pipeline C");
        assertTrue(errors.getFirst().contains("Inter-pipeline loop detected"), 
                "Error should indicate an inter-pipeline loop was detected");
        assertTrue(errors.getFirst().contains("pipelineA") && errors.getFirst().contains("pipelineC"), 
                "Error should mention the pipelines involved in the loop");
    }

    @Test
    void validate_invalidPipelineNames_handledGracefully() {
        // Create a configuration with invalid pipeline names to test error handling
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Valid pipeline
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null
        );
        stepsA.put("stepA", stepA);
        pipelines.put("validPipeline", new PipelineConfig("validPipeline", stepsA));

        // Add a blank key
        pipelines.put("", new PipelineConfig("emptyPipeline", new HashMap<>()));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        // The validator should handle this gracefully without throwing exceptions
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        // No loops, so no errors expected
        assertTrue(errors.isEmpty(), "Invalid pipeline names should be handled gracefully");
    }
}
