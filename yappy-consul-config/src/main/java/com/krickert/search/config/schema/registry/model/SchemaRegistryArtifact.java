package com.krickert.search.config.schema.registry.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.time.Instant;
import java.util.List; // To store references or a list of version numbers

/**
 * Represents a schema artifact (often referred to as a "subject") registered in the schema registry.
 * It acts as a container for multiple versions of a schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class SchemaRegistryArtifact {

    /**
     * The unique subject or name of the schema artifact (e.g., "moduleX-config-schema", often corresponding
     * to a PipelineModuleConfiguration.implementationId).
     * This ID is globally unique within the registry for artifacts.
     */
    @NonNull
    private String subject;

    /**
     * An optional description of the schema artifact and its purpose.
     */
    @Nullable
    private String description;

    /**
     * The type of schemas contained under this artifact (e.g., JSON_SCHEMA, AVRO).
     */
    @NonNull
    private SchemaType schemaType = SchemaType.JSON_SCHEMA; // Defaulting to JSON_SCHEMA

    /**
     * Timestamp of when this artifact was first created in the registry.
     */
    @NonNull
    private Instant createdAt;

    /**
     * Timestamp of the last modification to this artifact (e.g., a new version was added,
     * or description was updated).
     */
    @NonNull
    private Instant updatedAt;

    /**
     * The version number of the schema version currently considered "latest" for this subject.
     * Can be null if no versions exist or no version is explicitly marked as latest.
     */
    @Nullable
    private Integer latestVersionNumber;

    // Optionally, you could store a list of all version numbers directly here for quick lookup,
    // or the Schema Registry service would provide an API to list versions for a subject.
    // For a pure model, it might be cleaner to query versions separately.
    // List<Integer> availableVersions;
}