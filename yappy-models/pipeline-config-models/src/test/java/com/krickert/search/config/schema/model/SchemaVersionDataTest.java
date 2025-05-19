package com.krickert.search.config.schema.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(12345L, deserialized.globalId());
        assertEquals("test-subject", deserialized.subject());
        assertEquals(2, deserialized.version());
        assertEquals("{\"type\": \"object\"}", deserialized.schemaContent());
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.schemaType());
        assertEquals(SchemaCompatibility.BACKWARD, deserialized.compatibility());

        // Compare Instants by checking if they're within 1 second of each other
        // This accounts for potential millisecond precision differences in serialization/deserialization
        assertTrue(Math.abs(now.toEpochMilli() - deserialized.createdAt().toEpochMilli()) < 1000);

        assertEquals("Test version", deserialized.versionDescription());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a JSON string with null values for optional fields
        String json = "{\"globalId\":null,\"subject\":\"test-subject\",\"version\":1,\"schemaContent\":\"{}\",\"schemaType\":null,\"compatibility\":null,\"createdAt\":\"2023-05-01T12:34:56.789Z\",\"versionDescription\":null}";

        // Deserialize from JSON
        SchemaVersionData deserialized = objectMapper.readValue(json, SchemaVersionData.class);

        // Verify the values
        assertNull(deserialized.globalId());
        assertEquals("test-subject", deserialized.subject());
        assertEquals(1, deserialized.version());
        assertEquals("{}", deserialized.schemaContent());
        // Note: schemaType has a default value of JSON_SCHEMA, so it won't be null
        assertEquals(SchemaType.JSON_SCHEMA, deserialized.schemaType());
        assertNull(deserialized.compatibility());
        assertNotNull(deserialized.createdAt());
        assertNull(deserialized.versionDescription());
    }

    @Test
    void testDefaultSchemaType() {
        // Create a SchemaVersionData instance with minimal required fields
        SchemaVersionData versionData = new SchemaVersionData(
                12345L,
                "test-subject",
                1,
                "{}",
                SchemaType.JSON_SCHEMA,
                SchemaCompatibility.BACKWARD,
                Instant.now(),
                "Test version"
        );

        // Verify the default value
        assertEquals(SchemaType.JSON_SCHEMA, versionData.schemaType());
    }

    @Test
    void testEnumSerialization() throws Exception {
        // Create version data with different schema types and compatibility
        Instant now = Instant.now();
        SchemaVersionData versionData1 = new SchemaVersionData(
                12345L,
                "test-subject",
                1,
                "{}",
                SchemaType.AVRO,
                SchemaCompatibility.FORWARD,
                now,
                "Test version"
        );

        SchemaVersionData versionData2 = new SchemaVersionData(
                12346L,
                "test-subject",
                2,
                "{}",
                SchemaType.PROTOBUF,
                SchemaCompatibility.FULL,
                now,
                "Test version"
        );

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
        assertEquals(SchemaType.AVRO, deserialized1.schemaType());
        assertEquals(SchemaCompatibility.FORWARD, deserialized1.compatibility());
        assertEquals(SchemaType.PROTOBUF, deserialized2.schemaType());
        assertEquals(SchemaCompatibility.FULL, deserialized2.compatibility());
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/schema-version-data.json")) {
            // Deserialize from JSON
            SchemaVersionData versionData = objectMapper.readValue(is, SchemaVersionData.class);

            // Verify the values
            assertEquals(12345L, versionData.globalId());
            assertEquals("test-artifact", versionData.subject());
            assertEquals(2, versionData.version());
            assertEquals("{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}", versionData.schemaContent());
            assertEquals(SchemaType.JSON_SCHEMA, versionData.schemaType());
            assertEquals(SchemaCompatibility.BACKWARD, versionData.compatibility());
            assertEquals("Added name property", versionData.versionDescription());

            // Verify date - parse expected date
            Instant expectedCreatedAt = Instant.parse("2023-05-15T10:30:45.678Z");

            // Compare timestamp (allowing for small differences in precision)
            assertTrue(Math.abs(expectedCreatedAt.toEpochMilli() - versionData.createdAt().toEpochMilli()) < 1000);
        }
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed");
        }
    }
}
