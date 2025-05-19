package com.krickert.search.config.schema.model.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;

import java.io.IOException;
import java.util.Set;

/**
 * Utility class for validating JSON against JSON Schema.
 * This class provides methods for validating JSON content against JSON Schema.
 */
public class SchemaValidator {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * Creates a schema validators configuration with standard settings.
     *
     * @return A SchemaValidatorsConfig instance
     */
    private static SchemaValidatorsConfig getSchemaValidationConfig() {
        return SchemaValidatorsConfig.builder()
                .pathType(PathType.LEGACY)
                .errorMessageKeyword("message")
                .nullableKeywordEnabled(true)
                .build();
    }

    /**
     * Validates JSON content against a JSON Schema.
     *
     * @param jsonContent   The JSON content to validate
     * @param schemaContent The JSON Schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateContent(String jsonContent, String schemaContent) {
        try {
            // Parse the JSON content and schema
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            JsonNode schemaNode = objectMapper.readTree(schemaContent);

            // Create a JsonSchema instance from the schema content
            JsonSchema schema = schemaFactory.getSchema(schemaNode, getSchemaValidationConfig());

            // Validate the JSON content against the schema
            return schema.validate(jsonNode);
        } catch (IOException e) {
            System.err.println("Failed to parse JSON or schema content: " + e.getMessage());
            return Set.of(ValidationMessage.builder().message("Failed to parse JSON or schema content: " + e.getMessage()).build());
        } catch (Exception e) {
            System.err.println("Error during validation: " + e.getMessage());
            return Set.of(ValidationMessage.builder().message("Error during validation: " + e.getMessage()).build());
        }
    }

    /**
     * Validates a JsonNode against a JSON Schema.
     *
     * @param jsonNode      The JsonNode to validate
     * @param schemaContent The JSON Schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateJsonNode(JsonNode jsonNode, String schemaContent) {
        try {
            // Parse the schema
            JsonNode schemaNode = objectMapper.readTree(schemaContent);

            // Create a JsonSchema instance from the schema content
            JsonSchema schema = schemaFactory.getSchema(schemaNode, getSchemaValidationConfig());

            // Validate the JSON content against the schema
            return schema.validate(jsonNode);
        } catch (IOException e) {
            System.err.println("Failed to parse schema content: " + e.getMessage());
            return Set.of(ValidationMessage.builder().message("Failed to parse schema content: " + e.getMessage()).build());
        } catch (Exception e) {
            System.err.println("Error during validation: " + e.getMessage());
            return Set.of(ValidationMessage.builder().message("Error during validation: " + e.getMessage()).build());
        }
    }

    /**
     * Validates a JsonNode against a JSON Schema.
     *
     * @param jsonNode   The JsonNode to validate
     * @param schemaNode The JSON Schema as a JsonNode
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateJsonNode(JsonNode jsonNode, JsonNode schemaNode) {
        try {
            // Create a JsonSchema instance from the schema content
            JsonSchema schema = schemaFactory.getSchema(schemaNode, getSchemaValidationConfig());

            // Validate the JSON content against the schema
            return schema.validate(jsonNode);
        } catch (Exception e) {
            System.err.println("Error during validation: " + e.getMessage());
            return Set.of(ValidationMessage.builder().message("Error during validation: " + e.getMessage()).build());
        }
    }
}