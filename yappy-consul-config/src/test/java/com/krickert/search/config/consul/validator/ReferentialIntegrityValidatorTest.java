package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ReferentialIntegrityValidator class.
 *<br/>
 * These tests verify that the validator correctly identifies:
 * 1. Step ID uniqueness within a pipeline
 * 2. Pipeline name uniqueness within a graph
 * 3. Other validation rules that were already implemented
 */
class ReferentialIntegrityValidatorTest {

    private ReferentialIntegrityValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    @BeforeEach
    void setUp() {
        validator = new ReferentialIntegrityValidator();
        // Simple schema content provider that always returns an empty schema
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_nullClusterConfig_returnsError() {
        List<String> errors = validator.validate(null, schemaContentProvider);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("PipelineClusterConfig is null"));
    }

    @Test
    void validate_nullClusterName_throwsException() {
        // Attempting to create a PipelineClusterConfig with an empty name should throw an exception
        assertThrows(IllegalArgumentException.class, () -> {
            new PipelineClusterConfig(""); // Empty name will be caught by record constructor
        });
    }

    @Test
    void validate_duplicateStepIds_returnsErrors() {
        // Create a pipeline with duplicate step IDs
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a module that will be referenced by the steps
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
            "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);

        // Create two steps with the same ID
        PipelineStepConfig step1 = new PipelineStepConfig(
            "step1", "test-module", null, null, null, null
        );
        PipelineStepConfig step2 = new PipelineStepConfig(
            "step1", "test-module", null, null, null, null
        );

        steps.put("step1", step1);
        steps.put("step1-duplicate", step2); // Different map key but same pipelineStepId

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate step ID 'step1'")),
                "Should detect duplicate step IDs");
    }

    @Test
    void validate_duplicatePipelineNames_returnsErrors() {
        // Create two pipelines with the same name
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        PipelineConfig pipeline1 = new PipelineConfig("same-name", Collections.emptyMap());
        PipelineConfig pipeline2 = new PipelineConfig("same-name", Collections.emptyMap());

        pipelines.put("pipeline1", pipeline1);
        pipelines.put("pipeline2", pipeline2);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate pipeline name 'same-name'")),
                "Should detect duplicate pipeline names");
    }

    @Test
    void validate_unknownPipelineImplementationId_returnsErrors() {
        // Create a step that references a non-existent module
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig step = new PipelineStepConfig(
            "step1", "non-existent-module", null, null, null, null
        );

        steps.put("step1", step);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown pipelineImplementationId")),
                "Should detect unknown pipelineImplementationId");
    }

    @Test
    void validate_validConfig_returnsNoErrors() {
        // Create a valid pipeline configuration
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a module that will be referenced by the steps
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
            "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);

        // Create steps with unique IDs
        PipelineStepConfig step1 = new PipelineStepConfig(
            "step1", "test-module", null, null, null, null
        );
        PipelineStepConfig step2 = new PipelineStepConfig(
            "step2", "test-module", null, null, null, null
        );

        steps.put("step1", step1);
        steps.put("step2", step2);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Valid configuration should not produce any errors");
    }
}
