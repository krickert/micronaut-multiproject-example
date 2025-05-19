package com.krickert.search.config.consul.schema.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.schema.model.test.SchemaValidator;
import com.networknt.schema.ValidationMessage;
import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Utility class for loading JSON schemas in tests.
 * This class provides methods for loading schema content from resources and registering them with the ConsulSchemaRegistryDelegate.
 */
public class TestSchemaLoader {
    private static final Logger log = LoggerFactory.getLogger(TestSchemaLoader.class);
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
            log.error("Failed to load schema content from resource: {}: {}", resourceName, e.getMessage(), e);
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
            log.error("Failed to parse schema content as JSON: {}: {}", resourceName, e.getMessage(), e);
            throw new RuntimeException("Failed to parse schema content as JSON: " + resourceName, e);
        }
    }

    /**
     * Registers a schema with the ConsulSchemaRegistryDelegate.
     *
     * @param schemaRegistryDelegate The ConsulSchemaRegistryDelegate to register the schema with
     * @param schemaId               The ID to register the schema under
     * @param resourceName           The name of the resource file containing the schema
     * @return true if the schema was registered successfully, false otherwise
     */
    public static boolean registerTestSchema(
            @NonNull ConsulSchemaRegistryDelegate schemaRegistryDelegate,
            @NonNull String schemaId,
            @NonNull String resourceName) {
        try {
            String schemaContent = loadSchemaContent(resourceName);
            schemaRegistryDelegate.saveSchema(schemaId, schemaContent).block();
            log.info("Successfully registered test schema '{}' from resource '{}'", schemaId, resourceName);
            return true;
        } catch (Exception e) {
            log.error("Failed to register test schema '{}' from resource '{}': {}",
                    schemaId, resourceName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Validates JSON content against a schema using the ConsulSchemaRegistryDelegate.
     *
     * @param schemaRegistryDelegate The ConsulSchemaRegistryDelegate to use for validation
     * @param jsonContent            The JSON content to validate
     * @param schemaContent          The schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateContent(
            @NonNull ConsulSchemaRegistryDelegate schemaRegistryDelegate,
            @NonNull String jsonContent,
            @NonNull String schemaContent) {
        try {
            return schemaRegistryDelegate.validateContentAgainstSchema(jsonContent, schemaContent).block();
        } catch (Exception e) {
            log.error("Failed to validate content using ConsulSchemaRegistryDelegate: {}", e.getMessage(), e);
            // Fallback to direct validation if delegate validation fails
            return SchemaValidator.validateContent(jsonContent, schemaContent);
        }
    }

    /**
     * Validates a JsonNode against a schema using the ConsulSchemaRegistryDelegate.
     *
     * @param schemaRegistryDelegate The ConsulSchemaRegistryDelegate to use for validation
     * @param jsonNode               The JsonNode to validate
     * @param schemaContent          The schema content to validate against
     * @return A set of validation messages if validation fails, or an empty set if validation succeeds
     */
    public static Set<ValidationMessage> validateJsonNode(
            @NonNull ConsulSchemaRegistryDelegate schemaRegistryDelegate,
            @NonNull JsonNode jsonNode,
            @NonNull String schemaContent) {
        try {
            String jsonContent = objectMapper.writeValueAsString(jsonNode);
            return schemaRegistryDelegate.validateContentAgainstSchema(jsonContent, schemaContent).block();
        } catch (Exception e) {
            log.error("Failed to validate JsonNode using ConsulSchemaRegistryDelegate: {}", e.getMessage(), e);
            // Fallback to direct validation if delegate validation fails
            return SchemaValidator.validateJsonNode(jsonNode, schemaContent);
        }
    }
}
