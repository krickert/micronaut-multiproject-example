package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;

import java.util.Optional;
import java.util.function.Function;

/**
 * Validates a PipelineClusterConfig, including its internal consistency,
 * adherence to schema definitions for custom configurations, and other business rules.
 */
public interface ConfigurationValidator {

    /**
     * Validates the given PipelineClusterConfig.
     *
     * @param configToValidate      The PipelineClusterConfig object to validate.
     * @param schemaContentProvider A function that can provide the schema content string
     *                              for a given SchemaReference. This allows the validator
     *                              to dynamically fetch schema content as needed for validating
     *                              JsonConfigOptions.
     * @return A ValidationResult indicating whether the configuration is valid,
     * and a list of error messages if it's invalid.
     */
    ValidationResult validate(
            PipelineClusterConfig configToValidate,
            Function<SchemaReference, Optional<String>> schemaContentProvider
    );
}