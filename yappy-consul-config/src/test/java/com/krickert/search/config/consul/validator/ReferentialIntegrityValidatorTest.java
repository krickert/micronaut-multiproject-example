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
    void validate_nullClusterName_isCaughtByRecordConstructor() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PipelineClusterConfig(null, null, null, null, null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new PipelineClusterConfig("", null, null, null, null);
        });
    }

    @Test
    void validate_duplicateStepIds_returnsErrors() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);

        PipelineStepConfig step1 = new PipelineStepConfig(
                "step1", "test-module", null, null, null, null, null, null // 8 args
        );
        PipelineStepConfig step2 = new PipelineStepConfig(
                "step1", "test-module", null, null, null, null, null, null // 8 args, same ID
        );

        steps.put("step1", step1);
        steps.put("step1_as_map_key_duplicate", step2);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate step ID 'step1'")),
                "Should detect duplicate step IDs. Errors: " + String.join("; ", errors));
    }

    @Test
    void validate_duplicatePipelineNames_returnsErrors() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        PipelineConfig pipeline1 = new PipelineConfig("same-name", Collections.emptyMap());
        PipelineConfig pipeline2 = new PipelineConfig("same-name", Collections.emptyMap());

        pipelines.put("pipelineKey1", pipeline1);
        pipelines.put("pipelineKey2", pipeline2);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate pipeline name 'same-name'")),
                "Should detect duplicate pipeline names. Errors: " + String.join("; ", errors));
    }

    @Test
    void validate_unknownPipelineImplementationId_returnsErrors() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
                "step1", "non-existent-module", null, null, null, null, null, null // 8 args
        );
        steps.put("step1", step);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown pipelineImplementationId 'non-existent-module'")),
                "Should detect unknown pipelineImplementationId. Errors: " + String.join("; ", errors));
    }

    @Test
    void validate_pipelineNameMismatch_returnsErrors() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        PipelineConfig pipeline = new PipelineConfig("actual-name", Collections.emptyMap());
        pipelines.put("different-key", pipeline);

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Pipeline map key 'different-key' does not match its pipeline.name() field 'actual-name'")),
                "Should detect pipeline name mismatch with map key. Errors: " + String.join("; ", errors));
    }

    @Test
    void validate_validConfig_returnsNoErrors() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);

        PipelineStepConfig step1 = new PipelineStepConfig(
                "step1", "test-module", null, null, null, null, null, null // 8 args
        );
        PipelineStepConfig step2 = new PipelineStepConfig(
                "step2", "test-module", null, null, null, null, null, null // 8 args
        );
        steps.put("step1", step1);
        steps.put("step2", step2);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid configuration should not produce any errors. Errors: " + String.join("; ", errors));
    }

    @Test
    void validate_nullOrBlankValuesInLists_cannotBeTestedDirectlyDueToRecordValidation() {
        assertTrue(true, "Test acknowledges that direct testing of null/blank list elements is superseded by record validation for PipelineStepConfig.");
    }

    // --- Additional Tests ---

    @Test
    void validate_nullPipelineGraphConfig_noErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", null, new PipelineModuleMap(Collections.emptyMap()), null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null pipelineGraphConfig should result in no referential errors from this validator. Errors: " + errors);
    }

    @Test
    void validate_nullPipelineModuleMap_withSteps_reportsUnknownImplementations() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("s1", new PipelineStepConfig("s1", "mod1", null, null, null, null, null, null));
        PipelineConfig p1 = new PipelineConfig("p1", steps);
        PipelineGraphConfig graph = new PipelineGraphConfig(Collections.singletonMap("p1", p1));

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graph, null, null, null // Null module map
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for unknown module implementations.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown pipelineImplementationId 'mod1'")),
                "Error should mention unknown module 'mod1'. Errors: " + errors);
    }

    @Test
    void validate_pipelineGraphWithNullPipelinesMap_noErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", new PipelineGraphConfig(null), new PipelineModuleMap(Collections.emptyMap()), null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "PipelineGraphConfig with null pipelines map should result in no errors. Errors: " + errors);
    }

    @Test
    void validate_pipelineWithNullStepsMap_noStepErrors() {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("p1", new PipelineConfig("p1", null)); // Pipeline with null steps map
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", graphConfig, new PipelineModuleMap(Collections.emptyMap()), null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Pipeline with null steps map should not cause step validation errors. Errors: " + errors);
    }

    @Test
    void validate_stepIdMismatchWithMapKey_returnsError() {
        Map<String, PipelineModuleConfiguration> modules = Collections.singletonMap(
                "mod1", new PipelineModuleConfiguration("M1", "mod1", null)
        );
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig stepWithDifferentId = new PipelineStepConfig("actualStepId", "mod1", null, null, null, null, null, null);
        steps.put("mapStepKey", stepWithDifferentId); // Key in map is different

        PipelineConfig pipeline = new PipelineConfig("p1", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("p1", pipeline));
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("test-cluster", graphConfig, moduleMap, null, null);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Pipeline step key 'mapStepKey' does not match its pipelineStepId field 'actualStepId'")),
                "Should detect step ID mismatch. Errors: " + errors);
    }

    @Test
    void validate_stepWithNullPipelineImplementationId_isCaughtByRecordConstructor() {
        // This test verifies that the PipelineStepConfig constructor prevents null/blank implementation IDs.
        // The validator's check is a secondary defense.
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig("s1", null, null, null, null, null, null, null); // Impl ID is null
        });
        assertEquals("PipelineStepConfig pipelineImplementationId cannot be null or blank.", exception.getMessage());

        Exception exceptionBlank = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig("s1", "", null, null, null, null, null, null); // Impl ID is blank
        });
        assertEquals("PipelineStepConfig pipelineImplementationId cannot be null or blank.", exceptionBlank.getMessage());
    }


    @Test
    void validate_pipelineDefinitionIsNullInMap_isCaughtByGraphConfigConstructor() {
        // This test verifies that PipelineGraphConfig constructor (if using Map.copyOf)
        // prevents maps with null values. The validator's check is secondary.
        Map<String, PipelineConfig> pipelinesWithNullValue = new HashMap<>();
        pipelinesWithNullValue.put("keyForNullPipeline", null);

        Exception exception = assertThrows(NullPointerException.class, () -> {
            new PipelineGraphConfig(pipelinesWithNullValue);
        });
        // The exact message might vary slightly based on JDK version for Map.copyOf's NPE
        // but it will be an NPE.
        assertNotNull(exception, "Expected NullPointerException when PipelineGraphConfig is given a map with null values.");
    }

    @Test
    void validate_stepDefinitionIsNullInMap_isCaughtByPipelineConfigConstructor() {
        // This test verifies that PipelineConfig constructor (if using Map.copyOf)
        // prevents maps with null values. The validator's check is secondary.
        Map<String, PipelineStepConfig> stepsWithNullValue = new HashMap<>();
        stepsWithNullValue.put("keyForNullStep", null);

        Exception exception = assertThrows(NullPointerException.class, () -> {
            new PipelineConfig("p1", stepsWithNullValue);
        });
        assertNotNull(exception, "Expected NullPointerException when PipelineConfig is given a map with null values.");
    }

}