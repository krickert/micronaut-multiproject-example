package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for a pipeline module, which represents a service implementation meant to be a type of node in the graph.
 * Each module can have its own custom configuration schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineModuleConfiguration {

    /**
     * Required field - the user-friendly display name of this module.
     */
    private String implementationName;

    /**
     * Required field - the unique ID of the module (e.g., service ID).
     * This ID is used as the key in PipelineModuleMap.availableModules and
     * referenced by PipelineStepConfig.pipelineImplementationId.
     */
    private String implementationId;

    /**
     * The JSON schema string that defines the structure and validation rules for
     * the custom configuration (JsonConfigOptions.jsonConfig) this module accepts.
     * This schema is used by SchemaServiceConfig for validation.
     * Can be null or empty if the module does not require custom configuration or accepts any JSON object.
     */
    @Nullable
    private String customConfigJsonSchema;
}