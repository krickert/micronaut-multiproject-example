package com.krickert.search.config.schema.registry.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
// No Lombok needed

/**
 * Represents a schema artifact (often referred to as a "subject") registered in the schema registry.
 * It acts as a container for multiple versions of a schema.
 * This record is immutable.
 *
 * @param subject The unique subject or name of the schema artifact. Must not be null or blank.
 * @param description An optional description of the schema artifact. Can be null.
 * @param schemaType The type of schemas contained under this artifact. Defaults to JSON_SCHEMA. Cannot be null.
 * @param createdAt Timestamp of when this artifact was first created. Cannot be null.
 * @param updatedAt Timestamp of the last modification to this artifact. Cannot be null.
 * @param latestVersionNumber The version number of the schema currently considered "latest". Can be null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaRegistryArtifact(
    @JsonProperty("subject") String subject,
    @JsonProperty("description") String description,
    @JsonProperty("schemaType") SchemaType schemaType,
    @JsonProperty("createdAt") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant createdAt,
    @JsonProperty("updatedAt") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant updatedAt,
    @JsonProperty("latestVersionNumber") Integer latestVersionNumber
) {
    public SchemaRegistryArtifact {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("SchemaRegistryArtifact subject cannot be null or blank.");
        }
        if (schemaType == null) {
            schemaType = SchemaType.JSON_SCHEMA; // Defaulting if null, though better to ensure non-null input
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("SchemaRegistryArtifact createdAt cannot be null.");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("SchemaRegistryArtifact updatedAt cannot be null.");
        }
        // description can be null
        // latestVersionNumber can be null
    }

    // Convenience constructor that defaults schemaType and sets updatedAt to createdAt
    public SchemaRegistryArtifact(String subject, String description, Instant createdAt, Integer latestVersionNumber) {
        this(subject, description, SchemaType.JSON_SCHEMA, createdAt, createdAt, latestVersionNumber);
    }
}