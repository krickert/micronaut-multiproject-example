package com.krickert.search.config.consul;

import com.krickert.search.config.consul.validator.ClusterValidationRule;
import com.krickert.search.config.consul.validator.ReferentialIntegrityValidator;
import com.krickert.search.config.consul.validator.WhitelistValidator;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultConfigurationValidatorTest {

    @Test
    void validate_nullConfig_returnsInvalidResult() {
        List<ClusterValidationRule> mockRules = Collections.emptyList();
        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(mockRules);

        ValidationResult result = validator.validate(null, schemaReference -> Optional.empty());

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        assertEquals("PipelineClusterConfig cannot be null.", result.errors().getFirst());
    }

    @Test
    void validate_noValidationErrors_returnsValidResult() {
        ClusterValidationRule mockRule = mock(ClusterValidationRule.class);
        when(mockRule.validate(any(), any())).thenReturn(Collections.emptyList());

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule));
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validate_singleValidationError_returnsInvalidResult() {
        ClusterValidationRule mockRule = mock(ClusterValidationRule.class);
        when(mockRule.validate(any(), any())).thenReturn(List.of("Validation error"));

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule));
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        assertEquals("Validation error", result.errors().getFirst());

        verify(mockRule, times(1)).validate(eq(config), any());
    }

    @Test
    void validate_multipleValidationErrors_returnsInvalidResult() {
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);
        when(mockRule1.validate(any(), any())).thenReturn(List.of("Error 1", "Error 2"));
        when(mockRule2.validate(any(), any())).thenReturn(List.of("Error 3"));

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule1, mockRule2));
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        assertFalse(result.isValid());
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().containsAll(List.of("Error 1", "Error 2", "Error 3")));

        verify(mockRule1, times(1)).validate(eq(config), any());
        verify(mockRule2, times(1)).validate(eq(config), any());
    }

    @Test
    void validate_ruleThrowsException_logsErrorAndContinues() {
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);

        when(mockRule1.validate(any(), any())).thenThrow(new RuntimeException("Validation exception"));
        when(mockRule2.validate(any(), any())).thenReturn(Collections.emptyList());

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule1, mockRule2));
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains("Validation exception"));

        verify(mockRule1, times(1)).validate(eq(config), any());
        verify(mockRule2, times(1)).validate(eq(config), any());
    }

    @Test
    void validate_emptyRulesList_returnsValidResult() {
        // Create a validator with an empty list of rules
        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(Collections.emptyList());
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        // The validator should return a valid result when there are no rules
        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void validate_withActualRuleImplementations_orchestratesCorrectly() {
        // Create actual rule implementations
        ReferentialIntegrityValidator referentialIntegrityValidator = new ReferentialIntegrityValidator();
        WhitelistValidator whitelistValidator = new WhitelistValidator();

        // Create a validator with actual rule implementations
        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(
                List.of(referentialIntegrityValidator, whitelistValidator));

        // Create a valid config
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");

        // Validate the config
        ValidationResult result = validator.validate(config, schemaReference -> Optional.empty());

        // The validator should return a valid result for a valid config
        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
    }


    @Test
    void validate_executesRulesInOrder() {
        // Create mock rules
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule3 = mock(ClusterValidationRule.class);

        // Set up the rules to return no errors
        when(mockRule1.validate(any(), any())).thenReturn(Collections.emptyList());
        when(mockRule2.validate(any(), any())).thenReturn(Collections.emptyList());
        when(mockRule3.validate(any(), any())).thenReturn(Collections.emptyList());

        // Create the validator with the mock rules in a specific order
        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(
                List.of(mockRule1, mockRule2, mockRule3));

        // Create a config and validate it
        PipelineClusterConfig config = new PipelineClusterConfig("TestCluster");
        validator.validate(config, schemaReference -> Optional.empty());

        // Create an InOrder verifier to check the order of execution
        var inOrder = inOrder(mockRule1, mockRule2, mockRule3);

        // Verify that the rules were executed in the order they were provided
        inOrder.verify(mockRule1).validate(eq(config), any());
        inOrder.verify(mockRule2).validate(eq(config), any());
        inOrder.verify(mockRule3).validate(eq(config), any());
    }
}
