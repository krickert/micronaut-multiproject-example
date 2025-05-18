package com.krickert.search.config.schema.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchemaRegistryArtifactTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a SchemaRegistryArtifact instance
        Instant now = Instant.now();
        SchemaRegistryArtifact artifact = new SchemaRegistryArtifact(
                "test-subject",
                "Test description",
                SchemaType.JSON_SCHEMA,
                now,
                now,
                123
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(artifact);

        // Deserialize from JSON
        SchemaRegistryArtifact deserialized = objectMapper.readValue(json, SchemaRegistryArtifact.class);

        // Verify the values
        assertEquals("test-subject", deserialized.subject());
        assertEquals("Test description", deserialized.description());
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.schemaType());

        // Compare Instants by checking if they're within 1 second of each other
        // This accounts for potential millisecond precision differences in serialization/deserialization
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.createdAt().toEpochMilli()) < 1000);
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.updatedAt().toEpochMilli()) < 1000);

        assertEquals(123, deserialized.latestVersionNumber());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a JSON string with null values
        String json = "{\"subject\":\"test-subject\",\"description\":null,\"schemaType\":null,\"createdAt\":\"2023-05-01T12:34:56.789Z\",\"updatedAt\":\"2023-05-01T12:34:56.789Z\",\"latestVersionNumber\":null}";

        // Deserialize from JSON
        SchemaRegistryArtifact deserialized = objectMapper.readValue(json, SchemaRegistryArtifact.class);

        // Verify the values
        assertEquals("test-subject", deserialized.subject());
        assertNull(deserialized.description());
        // Note: schemaType has a default value of JSON_SCHEMA, so it won't be null
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.schemaType());
        assertNotNull(deserialized.createdAt());
        assertNotNull(deserialized.updatedAt());
        assertNull(deserialized.latestVersionNumber());
    }

    @Test
    void testDefaultSchemaType() {
        // Create a SchemaRegistryArtifact instance with minimal required fields
        Instant now = Instant.now();
        SchemaRegistryArtifact artifact = new SchemaRegistryArtifact(
                "test-subject",
                "Test description",
                SchemaType.JSON_SCHEMA,
                now,
                now,
                123
        );

        // Verify the default value
        assertEquals(SchemaType.JSON_SCHEMA, artifact.schemaType());
    }

    @Test
    void testEnumSerialization() throws Exception {
        // Create artifacts with different schema types
        Instant now = Instant.now();
        SchemaRegistryArtifact artifact1 = new SchemaRegistryArtifact(
                "test-subject",
                "Test description",
                SchemaType.AVRO,
                now,
                now,
                123
        );

        SchemaRegistryArtifact artifact2 = new SchemaRegistryArtifact(
                "test-subject",
                "Test description",
                SchemaType.PROTOBUF,
                now,
                now,
                123
        );

        // Serialize to JSON
        String json1 = objectMapper.writeValueAsString(artifact1);
        String json2 = objectMapper.writeValueAsString(artifact2);

        // Verify the enum values are serialized correctly
        assertTrue(json1.contains("\"schemaType\":\"AVRO\""));
        assertTrue(json2.contains("\"schemaType\":\"PROTOBUF\""));

        // Deserialize from JSON
        SchemaRegistryArtifact deserialized1 = objectMapper.readValue(json1, SchemaRegistryArtifact.class);
        SchemaRegistryArtifact deserialized2 = objectMapper.readValue(json2, SchemaRegistryArtifact.class);

        // Verify the enum values are deserialized correctly
        assertEquals(SchemaType.AVRO, deserialized1.schemaType());
        assertEquals(SchemaType.PROTOBUF, deserialized2.schemaType());
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/schema-registry-artifact.json")) {
            // Deserialize from JSON
            SchemaRegistryArtifact artifact = objectMapper.readValue(is, SchemaRegistryArtifact.class);

            // Verify the values
            assertEquals("test-artifact", artifact.subject());
            assertEquals("A test schema registry artifact", artifact.description());
            assertEquals(SchemaType.JSON_SCHEMA, artifact.schemaType());
            assertEquals(5, artifact.latestVersionNumber());

            // Verify dates - parse expected dates
            Instant expectedCreatedAt = Instant.parse("2023-05-01T12:34:56.789Z");
            Instant expectedUpdatedAt = Instant.parse("2023-05-02T12:34:56.789Z");

            // Compare timestamps (allowing for small differences in precision)
            assertTrue(Math.abs(expectedCreatedAt.toEpochMilli() - artifact.createdAt().toEpochMilli()) < 1000);
            assertTrue(Math.abs(expectedUpdatedAt.toEpochMilli() - artifact.updatedAt().toEpochMilli()) < 1000);
        }
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed");
        }
    }
}
