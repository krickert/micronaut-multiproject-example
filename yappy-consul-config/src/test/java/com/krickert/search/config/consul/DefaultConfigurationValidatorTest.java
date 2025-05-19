package com.krickert.search.config.consul;

import com.krickert.search.config.consul.validator.ClusterValidationRule;
// Import the model classes that will be used to create a minimal PipelineClusterConfig
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference; // For the schemaContentProvider lambda

import org.junit.jupiter.api.Test;
import org.mockito.InOrder; // For inOrder verification

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set; // For Collections.emptySet()
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultConfigurationValidatorTest {

    // Helper to create a minimal valid PipelineClusterConfig for testing flow
    // Ensures the correct 6-argument constructor is used.
    private PipelineClusterConfig createMinimalValidClusterConfig() {
        return new PipelineClusterConfig(
                "TestCluster",                                 // clusterName
                new PipelineGraphConfig(Collections.emptyMap()), // pipelineGraphConfig (minimal)
                new PipelineModuleMap(Collections.emptyMap()),   // pipelineModuleMap (minimal)
                null,                                          // defaultPipelineName
                Collections.emptySet(),                        // allowedKafkaTopics
                Collections.emptySet()                         // allowedGrpcServices
        );
    }

    private Function<SchemaReference, Optional<String>> dummySchemaProvider() {
        return schemaReference -> Optional.empty();
    }

    @Test
    void validate_nullConfig_returnsInvalidResult() {
        List<ClusterValidationRule> mockRules = Collections.emptyList();
        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(mockRules);

        ValidationResult result = validator.validate(null, dummySchemaProvider());

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        // Assuming errors() returns a List<String> and get(0) is safe if size is 1.
        assertEquals("PipelineClusterConfig cannot be null.", result.errors().get(0));
    }

    @Test
    void validate_noValidationErrors_returnsValidResult() {
        ClusterValidationRule mockRule = mock(ClusterValidationRule.class);
        PipelineClusterConfig config = createMinimalValidClusterConfig();

        when(mockRule.validate(any(PipelineClusterConfig.class), any())).thenReturn(Collections.emptyList());

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule));
        ValidationResult result = validator.validate(config, dummySchemaProvider());

        assertTrue(result.isValid());
        assertTrue(result.errors().isEmpty());
        verify(mockRule).validate(eq(config), any());
    }

    @Test
    void validate_oneRuleReturnsError_returnsInvalidResultWithErrors() {
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);
        PipelineClusterConfig config = createMinimalValidClusterConfig();

        when(mockRule1.validate(any(PipelineClusterConfig.class), any())).thenReturn(List.of("Error from rule 1"));
        when(mockRule2.validate(any(PipelineClusterConfig.class), any())).thenReturn(Collections.emptyList());

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule1, mockRule2));
        ValidationResult result = validator.validate(config, dummySchemaProvider());

        assertFalse(result.isValid());
        assertEquals(1, result.errors().size());
        assertEquals("Error from rule 1", result.errors().get(0));
        verify(mockRule1).validate(eq(config), any());
        verify(mockRule2).validate(eq(config), any());
    }

    @Test
    void validate_multipleRulesReturnErrors_returnsInvalidResultWithAllErrors() {
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);
        PipelineClusterConfig config = createMinimalValidClusterConfig();

        when(mockRule1.validate(any(PipelineClusterConfig.class), any())).thenReturn(List.of("Error A from rule 1", "Error B from rule 1"));
        when(mockRule2.validate(any(PipelineClusterConfig.class), any())).thenReturn(List.of("Error C from rule 2"));

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(List.of(mockRule1, mockRule2));
        ValidationResult result = validator.validate(config, dummySchemaProvider());

        assertFalse(result.isValid());
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().contains("Error A from rule 1"));
        assertTrue(result.errors().contains("Error B from rule 1"));
        assertTrue(result.errors().contains("Error C from rule 2"));
        verify(mockRule1).validate(eq(config), any());
        verify(mockRule2).validate(eq(config), any());
    }

    @Test
    void validate_executesRulesInOrder() {
        ClusterValidationRule mockRule1 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule2 = mock(ClusterValidationRule.class);
        ClusterValidationRule mockRule3 = mock(ClusterValidationRule.class);
        PipelineClusterConfig config = createMinimalValidClusterConfig();

        when(mockRule1.validate(any(), any())).thenReturn(Collections.emptyList());
        when(mockRule2.validate(any(), any())).thenReturn(Collections.emptyList());
        when(mockRule3.validate(any(), any())).thenReturn(Collections.emptyList());

        DefaultConfigurationValidator validator = new DefaultConfigurationValidator(
                List.of(mockRule1, mockRule2, mockRule3));

        validator.validate(config, dummySchemaProvider());

        // Use InOrder for verifying order of mock interactions
        InOrder inOrder = inOrder(mockRule1, mockRule2, mockRule3);

        inOrder.verify(mockRule1).validate(eq(config), any());
        inOrder.verify(mockRule2).validate(eq(config), any());
        inOrder.verify(mockRule3).validate(eq(config), any());
    }
}