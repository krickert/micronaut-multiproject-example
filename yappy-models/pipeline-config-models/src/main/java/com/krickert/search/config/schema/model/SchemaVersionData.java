package com.krickert.search.config.schema.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
// No Lombok needed

/**
 * Represents a specific version of a schema for a given artifact (subject) in the schema registry.
 * This record is immutable.
 *
 * @param globalId           A globally unique identifier for this specific schema version (optional). Can be null.
 * @param subject            The subject of the schema artifact this version belongs to. Must not be null or blank.
 * @param version            The version number for this schema content. Must not be null and must be positive.
 * @param schemaContent      The actual schema content as a string. Must not be null or blank.
 * @param schemaType         The type of this schema. Defaults to JSON_SCHEMA. Cannot be null.
 * @param compatibility      The compatibility level of this schema version. Can be null.
 * @param createdAt          Timestamp of when this specific schema version was registered. Cannot be null.
 * @param versionDescription An optional description specific to this version. Can be null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaVersionData(
        @JsonProperty("globalId") Long globalId,
        @JsonProperty("subject") String subject,
        @JsonProperty("version") Integer version,
        @JsonProperty("schemaContent") String schemaContent,
        @JsonProperty("schemaType") SchemaType schemaType,
        @JsonProperty("compatibility") SchemaCompatibility compatibility,
        @JsonProperty("createdAt") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC") Instant createdAt,
        @JsonProperty("versionDescription") String versionDescription
) {
    public SchemaVersionData {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("SchemaVersionData subject cannot be null or blank.");
        }
        if (version == null || version < 1) {
            throw new IllegalArgumentException("SchemaVersionData version cannot be null and must be positive.");
        }
        if (schemaContent == null || schemaContent.isBlank()) {
            // Consider if an empty schema "{}" is valid or should be disallowed here vs. by validator
            throw new IllegalArgumentException("SchemaVersionData schemaContent cannot be null or blank.");
        }
        if (schemaType == null) {
            schemaType = SchemaType.JSON_SCHEMA; // Defaulting if null
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("SchemaVersionData createdAt cannot be null.");
        }
        // globalId, compatibility, versionDescription can be null
    }
}