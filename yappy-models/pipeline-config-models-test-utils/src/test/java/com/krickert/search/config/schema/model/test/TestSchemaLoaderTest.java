package com.krickert.search.config.schema.model.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class TestSchemaLoaderTest {

    @Mock
    private ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void loadSchemaContent() {
        String content = TestSchemaLoader.loadSchemaContent("pipeline-step-config-schema.json");
        assertNotNull(content);
        assertTrue(content.contains("Pipeline Step Configuration Schema"));
    }

    @Test
    void loadSchemaAsJsonNode() {
        JsonNode jsonNode = TestSchemaLoader.loadSchemaAsJsonNode("pipeline-step-config-schema.json");
        assertNotNull(jsonNode);
        assertEquals("Pipeline Step Configuration Schema", jsonNode.get("title").asText());
    }

    @Test
    void registerTestSchema() {
        // Mock the schema registry delegate
        when(schemaRegistryDelegate.saveSchema(anyString(), anyString())).thenReturn(Mono.empty());

        boolean result = TestSchemaLoader.registerTestSchema(
                schemaRegistryDelegate,
                "test-schema:1",
                "pipeline-step-config-schema.json"
        );

        assertTrue(result);
    }

    @Test
    void validateContent_valid() throws Exception {
        // Create a valid configuration
        ObjectNode configNode = objectMapper.createObjectNode();
        configNode.put("inputField", "content");
        configNode.put("outputField", "processedContent");
        String configJson = objectMapper.writeValueAsString(configNode);

        // Load the schema
        String schemaContent = TestSchemaLoader.loadSchemaContent("pipeline-step-config-schema.json");

        // Mock the schema registry delegate
        when(schemaRegistryDelegate.validateContentAgainstSchema(anyString(), anyString()))
                .thenReturn(Mono.just(Collections.emptySet()));

        // Validate the configuration
        Set<ValidationMessage> validationMessages = TestSchemaLoader.validateContent(
                schemaRegistryDelegate,
                configJson,
                schemaContent
        );

        assertNotNull(validationMessages);
        assertTrue(validationMessages.isEmpty());
    }

    @Test
    void validateContent_invalid() throws Exception {
        // Create an invalid configuration (missing required field)
        ObjectNode configNode = objectMapper.createObjectNode();
        configNode.put("inputField", "content");
        // Missing outputField
        String configJson = objectMapper.writeValueAsString(configNode);

        // Load the schema
        String schemaContent = TestSchemaLoader.loadSchemaContent("pipeline-step-config-schema.json");

        // Create a validation message
        ValidationMessage validationMessage = ValidationMessage.builder()
                .message("$.outputField: is missing but it is required")
                .build();

        // Mock the schema registry delegate
        when(schemaRegistryDelegate.validateContentAgainstSchema(anyString(), anyString()))
                .thenReturn(Mono.just(Set.of(validationMessage)));

        // Validate the configuration
        Set<ValidationMessage> validationMessages = TestSchemaLoader.validateContent(
                schemaRegistryDelegate,
                configJson,
                schemaContent
        );

        assertNotNull(validationMessages);
        assertEquals(1, validationMessages.size());
        assertEquals("$.outputField: is missing but it is required", validationMessages.iterator().next().getMessage());
    }
}