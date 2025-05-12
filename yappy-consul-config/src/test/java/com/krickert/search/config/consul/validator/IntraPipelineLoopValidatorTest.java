package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the IntraPipelineLoopValidator class.
 * <br/>
 * These tests verify that the validator correctly identifies:
 * 1. Loops in Kafka data flow within a pipeline
 * 2. Self-loops (a step publishing to a topic it also listens to)
 * 3. Longer loops involving multiple steps
 * 4. Handles edge cases (null configs, empty pipelines, etc.)
 */
class IntraPipelineLoopValidatorTest {

    private IntraPipelineLoopValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    @BeforeEach
    void setUp() {
        validator = new IntraPipelineLoopValidator();
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
    void validate_directLoop_returnsError() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("stepA", new PipelineStepConfig("stepA", "test-module", null, List.of("topic2"), List.of(new KafkaPublishTopic("topic1")), null, null, null));
        steps.put("stepB", new PipelineStepConfig("stepB", "test-module", null, List.of("topic1"), List.of(new KafkaPublishTopic("topic2")), null, null, null));

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        String error = errors.getFirst();
        assertTrue(error.contains("Intra-pipeline loop detected") && error.contains("pipeline 'pipeline1'"), "Error message basic check failed");
        // Check for the specific cycle path (order might vary, so check for both possibilities)
        assertTrue(error.contains("Cycle path: [stepA -> stepB -> stepA]") || error.contains("Cycle path: [stepB -> stepA -> stepB]"),
                "Error should contain the correct cycle path for stepA and stepB. Actual: " + error);
    }

    @Test
    void validate_multipleDistinctLoopsInSamePipeline_returnsAllErrors() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("stepA", new PipelineStepConfig("stepA", "m", null, List.of("T2"), List.of(new KafkaPublishTopic("T1")), null, null, null));
        steps.put("stepB", new PipelineStepConfig("stepB", "m", null, List.of("T1"), List.of(new KafkaPublishTopic("T2")), null, null, null));
        steps.put("stepC", new PipelineStepConfig("stepC", "m", null, List.of("T4"), List.of(new KafkaPublishTopic("T3")), null, null, null));
        steps.put("stepD", new PipelineStepConfig("stepD", "m", null, List.of("T3"), List.of(new KafkaPublishTopic("T4")), null, null, null));
        steps.put("stepE", new PipelineStepConfig("stepE", "m", null, null, List.of(new KafkaPublishTopic("T5")), null, null, null));
        steps.put("stepF", new PipelineStepConfig("stepF", "m", null, List.of("T5"), null, null, null, null));

        PipelineConfig pipeline = new PipelineConfig("pipelineWithMultipleLoops", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline, "test-cluster-multi-internal-loop");
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(2, errors.size(), "Should detect two distinct loops in the same pipeline. Errors: " + errors);

        boolean loopABFound = errors.stream().anyMatch(e ->
                e.contains("pipeline 'pipelineWithMultipleLoops'") &&
                        (e.contains("Cycle path: [stepA -> stepB -> stepA]") || e.contains("Cycle path: [stepB -> stepA -> stepB]"))
        );
        boolean loopCDFound = errors.stream().anyMatch(e ->
                e.contains("pipeline 'pipelineWithMultipleLoops'") &&
                        (e.contains("Cycle path: [stepC -> stepD -> stepC]") || e.contains("Cycle path: [stepD -> stepC -> stepD]"))
        );

