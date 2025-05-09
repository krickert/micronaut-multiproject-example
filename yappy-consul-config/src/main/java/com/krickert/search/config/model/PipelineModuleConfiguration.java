package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;


/**
 * Configuration for a pipeline module, which represents a service implementation.
 * Each module can have its own custom configuration schema, referenced from a schema registry.
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
    @NonNull
    private String implementationName;

    /**
     * Required field - the unique ID of the module (e.g., service ID).
     * This ID is used as the key in PipelineModuleMap.availableModules and
     * typically serves as the 'subject' for its schema in the schema registry.
     */
    @NonNull
    private String implementationId;

    /**
     * A reference to the schema in the registry that defines the structure for
     * this module's custom configuration (JsonConfigOptions.jsonConfig).
     * If null, the module might not have a defined custom configuration schema,
     * or it might accept any JSON object (permissive).
     */
    @Nullable
    private SchemaReference customConfigSchemaReference; // Replaces String customConfigJsonSchema
}