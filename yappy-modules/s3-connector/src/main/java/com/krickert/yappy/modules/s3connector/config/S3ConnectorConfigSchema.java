package com.krickert.yappy.modules.s3connector.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Utility class for working with the S3ConnectorConfig JSON Schema.
 * This class provides methods to load, validate, and generate configurations
 * based on the JSON Schema.
 */
@Singleton
public class S3ConnectorConfigSchema {

    private static final Logger LOG = LoggerFactory.getLogger(S3ConnectorConfigSchema.class);
    private static final String SCHEMA_PATH = "/schema/s3-connector-config-schema.json";
    
    private final JsonSchema schema;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new S3ConnectorConfigSchema.
     * Loads the JSON Schema from the resources.
     */
    public S3ConnectorConfigSchema() {
        this.objectMapper = new ObjectMapper();
        this.schema = loadSchema();
    }

    /**
     * Loads the JSON Schema from the resources.
     *
     * @return the loaded JSON Schema
     */
    private JsonSchema loadSchema() {
        try (InputStream schemaStream = getClass().getResourceAsStream(SCHEMA_PATH)) {
            if (schemaStream == null) {
                throw new IllegalStateException("Schema file not found: " + SCHEMA_PATH);
            }
            
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(schemaStream);
        } catch (IOException e) {
            LOG.error("Failed to load schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to load schema", e);
        }
    }

    /**
     * Validates a configuration against the JSON Schema.
     *
     * @param config the configuration to validate
     * @return a set of validation messages, empty if valid
     */
    public Set<ValidationMessage> validate(S3ConnectorConfig config) {
        try {
            JsonNode jsonNode = objectMapper.valueToTree(config);
            return schema.validate(jsonNode);
        } catch (Exception e) {
            LOG.error("Failed to validate config: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Failed to validate config", e);
        }
    }

    /**
     * Checks if a configuration is valid according to the JSON Schema.
     *
     * @param config the configuration to validate
     * @return true if valid, false otherwise
     */
    public boolean isValid(S3ConnectorConfig config) {
        return validate(config).isEmpty();
    }

    /**
     * Returns the JSON Schema as a string.
     *
     * @return the JSON Schema as a string
     */
    public String getSchemaAsString() {
        try (InputStream schemaStream = getClass().getResourceAsStream(SCHEMA_PATH)) {
            if (schemaStream == null) {
                throw new IllegalStateException("Schema file not found: " + SCHEMA_PATH);
            }
            return new String(schemaStream.readAllBytes());
        } catch (IOException e) {
            LOG.error("Failed to read schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to read schema", e);
        }
    }

    /**
     * Returns the JSON Schema as a JsonNode.
     *
     * @return the JSON Schema as a JsonNode
     */
    public JsonNode getSchemaAsJsonNode() {
        try {
            return objectMapper.readTree(getSchemaAsString());
        } catch (IOException e) {
            LOG.error("Failed to parse schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to parse schema", e);
        }
    }
}