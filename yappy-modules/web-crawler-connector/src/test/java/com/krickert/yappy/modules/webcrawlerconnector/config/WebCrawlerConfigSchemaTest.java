package com.krickert.yappy.modules.webcrawlerconnector.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WebCrawlerConfigSchema class.
 */
class WebCrawlerConfigSchemaTest {

    private WebCrawlerConfigSchema schema;

    @BeforeEach
    void setUp() {
        schema = new WebCrawlerConfigSchema();
    }

    @Test
    void testGetSchemaAsString() {
        // Get the schema as a string
        String schemaString = schema.getSchemaAsString();
        
        // Verify the schema string
        assertNotNull(schemaString);
        assertFalse(schemaString.isEmpty());
        assertTrue(schemaString.contains("\"title\": \"Web Crawler Connector Configuration\""));
    }

    @Test
    void testGetSchemaAsJsonNode() {
        // Get the schema as a JsonNode
        JsonNode schemaNode = schema.getSchemaAsJsonNode();
        
        // Verify the schema node
        assertNotNull(schemaNode);
        assertTrue(schemaNode.isObject());
        assertEquals("Web Crawler Connector Configuration", schemaNode.get("title").asText());
    }

    @Test
    void testValidateValidConfig() {
        // Create a valid configuration
        WebCrawlerConfig config = WebCrawlerConfig.defaults()
                .userAgent("Test User Agent")
                .maxDepth(0)
                .maxPages(1)
                .stayWithinDomain(true)
                .followRedirects(true)
                .timeoutSeconds(30)
                .headless(true)
                .extractText(true)
                .extractHtml(true)
                .extractTitle(true)
                .kafkaTopic("test-topic")
                .logPrefix("[TEST] ")
                .build();
        
        // Validate the configuration
        Set<ValidationMessage> validationMessages = schema.validate(config);
        
        // Verify the validation result
        assertTrue(validationMessages.isEmpty());
        assertTrue(schema.isValid(config));
    }

    @Test
    void testValidateInvalidConfig() {
        // Create an invalid configuration with negative maxDepth
        WebCrawlerConfig config = WebCrawlerConfig.defaults()
                .maxDepth(-1)
                .build();
        
        // Validate the configuration
        Set<ValidationMessage> validationMessages = schema.validate(config);
        
        // Verify the validation result
        assertFalse(validationMessages.isEmpty());
        assertFalse(schema.isValid(config));
        
        // Verify the validation message
        ValidationMessage message = validationMessages.iterator().next();
        assertTrue(message.getMessage().contains("minimum"));
    }
}