package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StepTypeValidator class.
 * <br/>
 * These tests verify that the validator correctly identifies:
 * 1. INITIAL_PIPELINE steps that are referenced by other steps
 * 2. SINK steps that have next steps or error steps
 */
class StepTypeValidatorTest {

    private StepTypeValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    @BeforeEach
    void setUp() {
        validator = new StepTypeValidator();
        // Simple schema content provider that always returns an empty schema
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should result in no errors");
    }

    @Test
    void validate_initialPipelineStepReferencedByOtherStep_returnsError() {
        // Create a pipeline with an INITIAL_PIPELINE step that is referenced by another step
        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step",
                "test-module",
                null,
                List.of("regular-step"),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.INITIAL_PIPELINE
        );

        PipelineStepConfig regularStep = new PipelineStepConfig(
                "regular-step",
                "test-module",
                null,
                List.of("initial-step"), // References the INITIAL_PIPELINE step
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.PIPELINE
        );

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(initialStep.pipelineStepId(), initialStep);
        steps.put(regularStep.pipelineStepId(), regularStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("test-pipeline", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        
        assertFalse(errors.isEmpty(), "Should detect INITIAL_PIPELINE step referenced by another step");
        assertTrue(errors.stream().anyMatch(e -> e.contains("INITIAL_PIPELINE") && e.contains("referenced by other steps")),
                "Error should mention INITIAL_PIPELINE step being referenced. Errors: " + errors);
    }

    @Test
    void validate_sinkStepWithNextSteps_returnsError() {
        // Create a pipeline with a SINK step that has next steps
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step",
                "test-module",
                null,
                List.of("another-step"), // SINK should not have next steps
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.SINK
        );

        PipelineStepConfig anotherStep = new PipelineStepConfig(
                "another-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.PIPELINE
        );

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(sinkStep.pipelineStepId(), sinkStep);
        steps.put(anotherStep.pipelineStepId(), anotherStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("test-pipeline", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        
        assertFalse(errors.isEmpty(), "Should detect SINK step with next steps");
        assertTrue(errors.stream().anyMatch(e -> e.contains("SINK") && e.contains("has next steps")),
                "Error should mention SINK step having next steps. Errors: " + errors);
    }

    @Test
    void validate_sinkStepWithErrorSteps_returnsError() {
        // Create a pipeline with a SINK step that has error steps
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step",
                "test-module",
                null,
                Collections.emptyList(),
                List.of("error-step"), // SINK should not have error steps
                TransportType.INTERNAL,
                null,
                null,
                StepType.SINK
        );

        PipelineStepConfig errorStep = new PipelineStepConfig(
                "error-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.PIPELINE
        );

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(sinkStep.pipelineStepId(), sinkStep);
        steps.put(errorStep.pipelineStepId(), errorStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("test-pipeline", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        
        assertFalse(errors.isEmpty(), "Should detect SINK step with error steps");
        assertTrue(errors.stream().anyMatch(e -> e.contains("SINK") && e.contains("has next steps or error steps")),
                "Error should mention SINK step having error steps. Errors: " + errors);
    }

    @Test
    void validate_validConfiguration_returnsNoErrors() {
        // Create a valid pipeline with proper step types
        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step",
                "test-module",
                null,
                List.of("regular-step"),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.INITIAL_PIPELINE
        );

        PipelineStepConfig regularStep = new PipelineStepConfig(
                "regular-step",
                "test-module",
                null,
                List.of("sink-step"),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.PIPELINE
        );

        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.SINK
        );

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(initialStep.pipelineStepId(), initialStep);
        steps.put(regularStep.pipelineStepId(), regularStep);
        steps.put(sinkStep.pipelineStepId(), sinkStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("test-pipeline", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, null, null, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        
        assertTrue(errors.isEmpty(), "Valid configuration should not produce any errors. Errors: " + errors);
    }
}