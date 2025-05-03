package com.krickert.search.config.consul.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * Interface for schema validation and serialization of service configuration.
 * This interface defines methods for validating, serializing, and deserializing
 * JSON configuration for pipeline services.
 */
@Introspected
public interface SchemaServiceConfig {

    /**
     * Gets the JSON schema definition for this configuration.
     * The schema should follow a standard format that makes it easy for front-end forms creation.
     *
     * @return the JSON schema as a String
     */
    @NonNull
    String getJsonSchema();

    /**
     * Validates the provided JSON configuration against the schema.
     *
     * @param jsonConfig the JSON configuration to validate
     * @return true if the configuration is valid, false otherwise
     */
    boolean validateConfig(@NonNull String jsonConfig);

    /**
     * Serializes the configuration to JSON.
     *
     * @return the configuration as a JSON string
     */
    @NonNull
    String serializeConfig();

    /**
     * Deserializes the JSON configuration.
     *
     * @param jsonConfig the JSON configuration to deserialize
     * @return true if deserialization was successful, false otherwise
     */
    boolean deserializeConfig(@NonNull String jsonConfig);

    /**
     * Gets validation errors from the last validation attempt.
     *
     * @return a string containing validation errors, or null if there are no errors
     */
    @Nullable
    String getValidationErrors();
}
