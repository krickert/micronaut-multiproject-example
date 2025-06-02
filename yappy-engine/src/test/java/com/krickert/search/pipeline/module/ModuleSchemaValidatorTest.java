package com.krickert.search.pipeline.module;

import com.krickert.search.pipeline.module.ModuleSchemaValidator.ValidationResult;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class ModuleSchemaValidatorTest {
    
    @Inject
    ModuleSchemaValidator validator;
    
    @Test
    void testValidateSchema_ValidSchema() {
        String validSchema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "enabled": {
                        "type": "boolean",
                        "default": true
                    },
                    "maxSize": {
                        "type": "integer",
                        "minimum": 1,
                        "default": 100
                    }
                }
            }
            """;
        
        ValidationResult result = validator.validateSchema(validSchema);
        
        assertTrue(result.isValid());
        assertEquals("Schema is valid JSON Schema draft-07", result.getMessage());
    }
    
    @Test
    void testValidateSchema_MissingSchemaProperty() {
        String invalidSchema = """
            {
                "type": "object",
                "properties": {}
            }
            """;
        
        ValidationResult result = validator.validateSchema(invalidSchema);
        
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("must include '$schema'"));
    }
    
    @Test
    void testValidateSchema_WrongSchemaVersion() {
        String wrongVersionSchema = """
            {
                "$schema": "http://json-schema.org/draft-04/schema#",
                "type": "object"
            }
            """;
        
        ValidationResult result = validator.validateSchema(wrongVersionSchema);
        
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("must use JSON Schema draft-07"));
    }
    
    @Test
    void testValidateConfiguration_Valid() {
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "port": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 65535
                    },
                    "host": {
                        "type": "string"
                    }
                },
                "required": ["port"]
            }
            """;
        
        String validConfig = """
            {
                "port": 8080,
                "host": "localhost"
            }
            """;
        
        ValidationResult result = validator.validateConfiguration(validConfig, schema);
        
        assertTrue(result.isValid());
        assertEquals("Configuration is valid", result.getMessage());
    }
    
    @Test
    void testValidateConfiguration_Invalid() {
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "port": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 65535
                    }
                },
                "required": ["port"]
            }
            """;
        
        String invalidConfig = """
            {
                "port": "not a number"
            }
            """;
        
        ValidationResult result = validator.validateConfiguration(invalidConfig, schema);
        
        assertFalse(result.isValid());
        assertTrue(result.getMessage().contains("Configuration validation failed"));
    }
    
    @Test
    void testExtractDefaults() {
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "enabled": {
                        "type": "boolean",
                        "default": true
                    },
                    "timeout": {
                        "type": "integer",
                        "default": 30
                    },
                    "name": {
                        "type": "string",
                        "default": "default-name"
                    },
                    "tags": {
                        "type": "array",
                        "default": ["tag1", "tag2"]
                    }
                }
            }
            """;
        
        String defaults = validator.extractDefaults(schema);
        
        assertNotNull(defaults);
        assertTrue(defaults.contains("\"enabled\":true"));
        assertTrue(defaults.contains("\"timeout\":30"));
        assertTrue(defaults.contains("\"name\":\"default-name\""));
        assertTrue(defaults.contains("\"tags\":[\"tag1\",\"tag2\"]"));
    }
    
    @Test
    void testExtractDefaults_NoDefaults() {
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string"
                    }
                }
            }
            """;
        
        String defaults = validator.extractDefaults(schema);
        
        assertEquals("{}", defaults);
    }
}