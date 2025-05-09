package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map; // If getConfigMap is still desired conceptually

// No Micronaut imports

/**
 * Represents custom JSON configuration options for a pipeline step.
 * In a pure Jackson model, this primarily holds the configuration string.
 * Validation and schema interactions are handled by the service layer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonConfigOptions {

    @JsonProperty("jsonConfig")
    private String jsonConfig = "{}"; // Default to empty JSON object

    // The SchemaServiceConfig and its related logic (validateConfig, getValidationErrors, etc.)
    // would be removed from this pure POJO model.
    // That logic would reside in a service that consumes this model.
    // If the consuming service needs to provide the parsed map back to the step,
    // it could do so, but this POJO wouldn't inherently handle that.

    // Example: If you still want a way to hold a parsed map (populated by the service layer)
    // you could add a transient field, but it's cleaner if the service layer handles parsing and validation.
    // @JsonIgnore
    // private transient Map<String, Object> parsedConfigMap;
}