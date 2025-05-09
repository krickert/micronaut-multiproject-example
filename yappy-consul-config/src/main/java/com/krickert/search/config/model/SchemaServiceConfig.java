package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import java.util.Map;

/**
 * Interface for schema validation and serialization of service configuration.
 * This interface defines methods for validating, serializing, and deserializing
 * JSON configuration for pipeline services.
 */
@Introspected
public interface SchemaServiceConfig {

    /**
     * Gets the JSON schema definition used by this configuration instance.
     * The schema should follow a standard format that makes it easy for front-end forms creation.
     *
     * @return the JSON schema as a String
     */
    @NonNull
    String getJsonSchema();

    /**
     * Validates the provided JSON configuration string against the instance's schema.
     *
     * @param jsonConfig the JSON configuration to validate
     * @return true if the configuration is valid according to the schema, false otherwise
     */
    boolean validateConfig(@NonNull String jsonConfig);

    /**
     * Serializes the internal configuration map to a JSON string.
     *
     * @return the configuration as a JSON string
     */
    @NonNull
    String serializeConfig();

    /**
     * Deserializes the JSON configuration string into an internal map representation.
     * This typically includes validation against the instance's schema.
     *
     * @param jsonConfig the JSON configuration to deserialize
     * @return true if deserialization and validation were successful, false otherwise
     */
    boolean deserializeConfig(@NonNull String jsonConfig);

    /**
     * Gets validation errors from the last validation or deserialization attempt.
     *
     * @return a string containing validation errors, or null if there are no errors
     */
    @Nullable
    String getValidationErrors();

    /**
     * Gets the configuration data as a map.
     *
     * @return A map representing the configuration data.
     */
    @NonNull
    Map<String, Object> getConfigMap();
}