package com.krickert.search.config.consul;

import com.krickert.search.config.consul.validator.ClusterValidationRule;
import com.krickert.search.config.consul.validator.CustomConfigSchemaValidator;
import com.krickert.search.config.consul.validator.ReferentialIntegrityValidator;
import com.krickert.search.config.consul.validator.WhitelistValidator;
import com.krickert.search.config.pipeline.model.*;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DefaultConfigurationValidator using Micronaut's dependency injection.
 * This test verifies that the DefaultConfigurationValidator correctly orchestrates all
 * ClusterValidationRule implementations that are automatically injected by Micronaut.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultConfigurationValidatorMicronautTest {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultConfigurationValidatorMicronautTest.class);

    @Inject
    private DefaultConfigurationValidator validator;

    @Inject
    private List<ClusterValidationRule> validationRules;

    /**
     * Test that all expected validation rules are injected by Micronaut.
     */
    @Test
    void testValidationRulesInjected() {
        LOG.info("Injected validation rules: {}", validationRules);
        
        // Verify that we have at least the three validation rules we expect
        assertTrue(validationRules.size() >= 3, "Expected at least 3 validation rules, but got " + validationRules.size());
        
        // Verify that the expected validation rule types are present
        assertTrue(validationRules.stream().anyMatch(rule -> rule instanceof ReferentialIntegrityValidator),
                "ReferentialIntegrityValidator not found in injected rules");
        assertTrue(validationRules.stream().anyMatch(rule -> rule instanceof CustomConfigSchemaValidator),
                "CustomConfigSchemaValidator not found in injected rules");
        assertTrue(validationRules.stream().anyMatch(rule -> rule instanceof WhitelistValidator),
                "WhitelistValidator not found in injected rules");
    }

    /**
     * Test that the DefaultConfigurationValidator is correctly injected and can validate a simple configuration.
     */
    @Test
    void testValidatorInjected() {
        // Verify that the validator is injected
        assertNotNull(validator, "DefaultConfigurationValidator should be injected");
        
        // Create a simple valid configuration
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");
        
        // Create a schema content provider that always returns an empty schema
        Function<SchemaReference, Optional<String>> schemaContentProvider = ref -> Optional.of("{}");
        
        // Validate the configuration
        ValidationResult result = validator.validate(config, schemaContentProvider);
        
        // Verify that the validation was successful
        assertTrue(result.isValid(), "Validation should succeed for a simple valid configuration");
        assertTrue(result.errors().isEmpty(), "There should be no validation errors");
    }

    /**
     * Test that the DefaultConfigurationValidator correctly handles a null configuration.
     */
    @Test
    void testValidateNullConfig() {
        // Validate a null configuration
        ValidationResult result = validator.validate(null, ref -> Optional.empty());
        
        // Verify that the validation failed
        assertFalse(result.isValid(), "Validation should fail for a null configuration");
        assertEquals(1, result.errors().size(), "There should be exactly one validation error");
        assertEquals("PipelineClusterConfig cannot be null.", result.errors().getFirst(), 
                "The error message should indicate that the configuration is null");
    }

    /**
     * Test that the DefaultConfigurationValidator correctly handles a configuration with validation errors.
     */
    @Test
    void testValidateInvalidConfig() {
        // Create a configuration with a pipeline that has a step with an invalid implementation ID
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
                "step1", "non-existent-module", null, null, null, null);
        steps.put("step1", step);
        
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);
        
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null, null);
        
        // Validate the configuration
        ValidationResult result = validator.validate(config, ref -> Optional.empty());
        
        // Verify that the validation failed
        assertFalse(result.isValid(), "Validation should fail for a configuration with an invalid implementation ID");
        assertTrue(result.errors().size() >= 1, "There should be at least one validation error");
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("references unknown pipelineImplementationId")),
                "At least one error should indicate an unknown implementation ID");
    }
}