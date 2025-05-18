package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

// ... (javadoc)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record SchemaReference(
        @JsonProperty("subject") String subject,
        @JsonProperty("version") Integer version
) {
    // Validating constructor
    public SchemaReference {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("SchemaReference subject cannot be null or blank.");
        }
        if (version == null || version < 1) {
            throw new IllegalArgumentException("SchemaReference version cannot be null and must be positive.");
        }
    }

    /**
     * Returns a string representation combining subject and version,
     * suitable for logging or as a unique identifier.
     * Example: "my-schema-subject:3"
     *
     * @return A string combining subject and version.
     */
    public String toIdentifier() {
        return String.format("%s:%s", subject, version);
    }

    // The default toString() for a record is already quite good:
    // SchemaReference[subject=my-schema-subject, version=3]
    // but toIdentifier() gives you a more specific format if needed.
}
