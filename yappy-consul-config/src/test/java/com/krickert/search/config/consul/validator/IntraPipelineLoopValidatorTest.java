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

    @Test
    void validate_simpleLinearPipeline_returnsNoErrors() {
        // Create a simple linear pipeline: StepA -> Topic1 -> StepB
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create StepA that publishes to Topic1
        List<KafkaPublishTopic> stepAPublishTopics = List.of(new KafkaPublishTopic("topic1"));
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, null, stepAPublishTopics, null
        );

        // Create StepB that listens to Topic1
        List<String> stepBListenTopics = List.of("topic1");
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, stepBListenTopics, null, null
        );

        steps.put("stepA", stepA);
        steps.put("stepB", stepB);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Linear pipeline should not have loops");
    }

    @Test
    void validate_directLoop_returnsError() {
        // Create a pipeline with a direct loop: StepA -> Topic1 -> StepB -> Topic2 -> StepA
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create StepA that publishes to Topic1 and listens to Topic2
        List<KafkaPublishTopic> stepAPublishTopics = List.of(new KafkaPublishTopic("topic1"));
        List<String> stepAListenTopics = List.of("topic2");
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, stepAListenTopics, stepAPublishTopics, null
        );

        // Create StepB that listens to Topic1 and publishes to Topic2
        List<String> stepBListenTopics = List.of("topic1");
        List<KafkaPublishTopic> stepBPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, stepBListenTopics, stepBPublishTopics, null
        );

        steps.put("stepA", stepA);
        steps.put("stepB", stepB);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        assertTrue(errors.getFirst().contains("Loop detected"), 
                "Error should indicate a loop was detected");
        assertTrue(errors.getFirst().contains("pipeline1"), 
                "Error should mention the pipeline name");
    }

    @Test
    void validate_selfLoop_returnsError() {
        // Create a pipeline with a self-loop: StepA publishes to Topic1 and also listens to Topic1
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create StepA that both publishes to and listens to Topic1
        List<KafkaPublishTopic> stepAPublishTopics = List.of(new KafkaPublishTopic("topic1"));
        List<String> stepAListenTopics = List.of("topic1");
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, stepAListenTopics, stepAPublishTopics, null
        );

        steps.put("stepA", stepA);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        assertTrue(errors.getFirst().contains("Loop detected"), 
                "Error should indicate a loop was detected");
        assertTrue(errors.getFirst().contains("pipeline1"), 
                "Error should mention the pipeline name");
    }

    @Test
    void validate_longerLoop_returnsError() {
        // Create a pipeline with a longer loop: StepA -> Topic1 -> StepB -> Topic2 -> StepC -> Topic3 -> StepA
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create StepA that publishes to Topic1 and listens to Topic3
        List<KafkaPublishTopic> stepAPublishTopics = List.of(new KafkaPublishTopic("topic1"));
        List<String> stepAListenTopics = List.of("topic3");
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, stepAListenTopics, stepAPublishTopics, null
        );

        // Create StepB that listens to Topic1 and publishes to Topic2
        List<String> stepBListenTopics = List.of("topic1");
        List<KafkaPublishTopic> stepBPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, stepBListenTopics, stepBPublishTopics, null
        );

        // Create StepC that listens to Topic2 and publishes to Topic3
        List<String> stepCListenTopics = List.of("topic2");
        List<KafkaPublishTopic> stepCPublishTopics = List.of(new KafkaPublishTopic("topic3"));
        PipelineStepConfig stepC = new PipelineStepConfig(
            "stepC", "test-module", null, stepCListenTopics, stepCPublishTopics, null
        );

        steps.put("stepA", stepA);
        steps.put("stepB", stepB);
        steps.put("stepC", stepC);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop");
        assertTrue(errors.getFirst().contains("Loop detected"), 
                "Error should indicate a loop was detected");
        assertTrue(errors.getFirst().contains("pipeline1"), 
                "Error should mention the pipeline name");
    }

    @Test
    void validate_multiplePipelines_detectsLoopsCorrectly() {
        // Create multiple pipelines, some with loops, some without
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline 1: Linear pipeline (no loops)
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();
        PipelineStepConfig step1A = new PipelineStepConfig(
            "step1A", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null
        );
        PipelineStepConfig step1B = new PipelineStepConfig(
            "step1B", "test-module", null, List.of("topic1"), null, null
        );
        steps1.put("step1A", step1A);
        steps1.put("step1B", step1B);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: With a loop
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig step2A = new PipelineStepConfig(
            "step2A", "test-module", null, List.of("topic2B"), List.of(new KafkaPublishTopic("topic2A")), null
        );
        PipelineStepConfig step2B = new PipelineStepConfig(
            "step2B", "test-module", null, List.of("topic2A"), List.of(new KafkaPublishTopic("topic2B")), null
        );
        steps2.put("step2A", step2A);
        steps2.put("step2B", step2B);
        pipelines.put("pipeline2", new PipelineConfig("pipeline2", steps2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(1, errors.size(), "Should detect one loop in pipeline2");
        assertTrue(errors.getFirst().contains("Loop detected"), 
                "Error should indicate a loop was detected");
        assertTrue(errors.getFirst().contains("pipeline2"), 
                "Error should mention the pipeline with the loop");
    }

    @Test
    void validate_crossPipelineTopics_noLoops() {
        // Create two pipelines that share topic names but should not create loops
        // because loops are only detected within a single pipeline
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Pipeline 1: StepA publishes to Topic1
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();
        PipelineStepConfig stepA = new PipelineStepConfig(
            "stepA", "test-module", null, null, List.of(new KafkaPublishTopic("shared-topic")), null
        );
        steps1.put("stepA", stepA);
        pipelines.put("pipeline1", new PipelineConfig("pipeline1", steps1));

        // Pipeline 2: StepB listens to Topic1
        Map<String, PipelineStepConfig> steps2 = new HashMap<>();
        PipelineStepConfig stepB = new PipelineStepConfig(
            "stepB", "test-module", null, List.of("shared-topic"), null, null
        );
        steps2.put("stepB", stepB);
        pipelines.put("pipeline2", new PipelineConfig("pipeline2", steps2));

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Cross-pipeline topic sharing should not create loops");
    }

    @Test
    void validate_invalidStepIds_handledGracefully() {
        // Create a pipeline with steps that have invalid IDs
        // This tests the error handling in the graph construction
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step with a valid ID that publishes to a topic
        PipelineStepConfig validStep = new PipelineStepConfig(
            "validStep", "test-module", null, null, List.of(new KafkaPublishTopic("topic1")), null
        );
        steps.put("validStep", validStep);

        // Create a step with a null ID (this shouldn't happen in practice due to record validation)
        // but we'll test the validator's robustness
        // Note: We can't directly create a step with a null ID due to record validation,
        // so this is more of a theoretical test

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, null, null, null
        );

        // The validator should handle this gracefully without throwing exceptions
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        // No loops, so no errors expected
        assertTrue(errors.isEmpty(), "Invalid step IDs should be handled gracefully");
    }
}