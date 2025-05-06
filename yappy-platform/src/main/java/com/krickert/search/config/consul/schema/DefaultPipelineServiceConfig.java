package com.krickert.search.config.consul.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.util.StringUtils;
import io.micronaut.serde.annotation.Serdeable;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.*;

/**
 * Default implementation of PipelineServiceConfig for services without a specific
 * registered JSON schema. It wraps a standard Map &lt;String, Object&rt;, uses an internal
 * ObjectMapper, provides default pass-through validation, and handles a special "_json"
 * key in fromMap for merging JSON string data.
 */
@Introspected
@Serdeable // Micronaut Serde
@Slf4j
public class DefaultPipelineServiceConfig implements PipelineServiceConfig {

    private static final String JSON_OVERRIDE_KEY = "_json";
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {};

    // The actual configuration parameters
    private Map<String, Object> params = new HashMap<>();

    // Internal ObjectMapper instance
    private final ObjectMapper objectMapper;

    /**
     * Default constructor using a default ObjectMapper.
     */
    public DefaultPipelineServiceConfig() {
        this(new ObjectMapper(), null); // Initialize with default mapper, empty params
    }

     /**
     * Constructor to initialize with existing parameters using a default ObjectMapper.
     * @param initialParams The initial configuration parameters.
     */
    public DefaultPipelineServiceConfig(Map<String, Object> initialParams) {
         this(new ObjectMapper(), initialParams); // Initialize with default mapper
    }

    /**
     * Constructor allowing injection of a specific ObjectMapper.
     * @param objectMapper The ObjectMapper instance to use.
     */
    public DefaultPipelineServiceConfig(ObjectMapper objectMapper) {
        this(objectMapper, null); // Initialize with provided mapper, empty params
    }

    /**
     * Constructor allowing injection of ObjectMapper and initial parameters.
     * @param objectMapper The ObjectMapper instance to use.
     * @param initialParams The initial configuration parameters (can be null).
     */
    public DefaultPipelineServiceConfig(ObjectMapper objectMapper, Map<String, Object> initialParams) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        // Initialize params using the fromMap logic to handle potential _json key
        fromMap(initialParams);
    }

    @Override
    public String getSchemaJson() {
        // No specific schema for the default implementation
        return null;
    }

    @Override
    public Set<String> validate() {
        // Default implementation always considers the config valid as there's no schema.
        return Collections.emptySet();
    }

    @Override
    public Set<String> validateJson(String jsonInput) throws JsonProcessingException {
        // Default implementation always considers the JSON valid as there's no schema.
        // We still need to ensure the input is valid JSON structure though.
        try {
            this.objectMapper.readTree(jsonInput); // Attempt to parse
            log.debug("Performing default validation (no schema) - input is valid JSON structure.");
            return Collections.emptySet();
        } catch (JsonProcessingException e) {
            log.warn("Invalid JSON structure provided during default validation: {}", e.getMessage());
            throw e; // Re-throw as per interface contract
        }
    }

    @Override
    public String toJson() throws JsonProcessingException {
        if (this.params == null) {
            return "{}";
        }
        // Use the internal objectMapper
        return this.objectMapper.writeValueAsString(this.params);
    }

    @Override
    public void fromJson(String jsonInput) throws IOException {
        // Use the internal objectMapper
        if (StringUtils.isEmpty(jsonInput)) {
            this.params = new HashMap<>();
        } else {
            // Deserialize into a map using the internal mapper
            this.params = this.objectMapper.readValue(jsonInput, MAP_TYPE_REFERENCE);
        }
    }

    /**
     * Deserializes a Map into this configuration instance using the internal ObjectMapper.
     * If the input map contains a key "_json" with a String value,
     * that string is parsed as JSON and its key-value pairs are merged
     * into the configuration, potentially overwriting other keys from the original map.
     *
     * @param configParams Map representation of the configuration.
     */
    @Override
    public void fromMap(Map<String, Object> configParams) {
        Map<String, Object> processedParams = new HashMap<>();
        Map<String, Object> jsonOverrides = null;

        if (configParams != null) {
            // 1. Copy non-_json keys
            for (Map.Entry<String, Object> entry : configParams.entrySet()) {
                if (!JSON_OVERRIDE_KEY.equals(entry.getKey())) {
                    processedParams.put(entry.getKey(), entry.getValue());
                }
            }

            // 2. Process _json key if present
            if (configParams.containsKey(JSON_OVERRIDE_KEY)) {
                Object jsonValue = configParams.get(JSON_OVERRIDE_KEY);
                if (jsonValue instanceof String jsonString && !jsonString.isBlank()) {
                    try {
                        // Parse the JSON string using internal mapper
                        jsonOverrides = this.objectMapper.readValue(jsonString, MAP_TYPE_REFERENCE);
                        log.debug("Parsed JSON string from key '{}'", JSON_OVERRIDE_KEY);
                    } catch (JsonProcessingException e) {
                        log.warn("Value for key '{}' is not valid JSON, ignoring it. Error: {}", JSON_OVERRIDE_KEY, e.getMessage());
                        // Decide how to handle invalid JSON: ignore, throw, etc. Ignoring for now.
                    }
                } else {
                     log.warn("Value for key '{}' is not a non-blank String, ignoring it.", JSON_OVERRIDE_KEY);
                 }
            }

            // 3. Merge JSON overrides (overlay)
            if (jsonOverrides != null) {
                processedParams.putAll(jsonOverrides); // Values from jsonOverrides will overwrite existing keys
                log.debug("Merged {} keys from '{}' into config params.", jsonOverrides.size(), JSON_OVERRIDE_KEY);
            }
        }

        this.params = processedParams;
    }

    @Override
    public Map<String, Object> toMap() {
        // Use the internal objectMapper for potential type conversions if needed,
        // although direct map copy is usually fine here.
        // For consistency, round-tripping through the mapper ensures correct types.
        try {
             String json = this.toJson(); // Use internal mapper
             return this.objectMapper.readValue(json, MAP_TYPE_REFERENCE); // Use internal mapper
         } catch (IOException e) {
            log.error("Failed to convert internal params to map via JSON round-trip", e);
            // Fallback to direct copy, but this indicates an issue
             return new HashMap<>(this.params);
        }
    }

    // Optional: Provide direct access to the underlying map if needed,
    // although interacting via the interface methods is preferred.
    public Map<String, Object> getParams() {
        // Return a defensive copy
        return new HashMap<>(this.params);
    }

    // Optional: Provide access to the internal ObjectMapper if needed by subclasses
    protected ObjectMapper getObjectMapper() {
        return this.objectMapper;
    }
}