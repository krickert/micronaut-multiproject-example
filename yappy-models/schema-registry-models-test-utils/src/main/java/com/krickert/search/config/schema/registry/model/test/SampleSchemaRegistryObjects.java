package com.krickert.search.config.schema.registry.model.test;

import com.krickert.search.config.schema.registry.model.*;

import java.time.Instant;

/**
 * Provides sample schema registry objects.
 * These objects can be used for testing.
 */
public class SampleSchemaRegistryObjects {

    /**
     * Creates a minimal SchemaRegistryArtifact.
     */
    public static SchemaRegistryArtifact createMinimalSchemaRegistryArtifact() {
        return new SchemaRegistryArtifact(
                "test-schema",
                null,
                SchemaType.JSON_SCHEMA,
                Instant.parse("2023-01-01T00:00:00Z"),
                Instant.parse("2023-01-01T00:00:00Z"),
                null
        );
    }

    /**
     * Creates a comprehensive SchemaRegistryArtifact with all fields populated.
     */
    public static SchemaRegistryArtifact createComprehensiveSchemaRegistryArtifact() {
        return new SchemaRegistryArtifact(
                "comprehensive-schema",
                "A comprehensive schema for testing",
                SchemaType.AVRO,
                Instant.parse("2023-01-01T00:00:00Z"),
                Instant.parse("2023-02-01T00:00:00Z"),
                3
        );
    }

    /**
     * Creates a minimal SchemaVersionData.
     */
    public static SchemaVersionData createMinimalSchemaVersionData() {
        return new SchemaVersionData(
                null,
                "test-schema",
                1,
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
                SchemaType.JSON_SCHEMA,
                null,
                Instant.parse("2023-01-01T00:00:00Z"),
                null
        );
    }

    /**
     * Creates a comprehensive SchemaVersionData with all fields populated.
     */
    public static SchemaVersionData createComprehensiveSchemaVersionData() {
        return new SchemaVersionData(
                123456L,
                "comprehensive-schema",
                2,
                "{\"type\":\"record\",\"name\":\"TestRecord\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}",
                SchemaType.AVRO,
                SchemaCompatibility.BACKWARD,
                Instant.parse("2023-02-01T00:00:00Z"),
                "Version 2 with additional fields"
        );
    }

    /**
     * Creates a SchemaVersionData with JSON Schema type.
     */
    public static SchemaVersionData createJsonSchemaTypeVersionData() {
        return new SchemaVersionData(
                111L,
                "json-schema",
                1,
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"id\",\"name\"]}",
                SchemaType.JSON_SCHEMA,
                SchemaCompatibility.FULL,
                Instant.parse("2023-01-15T00:00:00Z"),
                "JSON Schema example"
        );
    }

    /**
     * Creates a SchemaVersionData with Avro schema type.
     */
    public static SchemaVersionData createAvroSchemaTypeVersionData() {
        return new SchemaVersionData(
                222L,
                "avro-schema",
                1,
                "{\"type\":\"record\",\"name\":\"User\",\"namespace\":\"com.example\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}",
                SchemaType.AVRO,
                SchemaCompatibility.BACKWARD,
                Instant.parse("2023-01-20T00:00:00Z"),
                "Avro Schema example"
        );
    }

    /**
     * Creates a SchemaVersionData with Protobuf schema type.
     */
    public static SchemaVersionData createProtobufSchemaTypeVersionData() {
        return new SchemaVersionData(
                333L,
                "protobuf-schema",
                1,
                "syntax = \"proto3\";\n\npackage com.example;\n\nmessage User {\n  int64 id = 1;\n  string name = 2;\n  optional string email = 3;\n}",
                SchemaType.PROTOBUF,
                SchemaCompatibility.FORWARD,
                Instant.parse("2023-01-25T00:00:00Z"),
                "Protobuf Schema example"
        );
    }

    /**
     * Creates a SchemaVersionData with BACKWARD compatibility.
     */
    public static SchemaVersionData createBackwardCompatibilitySchemaVersionData() {
        return new SchemaVersionData(
                444L,
                "backward-compatibility-schema",
                1,
                "{\"type\":\"record\",\"name\":\"BackwardCompatible\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}",
                SchemaType.AVRO,
                SchemaCompatibility.BACKWARD,
                Instant.parse("2023-03-01T00:00:00Z"),
                "Schema with BACKWARD compatibility"
        );
    }

    /**
     * Creates a SchemaVersionData with FORWARD compatibility.
     */
    public static SchemaVersionData createForwardCompatibilitySchemaVersionData() {
        return new SchemaVersionData(
                555L,
                "forward-compatibility-schema",
                1,
                "{\"type\":\"record\",\"name\":\"ForwardCompatible\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}",
                SchemaType.AVRO,
                SchemaCompatibility.FORWARD,
                Instant.parse("2023-03-05T00:00:00Z"),
                "Schema with FORWARD compatibility"
        );
    }

    /**
     * Creates a SchemaVersionData with FULL compatibility.
     */
    public static SchemaVersionData createFullCompatibilitySchemaVersionData() {
        return new SchemaVersionData(
                666L,
                "full-compatibility-schema",
                1,
                "{\"type\":\"record\",\"name\":\"FullCompatible\",\"fields\":[{\"name\":\"id\",\"type\":\"long\"},{\"name\":\"name\",\"type\":\"string\"}]}",
                SchemaType.AVRO,
                SchemaCompatibility.FULL,
                Instant.parse("2023-03-10T00:00:00Z"),
                "Schema with FULL compatibility"
        );
    }
}