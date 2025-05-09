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

class SchemaVersionDataTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a SchemaVersionData instance
        Instant now = Instant.now();
        SchemaVersionData versionData = new SchemaVersionData(
                12345L,
                "test-subject",
                2,
                "{\"type\": \"object\"}",
                SchemaType.JSON_SCHEMA,
                SchemaCompatibility.BACKWARD,
                now,
                "Test version"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(versionData);

        // Deserialize from JSON
        SchemaVersionData deserialized = objectMapper.readValue(json, SchemaVersionData.class);

        // Verify the values
        assertEquals(12345L, deserialized.getGlobalId());
        assertEquals("test-subject", deserialized.getSubject());
        assertEquals(2, deserialized.getVersion());
        assertEquals("{\"type\": \"object\"}", deserialized.getSchemaContent());
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.getSchemaType());
        assertEquals(SchemaCompatibility.BACKWARD, deserialized.getCompatibility());

        // Compare Instants by checking if they're within 1 second of each other
        // This accounts for potential millisecond precision differences in serialization/deserialization
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.getCreatedAt().toEpochMilli()) < 1000);

        assertEquals("Test version", deserialized.getVersionDescription());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a SchemaVersionData instance with null values
        SchemaVersionData versionData = new SchemaVersionData(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(versionData);

        // Deserialize from JSON
        SchemaVersionData deserialized = objectMapper.readValue(json, SchemaVersionData.class);

        // Verify the values
        assertNull(deserialized.getGlobalId());
        assertNull(deserialized.getSubject());
        assertNull(deserialized.getVersion());
        assertNull(deserialized.getSchemaContent());
        // Note: schemaType has a default value of JSON_SCHEMA, so it won't be null
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.getSchemaType());
        assertNull(deserialized.getCompatibility());
        assertNull(deserialized.getCreatedAt());
        assertNull(deserialized.getVersionDescription());
    }

    @Test
    void testDefaultSchemaType() {
        // Create a SchemaVersionData instance with default schema type
        SchemaVersionData versionData = new SchemaVersionData();

        // Verify the default value
        assertEquals(SchemaType.JSON_SCHEMA, versionData.getSchemaType());
    }

    @Test
    void testEnumSerialization() throws Exception {
        // Create version data with different schema types and compatibility
        SchemaVersionData versionData1 = new SchemaVersionData();
        versionData1.setSchemaType(SchemaType.AVRO);
        versionData1.setCompatibility(SchemaCompatibility.FORWARD);

        SchemaVersionData versionData2 = new SchemaVersionData();
        versionData2.setSchemaType(SchemaType.PROTOBUF);
        versionData2.setCompatibility(SchemaCompatibility.FULL);

        // Serialize to JSON
        String json1 = objectMapper.writeValueAsString(versionData1);
        String json2 = objectMapper.writeValueAsString(versionData2);

        // Verify the enum values are serialized correctly
        assertTrue(json1.contains("\"schemaType\":\"AVRO\""));
        assertTrue(json1.contains("\"compatibility\":\"FORWARD\""));
        assertTrue(json2.contains("\"schemaType\":\"PROTOBUF\""));
        assertTrue(json2.contains("\"compatibility\":\"FULL\""));

        // Deserialize from JSON
        SchemaVersionData deserialized1 = objectMapper.readValue(json1, SchemaVersionData.class);
        SchemaVersionData deserialized2 = objectMapper.readValue(json2, SchemaVersionData.class);

        // Verify the enum values are deserialized correctly
        assertEquals(SchemaType.AVRO, deserialized1.getSchemaType());
        assertEquals(SchemaCompatibility.FORWARD, deserialized1.getCompatibility());
        assertEquals(SchemaType.PROTOBUF, deserialized2.getSchemaType());
        assertEquals(SchemaCompatibility.FULL, deserialized2.getCompatibility());
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/schema-version-data.json")) {
            // Deserialize from JSON
            SchemaVersionData versionData = objectMapper.readValue(is, SchemaVersionData.class);

            // Verify the values
            assertEquals(12345L, versionData.getGlobalId());
            assertEquals("test-artifact", versionData.getSubject());
            assertEquals(2, versionData.getVersion());
            assertEquals("{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}", versionData.getSchemaContent());
            assertEquals(SchemaType.JSON_SCHEMA, versionData.getSchemaType());
            assertEquals(SchemaCompatibility.BACKWARD, versionData.getCompatibility());
            assertEquals("Added name property", versionData.getVersionDescription());

            // Verify date - parse expected date
            Instant expectedCreatedAt = Instant.parse("2023-05-15T10:30:45.678Z");

            // Compare timestamp (allowing for small differences in precision)
            assertTrue(Math.abs(expectedCreatedAt.toEpochMilli() - versionData.getCreatedAt().toEpochMilli()) < 1000);
        }
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed");
        }
    }
}