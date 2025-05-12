package com.krickert.search.config.consul.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the DefaultSchemaServiceConfig class.
 */
public class DefaultSchemaServiceConfigTest {

    @Test
    void testDefaultConstructor() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertNotNull(config.getJsonSchema());
        assertTrue(config.getJsonSchema().contains("\"type\":\"object\""));
        assertTrue(config.getJsonSchema().contains("\"additionalProperties\":true"));
        assertNotNull(config.getConfigMap());
        assertTrue(config.getConfigMap().isEmpty());
    }

    @Test
    void testConstructorWithJsonSchema() {
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}";
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig(jsonSchema);
        assertEquals(jsonSchema, config.getJsonSchema());
        assertNotNull(config.getConfigMap());
        assertTrue(config.getConfigMap().isEmpty());
    }

    @Test
    void testConstructorWithJsonSchemaAndConfig() {
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}";
        String jsonConfig = "{\"key\":\"value\"}";
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig(jsonSchema, jsonConfig);
        assertEquals(jsonSchema, config.getJsonSchema());
        assertNotNull(config.getConfigMap());
        assertEquals(1, config.getConfigMap().size());
        assertEquals("value", config.getConfigValue("key"));
    }

    @Test
    void testConstructorWithConfigMap() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("key", "value");
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig(configMap);
        assertNotNull(config.getJsonSchema());
        assertEquals(configMap, config.getConfigMap());
        assertEquals("value", config.getConfigValue("key"));
    }

    @Test
    void testValidateConfig() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertTrue(config.validateConfig("{\"key\":\"value\"}"));
        assertNull(config.getValidationErrors());
    }

    @Test
    void testValidateConfigWithInvalidJson() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertFalse(config.validateConfig("{invalid json}"));
        assertNotNull(config.getValidationErrors());
        assertTrue(config.getValidationErrors().contains("Invalid JSON"));
    }

    @Test
    void testValidateConfigWithNonObjectJson() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertFalse(config.validateConfig("\"just a string\""));
        assertNotNull(config.getValidationErrors());
        assertTrue(config.getValidationErrors().contains("Configuration must be a JSON object"));
    }

    @Test
    void testSerializeConfig() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("key", "value");
        configMap.put("number", 123);
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig(configMap);
        String json = config.serializeConfig();
        assertTrue(json.contains("\"key\":\"value\""));
        assertTrue(json.contains("\"number\":123"));
    }

    @Test
    void testDeserializeConfig() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertTrue(config.deserializeConfig("{\"key\":\"value\",\"number\":123}"));
        assertEquals("value", config.getConfigValue("key"));
        assertEquals(123, config.getConfigValue("number"));
    }

    @Test
    void testDeserializeConfigWithInvalidJson() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        assertFalse(config.deserializeConfig("{invalid json}"));
        assertNotNull(config.getValidationErrors());
    }

    @Test
    void testSetAndGetConfigValue() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        config.setConfigValue("key", "value");
        assertEquals("value", config.getConfigValue("key"));
    }

    @Test
    void testSetAndGetConfigMap() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("key", "value");
        config.setConfigMap(configMap);
        assertEquals(configMap, config.getConfigMap());
    }

    @Test
    void testSetJsonSchema() {
        DefaultSchemaServiceConfig config = new DefaultSchemaServiceConfig();
        String jsonSchema = "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}";
        config.setJsonSchema(jsonSchema);
        assertEquals(jsonSchema, config.getJsonSchema());
    }
}