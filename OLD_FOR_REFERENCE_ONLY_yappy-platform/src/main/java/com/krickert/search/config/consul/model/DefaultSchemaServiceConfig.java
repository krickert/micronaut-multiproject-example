package com.krickert.search.config.consul.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Default implementation of SchemaServiceConfig that provides basic JSON schema validation
 * and serialization capabilities. This implementation uses a simple JSON schema and
 * supports serialization/deserialization of a Map of key-value pairs.
 */
@Introspected
@Serdeable
public class DefaultSchemaServiceConfig implements SchemaServiceConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSchemaServiceConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    private static final SchemaValidatorsConfig SCHEMA_VALIDATORS_CONFIG = new SchemaValidatorsConfig();

    static {
        SCHEMA_VALIDATORS_CONFIG.setHandleNullableField(true);
    }

    private Map<String, Object> configMap = new HashMap<>();
    private String validationErrors;
    private String jsonSchema;

    /**
     * Default constructor that initializes with an empty schema.
     */
    public DefaultSchemaServiceConfig() {
        // Create a simple default schema for a generic key-value map
        ObjectNode schemaNode = OBJECT_MAPPER.createObjectNode();
        schemaNode.put("type", "object");
        schemaNode.put("additionalProperties", true);

        try {
            this.jsonSchema = OBJECT_MAPPER.writeValueAsString(schemaNode);
        } catch (JsonProcessingException e) {
            LOG.error("Error creating default JSON schema", e);
            this.jsonSchema = "{}";
        }
    }

    /**
     * Constructor with a specific JSON schema.
     *
     * @param jsonSchema the JSON schema to use for validation
     */
    public DefaultSchemaServiceConfig(String jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    /**
     * Constructor with initial configuration and schema.
     *
     * @param jsonSchema the JSON schema to use for validation
     * @param jsonConfig the initial JSON configuration
     */
    public DefaultSchemaServiceConfig(String jsonSchema, String jsonConfig) {
        this.jsonSchema = jsonSchema;
        deserializeConfig(jsonConfig);
    }

    /**
     * Constructor with initial configuration map.
     *
     * @param configMap the initial configuration map
     */
    public DefaultSchemaServiceConfig(Map<String, Object> configMap) {
        this();
        this.configMap = new HashMap<>(configMap);
    }

    @Override
    @NonNull
    public String getJsonSchema() {
        return jsonSchema;
    }

    /**
     * Sets the JSON schema for this configuration.
     *
     * @param jsonSchema the JSON schema to set
     */
    public void setJsonSchema(String jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    @Override
    public boolean validateConfig(@NonNull String jsonConfig) {
        try {
            // Parse the JSON config
            JsonNode configNode = OBJECT_MAPPER.readTree(jsonConfig);

            // Check if it's a valid JSON object
            if (!configNode.isObject()) {
                validationErrors = "Configuration must be a JSON object";
                return false;
            }

            // If no schema is defined, validation passes
            if (jsonSchema == null || jsonSchema.isEmpty() || jsonSchema.equals("{}")) {
                validationErrors = null;
                return true;
            }

            try {
                // Create a JsonSchema instance from the schema string
                JsonSchema schema = SCHEMA_FACTORY.getSchema(jsonSchema, SCHEMA_VALIDATORS_CONFIG);

                // Validate the config against the schema
                Set<ValidationMessage> validationMessageSet = schema.validate(configNode);

                if (validationMessageSet.isEmpty()) {
                    // Validation passed
                    validationErrors = null;
                    return true;
                } else {
                    // Validation failed, collect error messages
                    validationErrors = validationMessageSet.stream()
                            .map(ValidationMessage::getMessage)
                            .collect(Collectors.joining("; "));
                    LOG.debug("Schema validation failed: {}", validationErrors);
                    return false;
                }
            } catch (Exception e) {
                LOG.warn("Error during schema validation: {}", e.getMessage());
                validationErrors = "Schema validation error: " + e.getMessage();
                return false;
            }
        } catch (JsonProcessingException e) {
            validationErrors = "Invalid JSON: " + e.getMessage();
            LOG.error("Error validating JSON config", e);
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
            return "{}";
        }
    }

    @Override
    public boolean deserializeConfig(@NonNull String jsonConfig) {
        if (!validateConfig(jsonConfig)) {
            return false;
        }

        try {
            // Convert JSON to Map
            this.configMap = OBJECT_MAPPER.readValue(jsonConfig, 
                OBJECT_MAPPER.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class));
            return true;
        } catch (JsonProcessingException e) {
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

    /**
     * Gets the configuration as a Map.
     *
     * @return the configuration map
     */
    public Map<String, Object> getConfigMap() {
        return configMap;
    }

    /**
     * Sets the configuration map.
     *
     * @param configMap the configuration map to set
     */
    public void setConfigMap(Map<String, Object> configMap) {
        this.configMap = new HashMap<>(configMap);
    }

    /**
     * Gets a configuration value by key.
     *
     * @param key the configuration key
     * @return the configuration value, or null if not found
     */
    @Nullable
    public Object getConfigValue(String key) {
        return configMap.get(key);
    }

    /**
     * Sets a configuration value.
     *
     * @param key the configuration key
     * @param value the configuration value
     */
    public void setConfigValue(String key, Object value) {
        configMap.put(key, value);
    }
}
