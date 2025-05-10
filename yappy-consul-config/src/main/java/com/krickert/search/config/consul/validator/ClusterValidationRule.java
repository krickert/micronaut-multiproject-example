package com.krickert.search.config.consul.validator; // New sub-package

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.consul.ValidationResult; // Assuming this record is accessible

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Represents a specific validation rule to be applied to a PipelineClusterConfig.
 */
@FunctionalInterface // If it only has one abstract method for applying the rule
public interface ClusterValidationRule {
    /**
     * Applies this validation rule to the given configuration.
     *
     * @param clusterConfig The PipelineClusterConfig to validate.
     * @param schemaContentProvider A function to retrieve schema content for validation.
     * @return A list of error messages if validation fails for this rule, an empty list otherwise.
     */
    List<String> validate(
        PipelineClusterConfig clusterConfig,
        Function<SchemaReference, Optional<String>> schemaContentProvider
    );
}