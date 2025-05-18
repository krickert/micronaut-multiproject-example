package com.krickert.search.config.schema.model.test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides sample JSON for schema registry model classes.
 * Includes both minimal and comprehensive examples, as well as edge cases.
 */
public class SampleSchemaRegistryJson {

    private static final Map<String, String> JSON_CACHE = new HashMap<>();

    /**
     * Returns a minimal valid SchemaRegistryArtifact JSON.
     */
    public static String getMinimalSchemaRegistryArtifactJson() {
        return getJsonResource("minimal-schema-registry-artifact.json");
    }

    /**
     * Returns a comprehensive SchemaRegistryArtifact JSON.
     */
    public static String getComprehensiveSchemaRegistryArtifactJson() {
        return getJsonResource("comprehensive-schema-registry-artifact.json");
    }

    /**
     * Returns a minimal valid SchemaVersionData JSON.
     */
    public static String getMinimalSchemaVersionDataJson() {
        return getJsonResource("minimal-schema-version-data.json");
    }

    /**
     * Returns a comprehensive SchemaVersionData JSON.
     */
    public static String getComprehensiveSchemaVersionDataJson() {
        return getJsonResource("comprehensive-schema-version-data.json");
    }

    /**
     * Returns a JSON for a JSON Schema type schema.
     */
    public static String getJsonSchemaTypeJson() {
        return getJsonResource("json-schema-type.json");
    }

    /**
     * Returns a JSON for an Avro schema type.
     */
    public static String getAvroSchemaTypeJson() {
        return getJsonResource("avro-schema-type.json");
    }

    /**
     * Returns a JSON for a Protobuf schema type.
     */
    public static String getProtobufSchemaTypeJson() {
        return getJsonResource("protobuf-schema-type.json");
    }

    /**
     * Returns a JSON for a schema with BACKWARD compatibility.
     */
    public static String getBackwardCompatibilitySchemaJson() {
        return getJsonResource("backward-compatibility-schema.json");
    }

    /**
     * Returns a JSON for a schema with FORWARD compatibility.
     */
    public static String getForwardCompatibilitySchemaJson() {
        return getJsonResource("forward-compatibility-schema.json");
    }

    /**
     * Returns a JSON for a schema with FULL compatibility.
     */
    public static String getFullCompatibilitySchemaJson() {
        return getJsonResource("full-compatibility-schema.json");
    }

    /**
     * Returns a JSON resource as a string.
     * Resources are loaded from the classpath and cached for performance.
     */
    private static String getJsonResource(String resourceName) {
        return JSON_CACHE.computeIfAbsent(resourceName, name -> {
            try (InputStream is = SampleSchemaRegistryJson.class.getResourceAsStream("/json/" + name)) {
                if (is == null) {
                    throw new IllegalArgumentException("Resource not found: " + name);
                }
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException("Failed to load JSON resource: " + name, e);
            }
        });
    }
}