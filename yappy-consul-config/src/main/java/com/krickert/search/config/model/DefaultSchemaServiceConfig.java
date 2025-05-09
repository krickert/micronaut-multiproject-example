package com.krickert.search.config.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of SchemaServiceConfig that provides basic JSON schema validation
 * and serialization capabilities.
 */
@Introspected
@Serdeable
public class DefaultSchemaServiceConfig implements SchemaServiceConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSchemaServiceConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private static final SchemaValidatorsConfig SCHEMA_VALIDATORS_CONFIG = new SchemaValidatorsConfig.Builder().build();

    static {
        SCHEMA_VALIDATORS_CONFIG.setHandleNullableField(true); // Example: handle nullable fields
    }

    @Getter
    private Map<String, Object> configMap = new HashMap<>();
    private String validationErrors;
    private String jsonSchemaString; // Stores the schema used for this instance

    /**
     * Default constructor that initializes with an empty, permissive schema.
     * This schema allows any JSON object with any properties.
     */
    public DefaultSchemaServiceConfig() {
        ObjectNode schemaNode = OBJECT_MAPPER.createObjectNode();
        schemaNode.put("type", "object");
        schemaNode.put("additionalProperties", true); // Permissive schema

        try {
            this.jsonSchemaString = OBJECT_MAPPER.writeValueAsString(schemaNode);
        } catch (JsonProcessingException e) {
            LOG.error("Error creating default JSON schema", e);
            this.jsonSchemaString = "{}"; // Fallback
        }
        // Initialize with an empty configMap, to be populated by deserializeConfig if needed
    }

    /**
     * Constructor with initial JSON configuration data.
     * Uses a default permissive schema for validation unless a specific schema is later provided
     * to validateConfig or if this config is part of a JsonConfigOptions initialized with a specific schema.
     * It attempts to deserialize the provided jsonConfig.
     *
     * @param jsonConfig the initial JSON configuration data
     */
    public DefaultSchemaServiceConfig(String jsonConfig) {
        this(); // Initialize with default permissive schema
        if (jsonConfig != null && !jsonConfig.isEmpty()) {
            deserializeConfig(jsonConfig); // Attempt to parse the provided config
        }
    }
    
    /**
     * Constructor with a specific JSON schema string and initial JSON configuration data.
     *
     * @param jsonSchemaString the JSON schema to use for validation
     * @param jsonConfig       the initial JSON configuration data (can be null or empty)
     */
    public DefaultSchemaServiceConfig(String jsonSchemaString, @Nullable String jsonConfig) {
        this.jsonSchemaString = (jsonSchemaString == null || jsonSchemaString.trim().isEmpty()) ? createDefaultPermissiveSchema() : jsonSchemaString;
        if (jsonConfig != null && !jsonConfig.isEmpty()) {
            // Validate first, then deserialize. Deserialize will also validate if not done before.
            if (validateConfig(jsonConfig)) {
                deserializeConfig(jsonConfig); // deserializeConfig also calls validateConfig, ensure no double validation if not needed.
            }
        }
    }

    private String createDefaultPermissiveSchema() {
        ObjectNode schemaNode = OBJECT_MAPPER.createObjectNode();
        schemaNode.put("type", "object");
        schemaNode.put("additionalProperties", true);
        try {
            return OBJECT_MAPPER.writeValueAsString(schemaNode);
        } catch (JsonProcessingException e) {
            LOG.error("Error creating default JSON schema", e);
            return "{}";
        }
    }


    @Override
    @NonNull
    public String getJsonSchema() {
        return jsonSchemaString;
    }

    @Override
    public boolean validateConfig(@NonNull String jsonConfigToValidate) {
        try {
            JsonNode configNode = OBJECT_MAPPER.readTree(jsonConfigToValidate);

            if (!configNode.isObject()) {
                validationErrors = "Configuration must be a JSON object";
                return false;
            }

            if (this.jsonSchemaString == null || this.jsonSchemaString.isEmpty() || this.jsonSchemaString.equals("{}")) {
                // If using a truly empty or default permissive schema, treat as valid for structure.
                // Specific content validation would rely on a proper schema.
                LOG.debug("No specific schema provided or schema is permissive; basic JSON object validation passed.");
                validationErrors = null;
                return true;
            }

            JsonSchema schema = SCHEMA_FACTORY.getSchema(this.jsonSchemaString, SCHEMA_VALIDATORS_CONFIG);
            Set<ValidationMessage> validationMessageSet = schema.validate(configNode);

            if (validationMessageSet.isEmpty()) {
                validationErrors = null;
                return true;
            } else {
                validationErrors = validationMessageSet.stream()
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
                LOG.debug("Schema validation failed: {}", validationErrors);
                return false;
            }
        } catch (JsonProcessingException e) {
            validationErrors = "Invalid JSON format: " + e.getMessage();
            LOG.error("Error parsing JSON config for validation", e);
            return false;
        } catch (Exception e) {
            LOG.warn("Error during schema validation: {}", e.getMessage(), e);
            validationErrors = "Schema validation error: " + e.getMessage();
            return false;
        }
    }

    @Override
    @NonNull
    public String serializeConfig() {
        try {
            return OBJECT_MAPPER.writeValueAsString(configMap);
        } catch (JsonProcessingException e) {
            LOG.error("Error serializing config to JSON", e);
            return "{}"; // Fallback
        }
    }

    @Override
    public boolean deserializeConfig(@NonNull String jsonConfigToDeserialize) {
        // It's crucial to validate before attempting to deserialize into the map
        // to ensure the configMap reflects a valid state according to the schema.
        if (!validateConfig(jsonConfigToDeserialize)) {
            // Validation errors are set by validateConfig
            return false;
        }

        try {
            this.configMap = OBJECT_MAPPER.readValue(jsonConfigToDeserialize,
                    OBJECT_MAPPER.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
            // Validation passed and deserialization successful
            return true;
        } catch (JsonProcessingException e) {
            // This catch might be redundant if validateConfig catches JSON format issues,
            // but good for safety if deserialize has stricter parsing.
            validationErrors = "Error deserializing JSON: " + e.getMessage();
            LOG.error("Error deserializing JSON config", e);
            return false;
        }
    }

    @Override
    @Nullable
    public String getValidationErrors() {
        return validationErrors;
    }

    public void setConfigMap(Map<String, Object> configMap) {
        this.configMap = new HashMap<>(configMap);
    }

    @Nullable
    public Object getConfigValue(String key) {
        return configMap.get(key);
    }

    public void setConfigValue(String key, Object value) {
        configMap.put(key, value);
    }
}