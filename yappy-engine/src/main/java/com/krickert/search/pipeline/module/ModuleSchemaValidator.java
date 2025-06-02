package com.krickert.search.pipeline.module;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Validates module configuration schemas and configurations against schemas.
 */
@Singleton
public class ModuleSchemaValidator {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleSchemaValidator.class);
    
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    
    public ModuleSchemaValidator() {
        this.objectMapper = new ObjectMapper();
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }
    
    /**
     * Validates that a provided schema is a valid JSON Schema Draft 7.
     * 
     * @param schemaJson The JSON schema as a string
     * @return ValidationResult indicating if the schema is valid
     */
    public ValidationResult validateSchema(String schemaJson) {
        try {
            // Parse the schema
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            
            // Check for required schema properties
            if (!schemaNode.has("$schema")) {
                return ValidationResult.invalid("Schema must include '$schema' property");
            }
            
            String schemaVersion = schemaNode.get("$schema").asText();
            if (!schemaVersion.contains("draft-07") && !schemaVersion.contains("draft/7")) {
                return ValidationResult.invalid("Schema must use JSON Schema draft-07, found: " + schemaVersion);
            }
            
            if (!schemaNode.has("type")) {
                return ValidationResult.invalid("Schema must include 'type' property");
            }
            
            // Try to compile the schema
            JsonSchema schema = schemaFactory.getSchema(schemaNode);
            
            // Validate schema structure
            if ("object".equals(schemaNode.get("type").asText()) && !schemaNode.has("properties")) {
                LOG.warn("Schema defines type 'object' but has no 'properties' - module may not be configurable");
            }
            
            return ValidationResult.valid("Schema is valid JSON Schema draft-07");
            
        } catch (Exception e) {
            LOG.error("Failed to validate schema", e);
            return ValidationResult.invalid("Invalid schema: " + e.getMessage());
        }
    }
    
    /**
     * Validates a configuration against a module's schema.
     * 
     * @param configuration The configuration JSON to validate
     * @param schemaJson The schema to validate against
     * @return ValidationResult indicating if the configuration is valid
     */
    public ValidationResult validateConfiguration(String configuration, String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            JsonNode configNode = objectMapper.readTree(configuration);
            
            JsonSchema schema = schemaFactory.getSchema(schemaNode);
            Set<ValidationMessage> errors = schema.validate(configNode);
            
            if (errors.isEmpty()) {
                return ValidationResult.valid("Configuration is valid");
            } else {
                StringBuilder errorMsg = new StringBuilder("Configuration validation failed:\n");
                for (ValidationMessage error : errors) {
                    errorMsg.append("  - ").append(error.getMessage()).append("\n");
                }
                return ValidationResult.invalid(errorMsg.toString());
            }
            
        } catch (Exception e) {
            LOG.error("Failed to validate configuration", e);
            return ValidationResult.invalid("Validation error: " + e.getMessage());
        }
    }
    
    /**
     * Extracts default values from a schema.
     * 
     * @param schemaJson The schema JSON
     * @return A JSON string with default configuration values
     */
    public String extractDefaults(String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            JsonNode propertiesNode = schemaNode.get("properties");
            
            if (propertiesNode == null || !propertiesNode.isObject()) {
                return "{}";
            }
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode defaultConfig = mapper.createObjectNode();
            
            propertiesNode.fields().forEachRemaining(entry -> {
                String propertyName = entry.getKey();
                JsonNode propertySchema = entry.getValue();
                
                if (propertySchema.has("default")) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) defaultConfig)
                            .set(propertyName, propertySchema.get("default"));
                }
            });
            
            return mapper.writeValueAsString(defaultConfig);
            
        } catch (Exception e) {
            LOG.error("Failed to extract defaults from schema", e);
            return "{}";
        }
    }
    
    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        
        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
        
        public static ValidationResult valid(String message) {
            return new ValidationResult(true, message);
        }
        
        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return "ValidationResult{valid=" + valid + ", message='" + message + "'}";
        }
    }
}