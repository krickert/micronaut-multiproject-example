package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
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
     * Required field - the name of this module (pretty display name)
     */
    private String implementationName;

    /**
     * Required field - the ID of the module (service ID unique)
     */
    private String implementationId;

    /**
     * Each application itself is its own PipeStep implementation, and with that comes with a custom JSON
     * to be used by the SchemaServiceConfig.
     */
    private String customConfigJsonSchema;
}
