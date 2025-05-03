package com.krickert.search.config.consul.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration options for pipeline services that support JSON configuration.
 * This class replaces PipestepConfigOptions and adds support for JSON schema validation
 * and serialization through the SchemaServiceConfig interface.
 */
@Introspected
@Serdeable
@Getter
@Setter
public class JsonConfigOptions {
    /**
     * The JSON configuration as a string.
     */
    private String jsonConfig;

    /**
     * The JSON schema as a string.
     */
    private String jsonSchema;

    /**
     * The schema service configuration implementation.
     * This is excluded from serialization as it's created on demand.
     */
    @JsonIgnore
    @Nullable
    private SchemaServiceConfig schemaServiceConfig;

    /**
     * Default constructor.
     */
    public JsonConfigOptions() {
        this.jsonConfig = "{}";
        this.jsonSchema = "{}";
    }

    /**
     * Constructor with JSON configuration.
     *
     * @param jsonConfig the JSON configuration
     */
    public JsonConfigOptions(String jsonConfig) {
        this.jsonConfig = jsonConfig;
        this.jsonSchema = "{}";
    }

    /**
     * Constructor with JSON configuration and schema.
     *
     * @param jsonConfig the JSON configuration
     * @param jsonSchema the JSON schema
     */
    public JsonConfigOptions(String jsonConfig, String jsonSchema) {
        this.jsonConfig = jsonConfig;
        this.jsonSchema = jsonSchema;
    }

    /**
     * Gets the schema service configuration.
     * If it doesn't exist, creates a new DefaultSchemaServiceConfig.
     *
     * @return the schema service configuration
     */
    public SchemaServiceConfig getSchemaServiceConfig() {
        if (schemaServiceConfig == null) {
            schemaServiceConfig = new DefaultSchemaServiceConfig(jsonSchema, jsonConfig);
        }
        return schemaServiceConfig;
    }

    /**
     * Sets the schema service configuration.
     * Updates the JSON configuration and schema from the provided configuration.
     *
     * @param schemaServiceConfig the schema service configuration to set
     */
    public void setSchemaServiceConfig(SchemaServiceConfig schemaServiceConfig) {
        this.schemaServiceConfig = schemaServiceConfig;
        this.jsonConfig = schemaServiceConfig.serializeConfig();
        this.jsonSchema = schemaServiceConfig.getJsonSchema();
    }

    /**
     * Validates the JSON configuration against the schema.
     *
     * @return true if the configuration is valid, false otherwise
     */
    public boolean validateConfig() {
        return getSchemaServiceConfig().validateConfig(jsonConfig);
    }

    /**
     * Gets validation errors from the last validation attempt.
     *
     * @return a string containing validation errors, or null if there are no errors
     */
    public String getValidationErrors() {
        return getSchemaServiceConfig().getValidationErrors();
    }
}
