package com.krickert.search.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor; // For default constructor

import java.util.Map;
// NoArgsConstructor will be used by Jackson/Serde if present
// AllArgsConstructor might be removed if specific constructors are preferred.

/**
 * Configuration options for pipeline step's custom JSON configuration.
 * This class facilitates JSON schema validation and serialization for a step's specific parameters.
 */
@Data
@Introspected
@Serdeable
@NoArgsConstructor // Needed for deserialization
public class JsonConfigOptions {

    /**
     * The JSON configuration as a string for this specific step.
     */
    private String jsonConfig = "{}"; // Default to empty JSON object

    /**
     * The schema service configuration implementation.
     * This is not serialized itself but is used to manage the jsonConfig.
     * It should be initialized with the specific schema for the module this config is for.
     */
    @JsonIgnore
    @Nullable
    private transient SchemaServiceConfig schemaServiceConfig; // Mark transient if appropriate for your serialization strategy if not JsonIgnore

    /**
     * Constructor for creating JsonConfigOptions with specific configuration data and its corresponding schema.
     *
     * @param jsonConfigData The JSON string containing the custom configuration for the pipeline step.
     * @param moduleSchema   The JSON schema string specific to the module this configuration is for.
     * If null or empty, a default permissive schema will be used by DefaultSchemaServiceConfig.
     */
    public JsonConfigOptions(@Nullable String jsonConfigData, @Nullable String moduleSchema) {
        this.jsonConfig = (jsonConfigData == null || jsonConfigData.trim().isEmpty()) ? "{}" : jsonConfigData;
        // Initialize schemaServiceConfig immediately with the provided schema and data
        this.schemaServiceConfig = new DefaultSchemaServiceConfig(moduleSchema, this.jsonConfig);
        // If schema validation failed during DefaultSchemaServiceConfig construction, 
        // jsonConfig might be in an unvalidated state or configMap empty.
        // Ensure that if validation fails, jsonConfig is perhaps reset or marked invalid.
        if (this.schemaServiceConfig.getValidationErrors() != null) {
            // Log or handle initial validation failure. The config is held, but marked as invalid.
        } else {
             // If valid and deserialized, update jsonConfig to reflect the canonical form from configMap
            this.jsonConfig = this.schemaServiceConfig.serializeConfig();
        }
    }
    
    /**
     * Ensures the schema service config is available, primarily for internal use after construction.
     * It's recommended to use the constructor that accepts a schema for proper initialization.
     * If called when schemaServiceConfig is null (e.g. after default construction by a deserializer),
     * it will initialize with the current jsonConfig and a default permissive schema.
     *
     * @return The schema service configuration.
     */
    @JsonIgnore // To avoid issues with serialization trying to get this during construction
    public SchemaServiceConfig getSchemaServiceConfig() {
        if (schemaServiceConfig == null) {
            // This case typically occurs if the object was created via the default constructor
            // (e.g., by a deserializer) and not the schema-aware one.
            // It will use a default permissive schema.
            this.schemaServiceConfig = new DefaultSchemaServiceConfig(this.jsonConfig);
        }
        return schemaServiceConfig;
    }

    /**
     * Sets the schema service configuration, typically used if the schema needs to be applied
     * after default construction. Also updates the internal jsonConfig based on the provided service.
     *
     * @param schemaServiceConfig the schema service configuration to set.
     */
    public void setSchemaServiceConfig(@NonNull SchemaServiceConfig schemaServiceConfig) {
        this.schemaServiceConfig = schemaServiceConfig;
        // Attempt to deserialize and validate the current jsonConfig with the new schema
        if (this.schemaServiceConfig.deserializeConfig(this.jsonConfig)) {
             // Update jsonConfig to the serialized form from the (potentially now validated and parsed) map
            this.jsonConfig = this.schemaServiceConfig.serializeConfig();
        } else {
            // If deserialization/validation fails with the new schema, jsonConfig remains as is,
            // but validation errors will be available.
        }
    }
    
    /**
     * Validates the current JSON configuration against the associated schema.
     *
     * @return true if the configuration is valid, false otherwise.
     */
    public boolean validateConfig() {
        return getSchemaServiceConfig().validateConfig(this.jsonConfig);
    }

    /**
     * Gets validation errors from the last validation attempt.
     *
     * @return a string containing validation errors, or null if there are no errors.
     */
    @Nullable
    public String getValidationErrors() {
        return getSchemaServiceConfig().getValidationErrors();
    }

    /**
     * Gets the underlying configuration map from the schema service.
     *
     * @return A map representing the configuration, or an empty map if not properly deserialized.
     */
    @JsonIgnore
    public Map<String, Object> getConfigMap() {
        return getSchemaServiceConfig().getConfigMap();
    }

    /**
     * Sets the JSON configuration string.
     * Note: This will re-initialize the internal SchemaServiceConfig with the new JSON data,
     * potentially using a default permissive schema if a specific module schema isn't re-applied.
     * It's generally better to create a new JsonConfigOptions instance if the schema context changes.
     *
     * @param jsonConfig The new JSON configuration string.
     */
    public void setJsonConfig(String jsonConfig) {
        this.jsonConfig = (jsonConfig == null || jsonConfig.trim().isEmpty()) ? "{}" : jsonConfig;
        // Re-initialize or update schemaServiceConfig. If schemaServiceConfig was set with a specific schema,
        // that schema should ideally be reused.
        if (this.schemaServiceConfig != null) {
            // If schemaServiceConfig exists, try to deserialize the new jsonConfig with the existing schema.
            if (!this.schemaServiceConfig.deserializeConfig(this.jsonConfig)) {
                // jsonConfig is updated, but it's invalid according to the existing schema.
                // Validation errors are available via getValidationErrors().
            } else {
                // Valid and deserialized. Reflect canonical form.
                 this.jsonConfig = this.schemaServiceConfig.serializeConfig();
            }
        } else {
            // If no schemaServiceConfig, it will be created on demand by getSchemaServiceConfig()
            // with a default permissive schema.
            // To be safe, explicitly re-initialize here if that's the desired behavior.
            this.schemaServiceConfig = new DefaultSchemaServiceConfig(this.jsonConfig); // Uses default schema
        }
    }
}