package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.pipeline.model.*; // Assuming this wildcard covers all needed models
// Specific imports that might be needed if wildcard doesn't cover nested records for some compilers/IDEs
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
// import com.krickert.search.config.pipeline.model.StepType; // Covered by wildcard
// import com.krickert.search.config.pipeline.model.SchemaReference; // Covered by wildcard
// import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration; // Covered
// import com.krickert.search.config.pipeline.model.PipelineModuleMap; // Covered
// import com.krickert.search.config.pipeline.model.PipelineConfig; // Covered
// import com.krickert.search.config.pipeline.model.PipelineGraphConfig; // Covered
// import com.krickert.search.config.pipeline.model.PipelineClusterConfig; // Covered


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*; // Covers Map, List, Set, Collections, HashMap
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CustomConfigSchemaValidatorTest {

    private CustomConfigSchemaValidator validator;
    private ObjectMapper objectMapper;
    private Map<String, PipelineModuleConfiguration> availableModules;
    private Map<SchemaReference, String> schemaContentsMap; // Renamed for clarity

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


    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new CustomConfigSchemaValidator(objectMapper);
        availableModules = new HashMap<>();
        schemaContentsMap = new HashMap<>();
    }

    // Function to provide schema content based on SchemaReference
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
        SchemaReference userSchemaRef = new SchemaReference("user-module-schema-subject", 1); // Subject might differ from implId

        // Module setup: implementationId is key, customConfigSchemaReference points to schema
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "User Module Display Name", // implementationName
                moduleImplementationId,     // implementationId (links to ProcessorInfo)
                userSchemaRef               // customConfigSchemaReference
        ));
        schemaContentsMap.put(userSchemaRef, USER_SCHEMA_V1_CONTENT);

        PipelineStepConfig step = new PipelineStepConfig(
                "step1", StepType.PIPELINE, // Using INITIAL_PIPELINE or PIPELINE as appropriate
                internalBeanProcessor(moduleImplementationId), // ProcessorInfo links to module's implementationId
                createJsonConfig("""
                        {
                          "username": "testuser",
                          "maxConnections": 10
                        }""")
                // Using a simpler constructor that defaults customConfigSchemaId to null
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of("step1", step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid custom config should not produce errors. Errors: " + errors);
    }

    @Test
    void validate_invalidCustomConfig_returnsError() {
        String moduleImplementationId = "user-module-invalid-impl";
        SchemaReference userSchemaRef = new SchemaReference("user-module-invalid-subject", 1);

        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
                "User Module Invalid Display",
                moduleImplementationId,
                userSchemaRef
        ));
        schemaContentsMap.put(userSchemaRef, USER_SCHEMA_V1_CONTENT); // Use the same valid schema

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
        // IMPORTANT: Do NOT add missingSchemaRef to schemaContentsMap

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
        assertFalse(errors.isEmpty(), "Should return error if schema content is not found by provider.");
        assertTrue(errors.get(0).contains("Schema content for SchemaReference[subject=module-with-missing-schema-subject, version=1] (step 'step-schema-not-found') not found by provider."), "Error message content mismatch. Got: " + errors.get(0));
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

        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "No error from CustomConfigSchemaValidator if module has no schema ref. Errors: " + errors);
    }


    @Test
    void validate_stepWithNoCustomConfig_returnsNoErrors() {
        String moduleImplementationId = "bean-user-module-no-config-impl";
        SchemaReference userSchemaRef = new SchemaReference("user-module-no-config-subject", 1);
        availableModules.put(moduleImplementationId, new PipelineModuleConfiguration(
            "User Module No Config Display",
            moduleImplementationId,
            userSchemaRef
        ));
        schemaContentsMap.put(userSchemaRef, USER_SCHEMA_V1_CONTENT);

        PipelineStepConfig step = new PipelineStepConfig(
                "step-no-custom-config", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                null // No customConfig (PipelineStepConfig.JsonConfigOptions object is null)
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

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
        schemaContentsMap.put(schemaRef, schemaAcceptingEmpty);

        PipelineStepConfig step = new PipelineStepConfig(
                "step-empty-json", StepType.PIPELINE,
                internalBeanProcessor(moduleImplementationId),
                emptyInnerJsonConfig() // CustomConfig with an empty JsonNode object
        );

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of("p1", pipeline));
        PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());

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
        schemaContentsMap.put(malformedSchemaRef, MALFORMED_SCHEMA_CONTENT);

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

        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertFalse(errors.isEmpty(), "Should return error if schema content is malformed.");
        assertTrue(errors.get(0).contains("Error validating custom config for step 'step-malformed-schema' against schema SchemaReference[subject=module-malformed-schema-subject, version=1]"), "Error message content mismatch. Got: " + errors.get(0));
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
        schemaContentsMap.put(schemaRef, permissiveSchema);

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
        schemaContentsMap.put(schemaRef, strictSchema);

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

        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertFalse(errors.isEmpty(), "Config with null JsonNode should fail (as empty object) against a strict schema. Errors: " + errors.get(0));
        // Print the actual error message for debugging
        System.out.println("[DEBUG_LOG] Actual error message: " + errors.get(0));

        // Check for requiredField validation error - the exact message format may vary
        assertTrue(errors.get(0).contains("requiredField"), "Error message should mention 'requiredField'");
    }

    @Test
    void validate_noPipelines_returnsNoErrors() {
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.emptyMap());
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig("c1", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());
        List<String> errors = validator.validate(clusterConfig, this::testSchemaContentProvider);
        assertTrue(errors.isEmpty(), "No pipelines should result in no errors. Errors: " + errors);
    }
}
