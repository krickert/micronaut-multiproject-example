package com.krickert.search.config.consul.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the JsonConfigOptions class.
 */
public class JsonConfigOptionsTest {

    @Test
    void testDefaultConstructor() {
        JsonConfigOptions options = new JsonConfigOptions();
        assertEquals("{}", options.getJsonConfig());
        assertEquals("{}", options.getJsonSchema());
        assertNotNull(options.getSchemaServiceConfig());
    }

    @Test
    void testConstructorWithJsonConfig() {
        String jsonConfig = "{\"key\":\"value\"}";
        JsonConfigOptions options = new JsonConfigOptions(jsonConfig);
        assertEquals(jsonConfig, options.getJsonConfig());
        assertEquals("{}", options.getJsonSchema());
        assertNotNull(options.getSchemaServiceConfig());
    }

    @Test
    void testConstructorWithJsonConfigAndSchema() {
        String jsonConfig = "{\"key\":\"value\"}";
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}";
        JsonConfigOptions options = new JsonConfigOptions(jsonConfig, jsonSchema);
        assertEquals(jsonConfig, options.getJsonConfig());
        assertEquals(jsonSchema, options.getJsonSchema());
        assertNotNull(options.getSchemaServiceConfig());
    }

    @Test
    void testValidateConfig() {
        String jsonConfig = "{\"key\":\"value\"}";
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}";
        JsonConfigOptions options = new JsonConfigOptions(jsonConfig, jsonSchema);
        assertTrue(options.validateConfig());
        assertNull(options.getValidationErrors());
    }

    @Test
    void testValidateConfigWithInvalidJson() {
        String jsonConfig = "{invalid json}";
        JsonConfigOptions options = new JsonConfigOptions(jsonConfig);
        assertFalse(options.validateConfig());
        assertNotNull(options.getValidationErrors());
        assertTrue(options.getValidationErrors().contains("Invalid JSON"));
    }

    @Test
    void testSetSchemaServiceConfig() {
        JsonConfigOptions options = new JsonConfigOptions();
        SchemaServiceConfig schemaServiceConfig = new DefaultSchemaServiceConfig();
        options.setSchemaServiceConfig(schemaServiceConfig);
        assertSame(schemaServiceConfig, options.getSchemaServiceConfig());
        assertEquals(schemaServiceConfig.getJsonSchema(), options.getJsonSchema());
        assertEquals(schemaServiceConfig.serializeConfig(), options.getJsonConfig());
    }
}