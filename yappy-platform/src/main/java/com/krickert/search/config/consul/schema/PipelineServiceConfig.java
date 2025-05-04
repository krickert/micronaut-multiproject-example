package com.krickert.search.config.consul.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
// import com.fasterxml.jackson.databind.JsonNode; // Not strictly needed in interface now
// import com.fasterxml.jackson.databind.ObjectMapper; // Removed as param

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Interface for pipeline service configuration beans that support
 * JSON schema validation and serialization using an internally managed ObjectMapper.
 *
 * Implementations of this interface are expected in the actual pipeline services,
 * wrapping their specific configuration parameters.
 */
public interface PipelineServiceConfig {

    /**
     * Returns the JSON schema definition associated with this configuration type.
     * This might be fetched from a central registry or defined statically.
     *
     * @return JSON schema as a String, or null if no specific schema is defined.
     */
    String getSchemaJson();

    /**
     * Validates the current state of the configuration bean against its schema
     * using the internal ObjectMapper.
     *
     * @return A set of validation messages. Empty if valid.
     */
    Set<String> validate();

    /**
     * Validates a given JSON object string against this configuration type's schema
     * using the internal ObjectMapper.
     *
     * @param jsonInput The configuration JSON string to validate.
     * @return A set of validation messages. Empty if valid.
     * @throws JsonProcessingException if the input is invalid JSON.
     */
    Set<String> validateJson(String jsonInput) throws JsonProcessingException;


    /**
     * Serializes the configuration bean to its JSON representation
     * using the internal ObjectMapper.
     *
     * @return JSON string representation.
     * @throws JsonProcessingException if serialization fails.
     */
    String toJson() throws JsonProcessingException;

    /**
     * Deserializes JSON into this configuration bean instance
     * using the internal ObjectMapper.
     * Note: This method modifies the state of the implementing object.
     *
     * @param jsonInput The JSON string to deserialize from.
     * @throws IOException if deserialization fails.
     */
    void fromJson(String jsonInput) throws IOException;

    /**
     * Deserializes a Map (typically from configParams) into this configuration bean instance,
     * potentially handling special keys like "_json". Uses the internal ObjectMapper.
     *
     * @param configParams Map representation of the configuration.
     */
    void fromMap(Map<String, Object> configParams);

    /**
     * Converts the configuration bean to a Map representation
     * using the internal ObjectMapper.
     *
     * @return Map representation of the configuration.
     */
    Map<String, Object> toMap();
}