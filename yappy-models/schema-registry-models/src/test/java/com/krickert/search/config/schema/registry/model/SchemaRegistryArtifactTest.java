package com.krickert.search.config.schema.registry.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("test-subject", deserialized.getSubject());
        assertEquals("Test description", deserialized.getDescription());
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.getSchemaType());

        // Compare Instants by checking if they're within 1 second of each other
        // This accounts for potential millisecond precision differences in serialization/deserialization
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.getCreatedAt().toEpochMilli()) < 1000);
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.getUpdatedAt().toEpochMilli()) < 1000);

        assertEquals(123, deserialized.getLatestVersionNumber());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a SchemaRegistryArtifact instance with null values
        SchemaRegistryArtifact artifact = new SchemaRegistryArtifact(
                null,
                null,
                null,
                null,
                null,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(artifact);

        // Deserialize from JSON
        SchemaRegistryArtifact deserialized = objectMapper.readValue(json, SchemaRegistryArtifact.class);

        // Verify the values
        assertNull(deserialized.getSubject());
        assertNull(deserialized.getDescription());
        // Note: schemaType has a default value of JSON_SCHEMA, so it won't be null
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.getSchemaType());
        assertNull(deserialized.getCreatedAt());
        assertNull(deserialized.getUpdatedAt());
        assertNull(deserialized.getLatestVersionNumber());
    }

    @Test
    void testDefaultSchemaType() throws Exception {
        // Create a SchemaRegistryArtifact instance with default schema type
        SchemaRegistryArtifact artifact = new SchemaRegistryArtifact();

        // Verify the default value
        assertEquals(SchemaType.JSON_SCHEMA, artifact.getSchemaType());
    }

    @Test
    void testEnumSerialization() throws Exception {
        // Create artifacts with different schema types
        SchemaRegistryArtifact artifact1 = new SchemaRegistryArtifact();
        artifact1.setSchemaType(SchemaType.AVRO);

        SchemaRegistryArtifact artifact2 = new SchemaRegistryArtifact();
        artifact2.setSchemaType(SchemaType.PROTOBUF);

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
        assertEquals(SchemaType.AVRO, deserialized1.getSchemaType());
        assertEquals(SchemaType.PROTOBUF, deserialized2.getSchemaType());
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/schema-registry-artifact.json")) {
            // Deserialize from JSON
            SchemaRegistryArtifact artifact = objectMapper.readValue(is, SchemaRegistryArtifact.class);

            // Verify the values
            assertEquals("test-artifact", artifact.getSubject());
            assertEquals("A test schema registry artifact", artifact.getDescription());
            assertEquals(SchemaType.JSON_SCHEMA, artifact.getSchemaType());
            assertEquals(5, artifact.getLatestVersionNumber());

            // Verify dates - parse expected dates
            Instant expectedCreatedAt = Instant.parse("2023-05-01T12:34:56.789Z");
            Instant expectedUpdatedAt = Instant.parse("2023-05-02T12:34:56.789Z");

            // Compare timestamps (allowing for small differences in precision)
            assertTrue(Math.abs(expectedCreatedAt.toEpochMilli() - artifact.getCreatedAt().toEpochMilli()) < 1000);
            assertTrue(Math.abs(expectedUpdatedAt.toEpochMilli() - artifact.getUpdatedAt().toEpochMilli()) < 1000);
        }
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed");
        }
    }
}
