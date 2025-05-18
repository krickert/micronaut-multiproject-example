package com.krickert.search.config.consul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.krickert.search.config.consul.validator.ClusterValidationRule;
import com.krickert.search.config.consul.validator.CustomConfigSchemaValidator;
import com.krickert.search.config.consul.validator.ReferentialIntegrityValidator;
import com.krickert.search.config.consul.validator.WhitelistValidator;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigObjects;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DefaultConfigurationValidator using Micronaut's dependency injection.
 * This test verifies that the DefaultConfigurationValidator correctly orchestrates all
 * ClusterValidationRule implementations that are automatically injected by Micronaut.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultConfigurationValidatorMicronautTest {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultConfigurationValidatorMicronautTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    private DefaultConfigurationValidator validator; // The DI managed validator

    @Inject
    private List<ClusterValidationRule> standardValidationRules; // Injected list of standard rule beans

    // This rule is NOT a @Singleton. It's instantiated manually for a specific test.
    static class TestSpecificMisbehavingRule implements ClusterValidationRule {
        public static boolean wasCalled = false;
        @Override
        public List<String> validate(PipelineClusterConfig clusterConfig, Function<SchemaReference, Optional<String>> schemaContentProvider) {
            wasCalled = true;
            throw new RuntimeException("Simulated unexpected error in TestSpecificMisbehavingRule!");
        }
    }

    @Test
    void testValidationRulesInjected() {
        LOG.info("Injected validation rules: {}", standardValidationRules.stream().map(r -> r.getClass().getSimpleName()).toList());
        assertTrue(standardValidationRules.size() >= 5, "Expected at least 5 standard validation rules (including loop validators), but got " + standardValidationRules.size());
        assertTrue(standardValidationRules.stream().anyMatch(rule -> rule instanceof ReferentialIntegrityValidator),
                "ReferentialIntegrityValidator not found in injected rules");
        assertTrue(standardValidationRules.stream().anyMatch(rule -> rule instanceof CustomConfigSchemaValidator),
                "CustomConfigSchemaValidator not found in injected rules");
        assertTrue(standardValidationRules.stream().anyMatch(rule -> rule instanceof WhitelistValidator),
                "WhitelistValidator not found in injected rules");
        assertTrue(standardValidationRules.stream().anyMatch(rule -> rule.getClass().getSimpleName().contains("InterPipelineLoopValidator")),
                "InterPipelineLoopValidator not found or not named as expected in injected rules");
        assertTrue(standardValidationRules.stream().anyMatch(rule -> rule.getClass().getSimpleName().contains("IntraPipelineLoopValidator")),
                "IntraPipelineLoopValidator not found or not named as expected in injected rules");
    }

    @Test
    void testValidatorInjectedAndValidatesSimpleConfig() {
        assertNotNull(validator, "DefaultConfigurationValidator should be injected");
        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName("TestClusterSimple")
                .defaultPipelineName("default-pipeline")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        Function<SchemaReference, Optional<String>> schemaContentProvider = ref -> Optional.of("{}");
        ValidationResult result = validator.validate(config, schemaContentProvider); // Use the injected validator
        assertTrue(result.isValid(), "Validation should succeed for a simple valid configuration. Errors: " + result.errors());
        assertTrue(result.errors().isEmpty(), "There should be no validation errors for a simple valid configuration.");
    }

    @Test
    void testValidateNullConfig() {
        ValidationResult result = validator.validate(null, ref -> Optional.empty()); // Use the injected validator
        assertFalse(result.isValid(), "Validation should fail for a null configuration");
        assertEquals(1, result.errors().size(), "There should be exactly one validation error");
        assertEquals("PipelineClusterConfig cannot be null.", result.errors().getFirst(),
                "The error message should indicate that the configuration is null");
    }

    @Test
    void testCreatingConfigWithBlankClusterName_throwsAtConstruction() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            PipelineClusterConfig.builder() // This line will throw
                    .clusterName("") // Blank cluster name
                    .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                    .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                    .defaultPipelineName("default-pipeline")
                    .allowedKafkaTopics(Collections.emptySet())
                    .allowedGrpcServices(Collections.emptySet())
                    .build();
        });
        assertEquals("PipelineClusterConfig clusterName cannot be null or blank.", exception.getMessage());
    }


    @Test
    void testValidateInvalidConfigFromInjectedRule() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step with a non-existent module
        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("step1")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("non-existent-module", null))
                .build();

        steps.put(step.stepName(), step);
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());

        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName("test-cluster-invalid-module")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName("pipeline1")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        ValidationResult result = validator.validate(config, ref -> Optional.empty()); // Use the injected validator
        assertFalse(result.isValid(), "Validation should fail for a configuration with an invalid implementation ID. Errors: " + result.errors());
        assertTrue(result.errors().size() >= 1, "There should be at least one validation error");
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("references unknown implementationKey 'non-existent-module'")),
                "At least one error should indicate an unknown implementation ID");
    }
    @Test
    void testValidateConfigWithMultipleRuleViolations() {
        Map<String, PipelineStepConfig> stepsInvalidModule = new HashMap<>();

        // Create a step with an unknown module ID and invalid custom config
        PipelineStepConfig stepInvalidModule = PipelineStepConfig.builder()
                .stepName("stepBadModule")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("unknown-module-id", null))
                .customConfigSchemaId("non-existent-schema")
                .customConfig(new PipelineStepConfig.JsonConfigOptions(OBJECT_MAPPER.createObjectNode().put("invalid", "config"), Map.of()))
                .build();

        stepsInvalidModule.put(stepInvalidModule.stepName(), stepInvalidModule);
        PipelineConfig pipeline1 = new PipelineConfig("pipelineWithBadModule", stepsInvalidModule);

        Map<String, PipelineStepConfig> stepsInvalidTopic = new HashMap<>();

        // Create a KafkaInputDefinition with a non-whitelisted listen topic
        List<KafkaInputDefinition> kafkaInputs = List.of(
            KafkaInputDefinition.builder()
                .listenTopics(List.of("non-whitelisted-listen-topic"))
                .consumerGroupId("test-group")
                .build()
        );

        // Create a step with the non-whitelisted kafka input and invalid output target
        Map<String, PipelineStepConfig.OutputTarget> invalidOutputs = new HashMap<>();
        invalidOutputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("non-existent-step")
                .transportType(TransportType.INTERNAL)
                .build());

        PipelineStepConfig stepInvalidTopic = PipelineStepConfig.builder()
                .stepName("stepBadTopic")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("actual-module-id", null))
                .kafkaInputs(kafkaInputs)
                .outputs(invalidOutputs)
                .build();

        stepsInvalidTopic.put(stepInvalidTopic.stepName(), stepInvalidTopic);
        PipelineConfig pipeline2 = new PipelineConfig("pipelineWithBadTopic", stepsInvalidTopic);

        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        modules.put("actual-module-id", new PipelineModuleConfiguration("Actual Module", "actual-module-id", null));
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(pipeline1.name(), pipeline1);
        pipelines.put(pipeline2.name(), pipeline2);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);

        Set<String> allowedKafkaTopics = Collections.singleton("some-other-topic");
        Set<String> allowedGrpcServices = Collections.emptySet();

        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName("test-cluster-multi-error")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName("default-pipeline")
                .allowedKafkaTopics(allowedKafkaTopics)
                .allowedGrpcServices(allowedGrpcServices)
                .build();

        // Use a schema content provider that returns empty Optional for the non-existent schema
        // but returns a valid schema for other schemas
        ValidationResult result = validator.validate(config, ref -> {
            if (ref != null && ref.subject() != null && ref.subject().contains("non-existent-schema")) {
                return Optional.empty(); // Return empty for non-existent schema
            }
            return Optional.of("{}"); // Return valid schema for other schemas
        });

        assertFalse(result.isValid(), "Validation should fail due to multiple errors. Errors: " + result.errors());
        assertTrue(result.errors().size() >= 2, "Expected at least two errors from different rules.");

        // Print all errors for debugging
        System.out.println("[DEBUG_LOG] All errors: " + result.errors());

        boolean unknownModuleErrorFound = result.errors().stream()
                .anyMatch(e -> e.contains("references unknown implementationKey 'unknown-module-id'"));
        boolean schemaErrorFound = result.errors().stream()
                .anyMatch(e -> e.contains("non-existent-schema"));

        System.out.println("[DEBUG_LOG] unknownModuleErrorFound: " + unknownModuleErrorFound);
        System.out.println("[DEBUG_LOG] schemaErrorFound: " + schemaErrorFound);

        assertTrue(unknownModuleErrorFound, "Should find error for unknown module ID.");
        assertTrue(schemaErrorFound, "Should find error for non-existent schema.");
    }

    @Test
    void testValidatorHandlesRuleExceptionGracefully() {
        TestSpecificMisbehavingRule.wasCalled = false; // Reset static flag

        List<ClusterValidationRule> rulesForThisTest = new ArrayList<>(standardValidationRules);
        TestSpecificMisbehavingRule misbehavingRuleInstance = new TestSpecificMisbehavingRule();
        rulesForThisTest.add(misbehavingRuleInstance);

        DefaultConfigurationValidator testSpecificValidator = new DefaultConfigurationValidator(rulesForThisTest);

        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName("TestClusterForException")
                .defaultPipelineName("default-pipeline")
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();

        ValidationResult result = testSpecificValidator.validate(config, ref -> Optional.empty());

        assertFalse(result.isValid(), "Validation should fail when a rule throws an exception.");
        assertTrue(result.errors().size() >= 1, "Expected at least one error message about the exception.");

        boolean exceptionErrorFound = result.errors().stream()
                .anyMatch(e -> e.contains("Exception while applying validation rule TestSpecificMisbehavingRule") &&
                        e.contains("Simulated unexpected error in TestSpecificMisbehavingRule!"));
        assertTrue(exceptionErrorFound, "Error message should indicate an exception from TestSpecificMisbehavingRule. Errors: " + result.errors());
        assertTrue(TestSpecificMisbehavingRule.wasCalled, "TestSpecificMisbehavingRule's validate method should have been called.");
    }

    @Test
    void testValidateComplexButFullyValidConfig_returnsNoErrors() {
        // --- Modules ---
        SchemaReference schemaRef1 = new SchemaReference("schema-subject-1", 1);
        PipelineModuleConfiguration module1 = new PipelineModuleConfiguration("ModuleOne", "mod1_impl", schemaRef1);
        PipelineModuleConfiguration module2 = new PipelineModuleConfiguration("ModuleTwo", "mod2_impl", null); // No schema
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(
                module1.implementationId(), module1,
                module2.implementationId(), module2
        ));

        // --- Whitelists (Adjusted for the new topic structure) ---
        Set<String> allowedKafkaTopics = Set.of("input-topic", "p1s1-produces-topic", "p1s2-listens-topic", "output-topic");
        Set<String> allowedGrpcServices = Set.of("grpc-service-A", "mod1_impl", "mod2_impl");

        // --- Pipeline 1 Steps (Modified to use the new transport model) ---
        Map<String, PipelineStepConfig> p1Steps = new HashMap<>();

        // Create KafkaInputDefinition for p1s1
        List<KafkaInputDefinition> p1s1KafkaInputs = List.of(
            KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .consumerGroupId("p1s1-group")
                .build()
        );

        // Create output target for p1s1
        Map<String, PipelineStepConfig.OutputTarget> p1s1Outputs = new HashMap<>();
        p1s1Outputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("p1s2")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("p1s1-produces-topic")
                        .build())
                .build());

        // Create p1s1 step
        PipelineStepConfig p1s1 = PipelineStepConfig.builder()
                .stepName("p1s1")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("mod1_impl", null))
                .customConfigSchemaId("schema-subject-1")
                .customConfig(new PipelineStepConfig.JsonConfigOptions(OBJECT_MAPPER.createObjectNode().put("key", "value"), Map.of()))
                .kafkaInputs(p1s1KafkaInputs)
                .outputs(p1s1Outputs)
                .build();

        p1Steps.put(p1s1.stepName(), p1s1);

        // Create KafkaInputDefinition for p1s2
        List<KafkaInputDefinition> p1s2KafkaInputs = List.of(
            KafkaInputDefinition.builder()
                .listenTopics(List.of("p1s2-listens-topic"))
                .consumerGroupId("p1s2-group")
                .build()
        );

        // Create output target for p1s2
        Map<String, PipelineStepConfig.OutputTarget> p1s2Outputs = new HashMap<>();
        p1s2Outputs.put("default", PipelineStepConfig.OutputTarget.builder()
                .targetStepName("output")
                .transportType(TransportType.KAFKA)
                .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("output-topic")
                        .build())
                .build());

        // Create p1s2 step
        PipelineStepConfig p1s2 = PipelineStepConfig.builder()
                .stepName("p1s2")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("mod2_impl", null))
                .kafkaInputs(p1s2KafkaInputs)
                .outputs(p1s2Outputs)
                .build();

        p1Steps.put(p1s2.stepName(), p1s2);

        // Create output step
        PipelineStepConfig outputStep = PipelineStepConfig.builder()
                .stepName("output")
                .stepType(StepType.SINK)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("mod2_impl", null))
                .build();

        p1Steps.put(outputStep.stepName(), outputStep);

        PipelineConfig pipeline1 = new PipelineConfig("pipelineOne", p1Steps);

        // --- Pipeline 2 Steps (simple, using INTERNAL transport) ---
        Map<String, PipelineStepConfig> p2Steps = new HashMap<>();

        // Create p2s1 step
        PipelineStepConfig p2s1 = PipelineStepConfig.builder()
                .stepName("p2s1")
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("mod2_impl", null))
                .build();

        p2Steps.put(p2s1.stepName(), p2s1);
        PipelineConfig pipeline2 = new PipelineConfig("pipelineTwo", p2Steps);

        // --- Graph ---
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(
                pipeline1.name(), pipeline1,
                pipeline2.name(), pipeline2
        ));

        // --- Cluster Config ---
        PipelineClusterConfig config = PipelineClusterConfig.builder()
                .clusterName("complexValidCluster")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .defaultPipelineName("pipelineOne")
                .allowedKafkaTopics(allowedKafkaTopics)
                .allowedGrpcServices(allowedGrpcServices)
                .build();

        // --- Schema Provider ---
        String validSchemaContent = "{\"type\":\"object\", \"properties\":{\"key\":{\"type\":\"string\"}}, \"required\":[\"key\"]}";
        Function<SchemaReference, Optional<String>> schemaProvider = ref -> {
            if (ref.equals(schemaRef1)) {
                return Optional.of(validSchemaContent);
            }
            return Optional.empty();
        };

        // --- Validate ---
        ValidationResult result = validator.validate(config, schemaProvider);

        // --- Assert ---
        assertTrue(result.isValid(), "Complex configuration designed to be valid should produce no errors. Errors: " + result.errors());
        assertTrue(result.errors().isEmpty(), "Error list should be empty for this valid configuration. Errors: " + result.errors());
    }
}
