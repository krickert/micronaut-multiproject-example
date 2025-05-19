package com.krickert.search.config.schema.model.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Utility class for loading JSON schemas in tests.
 * This class provides methods for loading schema content from resources.
 */
public class TestSchemaLoader {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Loads schema content from a resource file.
     *
     * @param resourceName The name of the resource file (relative to the classpath)
     * @return The schema content as a string
     * @throws RuntimeException if the resource cannot be loaded
     */
    public static String loadSchemaContent(String resourceName) {
        try (InputStream is = TestSchemaLoader.class.getClassLoader().getResourceAsStream("schemas/" + resourceName)) {
            if (is == null) {
                throw new IOException("Resource not found: schemas/" + resourceName);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to load schema content from resource: " + resourceName + ": " + e.getMessage());
            throw new RuntimeException("Failed to load schema content from resource: " + resourceName, e);
        }
    }

    /**
     * Loads schema content from a resource file and parses it as a JsonNode.
     *
     * @param resourceName The name of the resource file (relative to the classpath)
     * @return The schema content as a JsonNode
     * @throws RuntimeException if the resource cannot be loaded or parsed
     */
    public static JsonNode loadSchemaAsJsonNode(String resourceName) {
        String content = loadSchemaContent(resourceName);
        try {
            return objectMapper.readTree(content);
        } catch (IOException e) {
            System.err.println("Failed to parse schema content as JSON: " + resourceName + ": " + e.getMessage());
            throw new RuntimeException("Failed to parse schema content as JSON: " + resourceName, e);
        }
    }

    /**
     * Validates JSON content against a schema.
     *
     * @param jsonContent   The JSON content to validate
     * @param schemaContent The schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateContent(String jsonContent, String schemaContent) {
        return SchemaValidator.validateContent(jsonContent, schemaContent);
    }

    /**
     * Validates a JsonNode against a schema.
     *
     * @param jsonNode      The JsonNode to validate
     * @param schemaContent The schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateJsonNode(JsonNode jsonNode, String schemaContent) {
        return SchemaValidator.validateJsonNode(jsonNode, schemaContent);
    }
}
