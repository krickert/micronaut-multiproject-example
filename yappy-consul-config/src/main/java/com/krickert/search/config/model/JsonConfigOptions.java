package com.krickert.search.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import lombok.*;

import java.io.IOException;

/**
 * Configuration options for pipeline services that support JSON configuration.
 * This class replaces PipestepConfigOptions and adds support for JSON schema validation
 * and serialization through the SchemaServiceConfig interface.
 */
@Data
@AllArgsConstructor
@Introspected
@Serdeable
public class JsonConfigOptions {
    /**
     * The JSON configuration as a string.
     */
    private String jsonConfig;

    /**
     * The schema service configuration implementation.
     * This is excluded from serialization as it's created on demand.
     */
    @JsonIgnore
    @Nullable
    private SchemaServiceConfig schemaServiceConfig;

    /**
     * Gets the schema service configuration.
     * If it doesn't exist, creates a new SimpleSchemaServiceConfig.
     *
     * @return the schema service configuration
     */
    public SchemaServiceConfig getSchemaServiceConfig() {
        if (schemaServiceConfig == null) {
            schemaServiceConfig = new DefaultSchemaServiceConfig(jsonConfig);
        }
        return schemaServiceConfig;
    }

    /**
     * Default constructor.
     */
    public JsonConfigOptions() {
        this.jsonConfig = "{}";
    }

    /**
     * Constructor with JSON configuration.
     *
     * @param jsonConfig the JSON configuration
     */
    public JsonConfigOptions(String jsonConfig) {
        this.jsonConfig = jsonConfig;
    }

    /**
     * Constructor with JSON configuration and schema.
     *
     * @param jsonConfig the JSON configuration
     * @param jsonSchema the JSON schema
     */
    public JsonConfigOptions(String jsonConfig, String jsonSchema) {
        this.jsonConfig = jsonConfig;
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

    /**
     * Deserializes the JSON configuration into a PipelineClusterConfig object.
     *
     * @param objectMapper the object mapper to use for deserialization
     * @return the deserialized PipelineClusterConfig object, or null if deserialization fails
     */
    @Nullable
    public PipelineClusterConfig toPipelineClusterConfig(ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(jsonConfig, PipelineClusterConfig.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Serializes a PipelineClusterConfig object into a JSON string and updates this object's JSON configuration.
     *
     * @param pipelineClusterConfig the PipelineClusterConfig object to serialize
     * @param objectMapper the object mapper to use for serialization
     * @return true if serialization was successful, false otherwise
     */
    public boolean fromPipelineClusterConfig(PipelineClusterConfig pipelineClusterConfig, ObjectMapper objectMapper) {
        try {
            this.jsonConfig = objectMapper.writeValueAsString(pipelineClusterConfig);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