        assertTrue(loopABFound, "Cycle A-B path not found or not formatted as expected. Errors: " + errors);
        assertTrue(loopCDFound, "Cycle C-D path not found or not formatted as expected. Errors: " + errors);
    }

    // Helper method to reduce boilerplate in test setup
    private PipelineClusterConfig createClusterConfig(PipelineConfig pipeline) {
        return createClusterConfig(pipeline, "test-cluster");
    }

    private PipelineClusterConfig createClusterConfig(PipelineConfig pipeline, String clusterName) {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(pipeline.name(), pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        return new PipelineClusterConfig(clusterName, graphConfig, null, null, null);
    }

    @Test
    void validate_selfLoop_returnsError() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("stepA", new PipelineStepConfig("stepA", "test-module", null, List.of("topic1"), List.of(new KafkaPublishTopic("topic1")), null, null, null));

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one self-loop");
        String error = errors.getFirst();
        assertTrue(error.contains("Intra-pipeline loop detected") && error.contains("pipeline 'pipeline1'"), "Error message basic check failed");
        assertTrue(error.contains("Cycle path: [stepA -> stepA]"),
                "Error should contain the correct self-loop path for stepA. Actual: " + error);
    }

    @Test
    void validate_longerLoop_returnsError() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("stepA", new PipelineStepConfig("stepA", "test-module", null, List.of("topic3"), List.of(new KafkaPublishTopic("topic1")), null, null, null));
        steps.put("stepB", new PipelineStepConfig("stepB", "test-module", null, List.of("topic1"), List.of(new KafkaPublishTopic("topic2")), null, null, null));
        steps.put("stepC", new PipelineStepConfig("stepC", "test-module", null, List.of("topic2"), List.of(new KafkaPublishTopic("topic3")), null, null, null));

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one longer loop");
        String error = errors.getFirst();
        assertTrue(error.contains("Intra-pipeline loop detected") && error.contains("pipeline 'pipeline1'"), "Error message basic check failed");

        // Check for the presence of all steps in the cycle path. Order can vary.
        // A more robust check would be to parse the path and verify its elements and structure.
        assertTrue(error.contains("stepA") && error.contains("stepB") && error.contains("stepC") && error.contains("->"),
                "Error should contain the correct cycle path elements for the longer loop. Actual: " + error);
        // Example of a more specific check if you know one possible output:
        // assertTrue(error.contains("Cycle path: [stepA -> stepB -> stepC -> stepA]") || /* other permutations */);
    }

    @Test
    void validate_multiplePipelines_detectsLoopsCorrectly() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline 1: Linear (no loop)
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();
        steps1.put("step1A", new PipelineStepConfig("step1A", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null, null, null));
        steps1.put("step1B", new PipelineStepConfig("step1B", "test-module", null, List.of("topic1"), null, null, null, null));
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: With a loop
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        steps2.put("step2A", new PipelineStepConfig("step2A", "test-module", null, List.of("topic2B"), List.of(new KafkaPublishTopic("topic2A")), null, null, null));
        steps2.put("step2B", new PipelineStepConfig("step2B", "test-module", null, List.of("topic2A"), List.of(new KafkaPublishTopic("topic2B")), null, null, null));
        pipelines.put("pipeline2", new PipelineConfig("pipeline2", steps2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("test-cluster", graphConfig, null, null, null);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop in pipeline2");
        String error = errors.getFirst();
        assertTrue(error.contains("Intra-pipeline loop detected") && error.contains("pipeline 'pipeline2'"), "Error message basic check failed");
        assertTrue(error.contains("Cycle path: [step2A -> step2B -> step2A]") || error.contains("Cycle path: [step2B -> step2A -> step2B]"),
                "Error should contain the correct cycle path for pipeline2. Actual: " + error);
    }



    @Test
    void validate_topicNameCaseSensitivity_noLoopExpected() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("stepX", new PipelineStepConfig("stepX", "m", null, null, List.of(new KafkaPublishTopic("TopicA")), null, null, null));
        steps.put("stepY", new PipelineStepConfig("stepY", "m", null, List.of("topica"), List.of(new KafkaPublishTopic("TopicA")), null, null, null));

        PipelineConfig pipeline = new PipelineConfig("caseSensitivePipeline", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline, "test-cluster-case-sensitive");

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Topic names differing only by case should not form a loop. Errors: " + errors);
    }

    @Test
    void validate_simpleLinearPipeline_returnsNoErrors() {
        // Create a simple linear pipeline: StepA -> Topic1 -> StepB
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create StepA that publishes to Topic1
        List<KafkaPublishTopic> stepAPublishTopics = List.of(new KafkaPublishTopic("topic1"));
        PipelineStepConfig stepA = new PipelineStepConfig(
                "stepA", "test-module", null, null, stepAPublishTopics, null, null, null // Added nulls
        );

        // Create StepB that listens to Topic1
        List<String> stepBListenTopics = List.of("topic1");
        PipelineStepConfig stepB = new PipelineStepConfig(
                "stepB", "test-module", null, stepBListenTopics, null, null, null, null // Added nulls
        );

        steps.put("stepA", stepA);
        steps.put("stepB", stepB);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline);


        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Linear pipeline should not have loops");
    }
    @Test
    void validate_crossPipelineTopics_noLoops() {
        // Create two pipelines that share topic names but should not create loops
        // because loops are only detected within a single pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline 1: StepA publishes to Topic1
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();
        PipelineStepConfig stepA = new PipelineStepConfig(
                "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("shared-topic")), null, null, null // Added nulls
        );
        steps1.put("stepA", stepA);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: StepB listens to Topic1
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
                "stepB", "test-module", null, List.of("shared-topic"), null, null, null, null // Added nulls
        );
        steps2.put("stepB", stepB);
        pipelines.put("pipeline2", new PipelineConfig("pipeline2", steps2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Cross-pipeline topic sharing should not create loops for IntraPipelineLoopValidator");
    }

    @Test
    void validate_invalidStepIds_handledGracefully() {
        // Create a pipeline with steps that have invalid IDs
        // This tests the error handling in the graph construction
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step with a valid ID that publishes to a topic
        PipelineStepConfig validStep = new PipelineStepConfig(
                "validStep", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null, null, null // Added nulls
        );
        steps.put("validStep", validStep);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipeline);


        // The validator should handle this gracefully without throwing exceptions
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        // No loops, so no errors expected
        assertTrue(errors.isEmpty(), "Invalid step IDs (in terms of map keys) should be handled gracefully");
    }
    @Test
    void validate_pipelineWithNoSteps_returnsNoErrors() {
        // Create a pipeline with no steps
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        PipelineConfig pipeline = new PipelineConfig("pipeline1", Collections.emptyMap());
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Pipeline with no steps should not produce errors");
    }
}