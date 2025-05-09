package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;


/**
 * Represents a reference to a specific version of a schema stored in a schema registry.
 * The 'subject' often corresponds to the PipelineModuleConfiguration's implementationId.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class SchemaReference {

    /**
     * The subject or name under which the schema artifact is registered.
     * This typically corresponds to the PipelineModuleConfiguration.implementationId.
     */
    @NonNull
    private String subject;

    /**
     * The specific version of the schema to be used.
     */
    @NonNull
    private Integer version;
}