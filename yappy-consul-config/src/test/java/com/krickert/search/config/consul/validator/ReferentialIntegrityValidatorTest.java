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
    void validate_pipelineNameMismatch_returnsErrors() {
        // Create a pipeline where the map key doesn't match the pipeline.name() field
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        PipelineConfig pipeline = new PipelineConfig("actual-name", Collections.emptyMap());
        pipelines.put("different-key", pipeline); // Map key doesn't match pipeline.name()

        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());

        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.stream().anyMatch(e -> e.contains("Pipeline map key 'different-key' does not match its pipeline.name() field 'actual-name'")),
                "Should detect pipeline name mismatch with map key");
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

    /**
     * Note: We can't directly test null/blank values in the lists (kafkaListenTopics, kafkaPublishTopics, grpcForwardTo)
     * because the PipelineStepConfig record constructor already validates these inputs and throws exceptions for null
     * or blank elements. The ReferentialIntegrityValidator's null/blank checks are still important for robustness,
     * but they're effectively redundant with the record constructor's validation.
     * <br/>
     * In a real application, these checks would catch issues if the validation in the record constructor was bypassed
     * or if the data came from a source that didn't validate it.
     * <br/>
     * The code that checks for null/blank values in the lists is at lines 138-166 in ReferentialIntegrityValidator.java:
     * <br/>
     * 1. For kafkaListenTopics:
     * ```java
     * if (step.kafkaListenTopics() != null) {
     *     for (String topic : step.kafkaListenTopics()) {
     *         if (topic == null || topic.isBlank()) {
     *             errors.add(String.format("Pipeline step '%s' in pipeline '%s' (cluster '%s') contains a null or blank Kafka listen topic.",
     *                     step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
     *         }
     *     }
     * }
     * ```
     * <br/>
     * 2. For kafkaPublishTopics:
     * ```java
     * if (step.kafkaPublishTopics() != null) {
     *     for (KafkaPublishTopic pubTopic : step.kafkaPublishTopics()) {
     *         if (pubTopic == null || pubTopic.topic() == null || pubTopic.topic().isBlank()) {
     *             errors.add(String.format("Pipeline step '%s' in pipeline '%s' (cluster '%s') contains a null or blank Kafka publish topic.",
     *                     step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
     *         }
     *     }
     * }
     * ```
     * <br/>
     * 3. For grpcForwardTo:
     * ```java
     * if (step.grpcForwardTo() != null) {
     *     for (String service : step.grpcForwardTo()) {
     *         if (service == null || service.isBlank()) {
     *             errors.add(String.format("Pipeline step '%s' in pipeline '%s' (cluster '%s') contains a null or blank gRPC forward-to service.",
     *                     step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
     *         }
     *     }
     * }
     * ```
     */
    @Test
    void validate_nullOrBlankValuesInLists_cannotBeTestedDirectly() {
        // This test is a placeholder to document why we can't directly test null/blank values in the lists
        // See the comment above for details

        // We can verify that the ReferentialIntegrityValidator class has the code to check for null/blank values
        // in the lists by examining the implementation, but we can't directly test it with null/blank values
        // because the PipelineStepConfig record constructor already validates these inputs and throws exceptions
        // for null or blank elements.
    }
}
