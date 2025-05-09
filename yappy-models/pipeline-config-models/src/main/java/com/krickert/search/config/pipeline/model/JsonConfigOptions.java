package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
// No Lombok needed

/**
 * Represents custom JSON configuration options for a pipeline step.
 * This record is immutable and primarily holds the configuration string.
 * Validation and schema interactions are handled by the service layer.
 *
 * @param jsonConfig The JSON configuration as a string for a specific step. Defaults to "{}".
 * Cannot be null, but can be an empty JSON object string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonConfigOptions(
    @JsonProperty("jsonConfig") String jsonConfig
) {
    /**
     * Default constructor providing an empty JSON object string.
     */
    public JsonConfigOptions() {
        this("{}"); // Default to empty JSON object string
    }

    public JsonConfigOptions {
        if (jsonConfig == null) {
            // Or default to "{}" if null is passed, though constructor argument is not @Nullable
            throw new IllegalArgumentException("jsonConfig cannot be null. Use an empty JSON object string '{}' instead.");
        }
        // Further validation (e.g., is it valid JSON?) could be done here
        // but might be better handled by the service layer or Jackson during deserialization.
    }
}