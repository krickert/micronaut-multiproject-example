package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
// No Lombok needed for a simple record

/**
 * Represents a reference to a specific version of a schema stored in a schema registry.
 * This record is immutable.
 *
 * @param subject The subject or name under which the schema artifact is registered.
 * This typically corresponds to the PipelineModuleConfiguration's implementationId.
 * @param version The specific version of the schema to be used.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaReference(
    @JsonProperty("subject") String subject,
    @JsonProperty("version") Integer version
) {
    // Canonical constructor, accessors (subject(), version()), equals(), hashCode(),
    // and toString() are automatically provided.

    // You can add custom constructors or static factory methods if needed.
    // Example: Validating constructor
    public SchemaReference {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("SchemaReference subject cannot be null or blank.");
        }
        if (version == null || version < 1) {
            throw new IllegalArgumentException("SchemaReference version cannot be null and must be positive.");
        }
    }
}