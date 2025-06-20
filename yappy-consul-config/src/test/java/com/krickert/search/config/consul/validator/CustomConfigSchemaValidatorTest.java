package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.consul.ValidationResult;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.schema.model.test.SchemaValidator;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomConfigSchemaValidatorTest {
    private static final Logger log = LoggerFactory.getLogger(CustomConfigSchemaValidatorTest.class);

    // Test JSON schemas
    private static final String USER_SCHEMA_V1_CONTENT = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "UserConfig",
              "type": "object",
              "properties": {
                "username": { "type": "string", "minLength": 3 },
                "maxConnections": { "type": "integer", "minimum": 1, "maximum": 100 }
              },
              "required": ["username", "maxConnections"]
            }""";
    private static final String ADDRESS_SCHEMA_V1_CONTENT = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "AddressConfig",
              "type": "object",
              "properties": {
                "street": { "type": "string" },
                "city": { "type": "string" }
              },
              "required": ["street"]
            }""";
    private static final String MALFORMED_SCHEMA_CONTENT = "{ not a valid json schema";
    private CustomConfigSchemaValidator validator;
    private ObjectMapper objectMapper;
    private Map<String, PipelineModuleConfiguration> availableModules;
    private Map<SchemaReference, String> schemaContentsMap; // Renamed for clarity
    @Mock
    private ConsulSchemaRegistryDelegate schemaRegistryDelegate;
    @Mock
    private SchemaValidationService schemaValidationService;

    // Helper to create ProcessorInfo for internal beans
    private ProcessorInfo internalBeanProcessor(String beanImplementationId) {
        // Assuming internal beans are identified by their implementationId which is also the bean name
        return new ProcessorInfo(null, beanImplementationId);
    }

    // Helper to create ProcessorInfo for gRPC services
    private ProcessorInfo grpcServiceProcessor(String serviceImplementationId) {
        // Assuming gRPC services are identified by their implementationId which is the service name
        return new ProcessorInfo(serviceImplementationId, null);
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        availableModules = new HashMap<>();
        schemaContentsMap = new HashMap<>();

        // Add test schemas to the schemaContentsMap
        SchemaReference userSchemaRef = new SchemaReference("user-module-schema-subject", 1);
        schemaContentsMap.put(userSchemaRef, USER_SCHEMA_V1_CONTENT);

        SchemaReference addressSchemaRef = new SchemaReference("address-module-schema-subject", 1);
        schemaContentsMap.put(addressSchemaRef, ADDRESS_SCHEMA_V1_CONTENT);

        // Configure the mock schemaRegistryDelegate to use our schemaContentsMap
        when(schemaRegistryDelegate.getSchemaContent(anyString())).thenAnswer(invocation -> {
            String schemaId = invocation.getArgument(0);
            // Find the SchemaReference with the matching subject
            Optional<SchemaReference> matchingRef = schemaContentsMap.keySet().stream()
                    .filter(ref -> ref.subject().equals(schemaId))
                    .findFirst();

            if (matchingRef.isPresent()) {
                String content = schemaContentsMap.get(matchingRef.get());
                return Mono.just(content);
            } else {
                return Mono.error(new RuntimeException("Schema not found for ID: " + schemaId));
            }
        });

        // Configure validateContentAgainstSchema to use SchemaValidator
        when(schemaRegistryDelegate.validateContentAgainstSchema(anyString(), anyString())).thenAnswer(invocation -> {
            String jsonContent = invocation.getArgument(0);
            String schemaContent = invocation.getArgument(1);
            Set<ValidationMessage> messages = SchemaValidator.validateContent(jsonContent, schemaContent);
            return Mono.just(messages);
        });
        
        // Configure schemaValidationService to delegate to SchemaValidator
        when(schemaValidationService.validateJson(any(JsonNode.class), any(JsonNode.class))).thenAnswer(invocation -> {
            JsonNode jsonNode = invocation.getArgument(0);
            JsonNode schemaNode = invocation.getArgument(1);
            Set<ValidationMessage> messages = SchemaValidator.validateContent(jsonNode.toString(), schemaNode.toString());
            if (messages.isEmpty()) {
                return Mono.just(ValidationResult.valid());
            } else {
                List<String> errors = messages.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                return Mono.just(ValidationResult.invalid(errors));
            }
        });

        validator = new CustomConfigSchemaValidator(objectMapper, schemaValidationService);

        // Register test schemas with the mock schemaRegistryDelegate
        for (Map.Entry<SchemaReference, String> entry : schemaContentsMap.entrySet()) {
            String schemaId = entry.getKey().subject();
            String schemaContent = entry.getValue();

            // Mock the saveSchema method to return a completed Mono
            when(schemaRegistryDelegate.saveSchema(eq(schemaId), eq(schemaContent)))
                    .thenReturn(Mono.empty());
        }
    }

    private Optional<String> testSchemaContentProvider(SchemaReference ref) {
        return Optional.ofNullable(schemaContentsMap.get(ref));
    }

    private JsonConfigOptions createJsonConfig(String jsonString) {
        try {
            JsonNode node = objectMapper.readTree(jsonString);
            // Using the inner record from PipelineStepConfig
            return new PipelineStepConfig.JsonConfigOptions(node, Collections.emptyMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse test JSON string: " + jsonString, e);
        }
    }

    private JsonConfigOptions emptyInnerJsonConfig() {
        // Using the inner record from PipelineStepConfig
        return new PipelineStepConfig.JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }


    @Test
    void validate_validCustomConfig_returnsNoErrors() {
        String moduleImplementationId = "user-module-impl";
        SchemaReference userSchemaRef = new SchemaReference("user-module-schema-subject", 1);

        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "User Module Display Name",
                moduleImplementationId,
                userSchemaRef
        ));

        PipelineStepConfig step = new PipelineStepConfig(
                "step1", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                createJsonConfig("""
                        {
                          "username": "testuser",
                          "maxConnections": 10
                        }""")
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of("step1", step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid custom config should not produce errors. Errors: " + errors);
    }

    @Test
    void validate_invalidCustomConfig_returnsError() {
        String moduleImplementationId = "user-module-invalid-impl";
        SchemaReference userSchemaRef = new SchemaReference("user-module-schema-subject", 1); // Use the existing schema

        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "User Module Invalid Display",
                moduleImplementationId,
                userSchemaRef
        ));

        PipelineStepConfig step = new PipelineStepConfig(
                "step-invalid-config", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                createJsonConfig("""
                        {
                          "username": "us"
                        }""") // Missing maxConnections, username too short
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertFalse(errors.isEmpty(), "Invalid custom config should produce errors.");
        assertEquals(1, errors.size(), "Expected one error message grouping schema violations.");
        assertTrue(errors.get(0).contains("Step 'step-invalid-config' custom config failed schema validation"));
        // Specific error messages depend on the JSON schema library (networknt-schema-validator)
        // but we can check for parts of it.
        // Print the actual error message for debugging
        System.out.println("[DEBUG_LOG] Actual error message: " + errors.get(0));

        // Check for username validation error - the exact message format may vary
        assertTrue(errors.get(0).contains("username"), "Error message should mention 'username'");

        // Check for maxConnections validation error - the exact message format may vary
        assertTrue(errors.get(0).contains("maxConnections"), "Error message should mention 'maxConnections'");
    }

    @Test
    void validate_schemaNotFoundForStep_returnsError() {
        String moduleImplementationId = "bean-missing-schema-impl";
        SchemaReference missingSchemaRef = new SchemaReference("module-with-missing-schema-subject", 1);
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "Missing Schema Module Display",
                moduleImplementationId,
                missingSchemaRef
        ));
        // IMPORTANT: Do NOT add missingSchemaRef to schemaContentsMap in testSchemaContentProvider

        PipelineStepConfig step = new PipelineStepConfig(
                "step-schema-not-found", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                createJsonConfig("""
                        {"data":"some data"}""")
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);

        assertFalse(errors.isEmpty(), "Should return error if schema content is not found via provider.");
        assertEquals(1, errors.size(), "Expected exactly one error message.");
        String actualErrorMessage = errors.get(0);
        log.info("Actual error for schema not found: {}", actualErrorMessage);

        // Construct the expected message based on the validator's logic
        String expectedSchemaSourceDescription = "module-defined schemaRef '" + missingSchemaRef.toIdentifier() + "'";
        String expectedErrorMessage = String.format("Schema content for %s (referenced by step '%s') not found via provider. Cannot validate configuration.",
                expectedSchemaSourceDescription, step.stepName());

        assertEquals(expectedErrorMessage, actualErrorMessage, "Error message content mismatch.");
    }



    @Test
    void validate_stepWithCustomConfigButModuleHasNoSchemaRef_noErrorFromThisValidator() {
        String moduleImplementationId = "bean-no-schema-ref-impl";
        // Module exists but does not define a customConfigSchemaReference (pass null)
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "No Schema Ref Module Display",
                moduleImplementationId,
                null // No schema reference
        ));

        PipelineStepConfig step = new PipelineStepConfig(
                "step-no-module-schema", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                createJsonConfig("""
                        {"data":"some data"}""") // Has custom config
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "No error from CustomConfigSchemaValidator if module has no schema ref. Errors: " + errors);
    }


    @Test
    void validate_stepWithNoCustomConfig_returnsNoErrors() {
        String moduleImplementationId = "bean-user-module-no-config-impl";
        SchemaReference userSchemaRef = new SchemaReference("user-module-schema-subject", 1); // Use the existing schema
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "User Module No Config Display",
                moduleImplementationId,
                userSchemaRef
        ));

        PipelineStepConfig step = new PipelineStepConfig(
                "step-no-custom-config", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                null // No customConfig (PipelineStepConfig.JsonConfigOptions object is null)
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "No errors if step has no custom config, even if module defines a schema. Errors: " + errors);
    }

    @Test
    void validate_stepWithEmptyJsonCustomConfig_validatesAsEmptyObject() {
        String moduleImplementationId = "bean-empty-config-impl";
        SchemaReference schemaRef = new SchemaReference("empty-test-schema-subject", 1);
        String schemaAcceptingEmpty = """
                {
                  "type": "object",
                  "properties": { "optionalField": { "type": "string" }}
                }""";
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "Empty Config Module Display",
                moduleImplementationId,
                schemaRef
        ));
        // Add the schema to the schemaContentsMap so it can be found by the mock schemaRegistryDelegate
        schemaContentsMap.put(schemaRef, schemaAcceptingEmpty);

        // Configure the mock schemaRegistryDelegate to return this schema
        when(schemaRegistryDelegate.getSchemaContent(eq(schemaRef.subject())))
                .thenReturn(Mono.just(schemaAcceptingEmpty));

        PipelineStepConfig step = new PipelineStepConfig(
                "step-empty-json", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                emptyInnerJsonConfig() // CustomConfig with an empty JsonNode object
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "Empty JSON config against a schema allowing empty object should be valid. Errors: " + errors);
    }

    @Test
    void validate_malformedSchemaInProvider_returnsError() {
        String moduleImplementationId = "bean-malformed-schema-impl";
        SchemaReference malformedSchemaRef = new SchemaReference("module-malformed-schema-subject", 1);
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "Malformed Schema Module Display",
                moduleImplementationId,
                malformedSchemaRef
        ));
        // Add the malformed schema to the schemaContentsMap
        schemaContentsMap.put(malformedSchemaRef, MALFORMED_SCHEMA_CONTENT);

        // Configure the mock schemaRegistryDelegate to return this malformed schema
        when(schemaRegistryDelegate.getSchemaContent(eq(malformedSchemaRef.subject())))
                .thenReturn(Mono.just(MALFORMED_SCHEMA_CONTENT));

        PipelineStepConfig step = new PipelineStepConfig(
                "step-malformed-schema", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                createJsonConfig("""
                        {"data":"some data"}""")
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertFalse(errors.isEmpty(), "Should return error if schema content is malformed.");

        // Print the actual error message for debugging
        System.out.println("[DEBUG_LOG] Actual error message: " + errors.get(0));

        // Check that the error message contains the step name and schema reference
        assertTrue(errors.get(0).contains("step-malformed-schema"), "Error message should mention the step name");
        assertTrue(errors.get(0).contains("module-malformed-schema-subject"), "Error message should mention the schema subject");
    }

    @Test
    void validate_stepWithNullJsonNodeInCustomConfig_validatesAsEmptyAgainstPermissiveSchema() {
        String moduleImplementationId = "bean-null-node-impl";
        SchemaReference schemaRef = new SchemaReference("null-node-schema-subject", 1);
        String permissiveSchema = """
                {
                  "type": "object",
                  "properties": { "optionalField": { "type": "string" }}
                }""";
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "Null Node Module Display",
                moduleImplementationId,
                schemaRef
        ));
        // Add the schema to the schemaContentsMap
        schemaContentsMap.put(schemaRef, permissiveSchema);

        // Configure the mock schemaRegistryDelegate to return this schema
        when(schemaRegistryDelegate.getSchemaContent(eq(schemaRef.subject())))
                .thenReturn(Mono.just(permissiveSchema));

        PipelineStepConfig.JsonConfigOptions configWithNullNode = new PipelineStepConfig.JsonConfigOptions(null, Collections.emptyMap());
        PipelineStepConfig step = new PipelineStepConfig(
                "step-null-json-node", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                configWithNullNode
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "Config with null JsonNode should validate as empty (via default empty ObjectNode) against a permissive schema. Errors: " + errors);
    }

    @Test
    void validate_stepWithNullJsonNodeInCustomConfig_failsAgainstStrictSchema() {
        String moduleImplementationId = "bean-null-node-strict-impl";
        SchemaReference schemaRef = new SchemaReference("null-node-strict-schema-subject", 1);
        String strictSchema = """
                {
                  "type": "object",
                  "properties": { "requiredField": { "type": "string" }},
                  "required": ["requiredField"]
                }""";
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "Null Node Strict Module Display",
                moduleImplementationId,
                schemaRef
        ));
        // Add the schema to the schemaContentsMap
        schemaContentsMap.put(schemaRef, strictSchema);

        // Configure the mock schemaRegistryDelegate to return this schema
        when(schemaRegistryDelegate.getSchemaContent(eq(schemaRef.subject())))
                .thenReturn(Mono.just(strictSchema));

        PipelineStepConfig.JsonConfigOptions configWithNullNode = new PipelineStepConfig.JsonConfigOptions(null, Collections.emptyMap());
        PipelineStepConfig step = new PipelineStepConfig(
                "step-null-json-node-strict", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                configWithNullNode
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertFalse(errors.isEmpty(), "Config with null JsonNode should fail (as empty object) against a strict schema. Errors: " + errors.getFirst());
        // Print the actual error message for debugging
        System.out.println("[DEBUG_LOG] Actual error message: " + errors.get(0));

        // Check for requiredField validation error - the exact message format may vary
        assertTrue(errors.getFirst().contains("requiredField"), "Error message should mention 'requiredField'");
    }

    @Test
    void validate_noPipelines_returnsNoErrors() {
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.emptyMap());
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        // Use validateUsingRegistry instead of validate with schemaContentProvider
        // CORRECTED LINE: Call the public 'validate' method
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "No pipelines should result in no errors. Errors: " + errors);
    }
}
