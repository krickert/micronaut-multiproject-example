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
                "step1A", "test-module", null, null, null, null, null, null // Added nulls
        );
        steps1.put("step1A", step1A);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: No Kafka interactions
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig step2A = new PipelineStepConfig(
                "step2A", "test-module", null, null, null, null, null, null // Added nulls
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
                "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null, null, null // Added nulls
        );
        stepsA.put("stepA", stepA);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
                "stepB", "test-module", null, List.of("topicX"), null, null, null, null // Added nulls
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
                "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null, null, null // Added nulls
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
                "stepA2", "test-module", null, List.of("topicY"), null, null, null, null // Added nulls
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to TopicX and publishes to TopicY
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
                "stepB1", "test-module", null, List.of("topicX"), null, null, null, null // Added nulls
        );
        PipelineStepConfig stepB2 = new PipelineStepConfig(
                "stepB2", "test-module", null, null, List.of(new KafkaPublishTopic("topicY")), null, null, null // Added nulls
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
                "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("T1")), null, null, null // Added nulls
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
                "stepA2", "test-module", null, List.of("T3"), null, null, null, null // Added nulls
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to T1 and publishes to T2
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB1 = new PipelineStepConfig(
                "stepB1", "test-module", null, List.of("T1"), null, null, null, null // Added nulls
        );
        PipelineStepConfig stepB2 = new PipelineStepConfig(
                "stepB2", "test-module", null, null, List.of(new KafkaPublishTopic("T2")), null, null, null // Added nulls
        );
        stepsB.put("stepB1", stepB1);
        stepsB.put("stepB2", stepB2);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        // Pipeline C: Listens to T2 and publishes to T3
        Map<String, PipelineStepConfig> stepsC = new HashMap<>();
        PipelineStepConfig stepC1 = new PipelineStepConfig(
                "stepC1", "test-module", null, List.of("T2"), null, null, null, null // Added nulls
        );
        PipelineStepConfig stepC2 = new PipelineStepConfig(
                "stepC2", "test-module", null, null, List.of(new KafkaPublishTopic("T3")), null, null, null // Added nulls
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
                "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX")), null, null, null // Added nulls
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
                "stepA2", "test-module", null, List.of("topicX"), null, null, null, null // Added nulls
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
        PipelineStepConfig stepA1_config = new PipelineStepConfig( // Renamed to avoid conflict
                "stepA1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX1")), null, null, null // Added nulls
        );
        stepsA1.put("stepA1", stepA1_config);
        pipelines.put("pipelineA1", new PipelineConfig("pipelineA1", stepsA1));

        Map<String, PipelineStepConfig> stepsB1 = new HashMap<>();
        PipelineStepConfig stepB1_config = new PipelineStepConfig( // Renamed to avoid conflict
                "stepB1", "test-module", null, List.of("topicX1"), null, null, null, null // Added nulls
        );
        stepsB1.put("stepB1", stepB1_config);
        pipelines.put("pipelineB1", new PipelineConfig("pipelineB1", stepsB1));

        // Set 2: With a loop
        // Pipeline A2 -> TopicX2 -> Pipeline B2 -> TopicY2 -> Pipeline A2
        Map<String, PipelineStepConfig> stepsA2 = new HashMap<>();
        PipelineStepConfig stepA2_1 = new PipelineStepConfig(
                "stepA2_1", "test-module", null, null, List.of(new KafkaPublishTopic("topicX2")), null, null, null // Added nulls
        );
        PipelineStepConfig stepA2_2 = new PipelineStepConfig(
                "stepA2_2", "test-module", null, List.of("topicY2"), null, null, null, null // Added nulls
        );
        stepsA2.put("stepA2_1", stepA2_1);
        stepsA2.put("stepA2_2", stepA2_2);
        pipelines.put("pipelineA2", new PipelineConfig("pipelineA2", stepsA2));

        Map<String, PipelineStepConfig> stepsB2 = new HashMap<>();
        PipelineStepConfig stepB2_1 = new PipelineStepConfig(
                "stepB2_1", "test-module", null, List.of("topicX2"), null, null, null, null // Added nulls
        );
        PipelineStepConfig stepB2_2 = new PipelineStepConfig(
                "stepB2_2", "test-module", null, null, List.of(new KafkaPublishTopic("topicY2")), null, null, null // Added nulls
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
                null, null, null // Added nulls
        );
        PipelineStepConfig stepA2 = new PipelineStepConfig(
                "stepA2", "test-module", null, List.of("topicLoop"), null, null, null, null // Added nulls
        );
        stepsA.put("stepA1", stepA1);
        stepsA.put("stepA2", stepA2);
        pipelines.put("pipelineA", new PipelineConfig("pipelineA", stepsA));

        // Pipeline B: Listens to topic1 (no loop)
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        PipelineStepConfig stepB_config = new PipelineStepConfig( // Renamed to avoid conflict
                "stepB", "test-module", null, List.of("topic1"), null, null, null, null // Added nulls
        );
        stepsB.put("stepB", stepB_config);
        pipelines.put("pipelineB", new PipelineConfig("pipelineB", stepsB));

        // Pipeline C: Listens to topic2 and publishes to topicLoop (creates loop with Pipeline A)
        Map<String, PipelineStepConfig> stepsC = new HashMap<>();
        PipelineStepConfig stepC1 = new PipelineStepConfig(
                "stepC1", "test-module", null, List.of("topic2"), null, null, null, null // Added nulls
        );
        PipelineStepConfig stepC2 = new PipelineStepConfig(
                "stepC2", "test-module", null, null, List.of(new KafkaPublishTopic("topicLoop")), null, null, null // Added nulls
        );
        stepsC.put("stepC1", stepC1);
        stepsC.put("stepC2", stepC2);
        pipelines.put("pipelineC", new PipelineConfig("pipelineC", stepsC));

        // Pipeline D: Listens to topic3 (no loop)
        Map<String, PipelineStepConfig> stepsD = new HashMap<>();
        PipelineStepConfig stepD_config = new PipelineStepConfig( // Renamed to avoid conflict
                "stepD", "test-module", null, List.of("topic3"), null, null, null, null // Added nulls
        );
        stepsD.put("stepD", stepD_config);
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
                "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null, null, null // Added nulls
        );
        stepsA.put("stepA", stepA);
        pipelines.put("validPipeline", new PipelineConfig("validPipeline", stepsA));

        // Add a blank key
        // Note: The PipelineConfig constructor itself validates the name, so a blank name there would throw.
        // This test is more about how the InterPipelineLoopValidator handles keys in the pipelines map.
        // If PipelineConfig("emptyPipeline", ...) is valid, but the key in the outer map is blank:
        pipelines.put("", new PipelineConfig("pipelineWithBlankKeyInMap", new HashMap<>()));


        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        // The validator should handle this gracefully without throwing exceptions
        // and without incorrectly identifying loops due to map key issues.
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        // No loops, so no errors expected from this validator.
        // Other validators might flag the blank pipeline name if it's an issue for them.
        assertTrue(errors.isEmpty(), "Invalid pipeline names in the graph's map key should be handled gracefully by this validator");
    }

    @Test
    void validate_multipleDistinctLoops_returnsAllErrors() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Loop 1: P_A -> T1 -> P_B -> T2 -> P_A
        Map<String, PipelineStepConfig> stepsA = new HashMap<>();
        stepsA.put("sA1", new PipelineStepConfig("sA1", "m", null, null, List.of(new KafkaPublishTopic("T1")), null, null, null));
        stepsA.put("sA2", new PipelineStepConfig("sA2", "m", null, List.of("T2"), null, null, null, null));
        pipelines.put("P_A", new PipelineConfig("P_A", stepsA));

        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        stepsB.put("sB1", new PipelineStepConfig("sB1", "m", null, List.of("T1"), null, null, null, null));
        stepsB.put("sB2", new PipelineStepConfig("sB2", "m", null, null, List.of(new KafkaPublishTopic("T2")), null, null, null));
        pipelines.put("P_B", new PipelineConfig("P_B", stepsB));

        // Loop 2: P_C -> T3 -> P_D -> T4 -> P_C
        Map<String, PipelineStepConfig> stepsC = new HashMap<>();
        stepsC.put("sC1", new PipelineStepConfig("sC1", "m", null, null, List.of(new KafkaPublishTopic("T3")), null, null, null));
        stepsC.put("sC2", new PipelineStepConfig("sC2", "m", null, List.of("T4"), null, null, null, null));
        pipelines.put("P_C", new PipelineConfig("P_C", stepsC));

        Map<String, PipelineStepConfig> stepsD = new HashMap<>();
        stepsD.put("sD1", new PipelineStepConfig("sD1", "m", null, List.of("T3"), null, null, null, null));
        stepsD.put("sD2", new PipelineStepConfig("sD2", "m", null, null, List.of(new KafkaPublishTopic("T4")), null, null, null));
        pipelines.put("P_D", new PipelineConfig("P_D", stepsD));

        // Linear flow (no loop)
        Map<String, PipelineStepConfig> stepsE = new HashMap<>();
        stepsE.put("sE", new PipelineStepConfig("sE", "m", null, null, List.of(new KafkaPublishTopic("T5")), null, null, null));
        pipelines.put("P_E", new PipelineConfig("P_E", stepsE));
        Map<String, PipelineStepConfig> stepsF = new HashMap<>();
        stepsF.put("sF", new PipelineStepConfig("sF", "m", null, List.of("T5"), null, null, null, null));
        pipelines.put("P_F", new PipelineConfig("P_F", stepsF));


        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster-multi-loop", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(2, errors.size(), "Should detect two distinct loops");

        boolean loop1Detected = errors.stream().anyMatch(e -> e.contains("P_A") && e.contains("P_B"));
        boolean loop2Detected = errors.stream().anyMatch(e -> e.contains("P_C") && e.contains("P_D"));

        assertTrue(loop1Detected, "Loop between P_A and P_B should be detected");
        assertTrue(loop2Detected, "Loop between P_C and P_D should be detected");
    }

    @Test
    void validate_pipelineWithNoSteps_returnsNoErrors() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("emptyPipeline", new PipelineConfig("emptyPipeline", Collections.emptyMap())); // No steps

        // Add another pipeline that could form a loop if emptyPipeline participated
        Map<String, PipelineStepConfig> stepsB = new HashMap<>();
        stepsB.put("sB1", new PipelineStepConfig("sB1", "m", null, List.of("T1"), null, null, null, null));
        stepsB.put("sB2", new PipelineStepConfig("sB2", "m", null, null, List.of(new KafkaPublishTopic("T1")), null, null, null)); // Self-loop if T1 is used
        pipelines.put("P_B", new PipelineConfig("P_B", stepsB));


        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster-empty-steps", graphConfig, null, null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        // Expecting 1 error from P_B's self-loop, but none from emptyPipeline
        assertEquals(1, errors.size(), "Pipeline with no steps should not contribute to loops, but other loops should be found.");
        assertTrue(errors.getFirst().contains("P_B"));
    }
}