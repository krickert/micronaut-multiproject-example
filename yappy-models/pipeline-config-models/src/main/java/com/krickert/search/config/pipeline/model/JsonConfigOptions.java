package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Represents custom JSON configuration options for a pipeline step.
 * This record is immutable and primarily holds the configuration string.
 * Validation against a schema is handled by the service layer.
 *
 * @param jsonConfig The JSON configuration as a string for a specific step.
 *                   Cannot be null. Must be at least an empty JSON object string "{}".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record JsonConfigOptions(
        @JsonProperty("jsonConfig") String jsonConfig
) {
    // Public constant for the default empty JSON object string
    public static final String DEFAULT_EMPTY_JSON = "{}";

    /**
     * Default constructor ensuring jsonConfig is initialized to an empty JSON object string.
     * Useful for cases where an empty configuration is the default.
     */
    public JsonConfigOptions() {
        this(DEFAULT_EMPTY_JSON);
    }

    /**
     * Canonical constructor.
     *
     * @param jsonConfig The JSON configuration string.
     * @throws IllegalArgumentException if jsonConfig is null.
     */
    @JsonCreator // Helps Jackson identify this as the constructor to use for deserialization
    public JsonConfigOptions(@JsonProperty("jsonConfig") String jsonConfig) {
        if (jsonConfig == null) {
            throw new IllegalArgumentException("jsonConfig cannot be null. Use an empty JSON object string '{}' if no configuration is intended.");
        }
        this.jsonConfig = jsonConfig;
        // Note: Validating if the string is syntactically correct JSON here is optional.
        // Often, this level of validation is deferred until the JSON is parsed against its schema.
        // If you want a basic check here, you could add it, but it might be redundant
        // with later schema validation.
    }
}
