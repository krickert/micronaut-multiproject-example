package com.krickert.search.config.consul;

import com.krickert.search.config.consul.validator.ClusterValidationRule;
import com.krickert.search.config.consul.validator.CustomConfigSchemaValidator;
import com.krickert.search.config.consul.validator.ReferentialIntegrityValidator;
import com.krickert.search.config.consul.validator.WhitelistValidator;
import com.krickert.search.config.pipeline.model.*;
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
        PipelineClusterConfig config = new PipelineClusterConfig("TestClusterSimple");
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
            new PipelineClusterConfig( // This line will throw
                    "", // Blank cluster name
                    new PipelineGraphConfig(Collections.emptyMap()),
                    new PipelineModuleMap(Collections.emptyMap()),
                    Collections.emptySet(),
                    Collections.emptySet()
            );
        });
        assertEquals("PipelineClusterConfig clusterName cannot be null or blank.", exception.getMessage());
    }


    @Test
    void testValidateInvalidConfigFromInjectedRule() {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
                "step1", "non-existent-module", null, null, null, null,
                null, null
        );
        steps.put("step1", step);
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster-invalid-module", graphConfig, moduleMap,
                Collections.emptySet(),
                Collections.emptySet()
        );
        ValidationResult result = validator.validate(config, ref -> Optional.empty()); // Use the injected validator
        assertFalse(result.isValid(), "Validation should fail for a configuration with an invalid implementation ID. Errors: " + result.errors());
        assertTrue(result.errors().size() >= 1, "There should be at least one validation error");
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("references unknown pipelineImplementationId 'non-existent-module'")),
                "At least one error should indicate an unknown implementation ID");
    }
    @Test
    void testValidateConfigWithMultipleRuleViolations() {
        Map<String, PipelineStepConfig> stepsInvalidModule = new HashMap<>();
        PipelineStepConfig stepInvalidModule = new PipelineStepConfig(
                "stepBadModule", "unknown-module-id", null, null, null, null, null, null
        );
        stepsInvalidModule.put(stepInvalidModule.pipelineStepId(), stepInvalidModule);
        PipelineConfig pipeline1 = new PipelineConfig("pipelineWithBadModule", stepsInvalidModule);

        Map<String, PipelineStepConfig> stepsInvalidTopic = new HashMap<>();
        PipelineStepConfig stepInvalidTopic = new PipelineStepConfig(
                "stepBadTopic", "actual-module-id",
                null,
                List.of("non-whitelisted-listen-topic"),
                null, null, null, null
        );
        stepsInvalidTopic.put(stepInvalidTopic.pipelineStepId(), stepInvalidTopic);
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

        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster-multi-error", graphConfig, moduleMap, allowedKafkaTopics, allowedGrpcServices
        );

        ValidationResult result = validator.validate(config, ref -> Optional.of("{}")); // Use the injected validator

        assertFalse(result.isValid(), "Validation should fail due to multiple errors. Errors: " + result.errors());
        assertTrue(result.errors().size() >= 2, "Expected at least two errors from different rules.");

        boolean unknownModuleErrorFound = result.errors().stream()
                .anyMatch(e -> e.contains("references unknown pipelineImplementationId 'unknown-module-id'"));
        boolean whitelistErrorFound = result.errors().stream()
                .anyMatch(e -> e.contains("listens to non-whitelisted Kafka topic 'non-whitelisted-listen-topic'"));

        assertTrue(unknownModuleErrorFound, "Should find error for unknown module ID.");
        assertTrue(whitelistErrorFound, "Should find error for non-whitelisted Kafka topic.");
    }

    @Test
    void testValidatorHandlesRuleExceptionGracefully() {
        TestSpecificMisbehavingRule.wasCalled = false; // Reset static flag

        List<ClusterValidationRule> rulesForThisTest = new ArrayList<>(standardValidationRules);
        TestSpecificMisbehavingRule misbehavingRuleInstance = new TestSpecificMisbehavingRule();
        rulesForThisTest.add(misbehavingRuleInstance);

        DefaultConfigurationValidator testSpecificValidator = new DefaultConfigurationValidator(rulesForThisTest);

        PipelineClusterConfig config = new PipelineClusterConfig("TestClusterForException");
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
        Set<String> allowedGrpcServices = Set.of("grpc-service-A");

        // --- Pipeline 1 Steps (Modified to avoid InterPipelineLoopValidator self-loop) ---
        Map<String, PipelineStepConfig> p1Steps = new HashMap<>();
        p1Steps.put("p1s1", new PipelineStepConfig("p1s1", "mod1_impl",
                new JsonConfigOptions("{\"key\":\"value\"}"), // Has custom config
                List.of("input-topic"), // Listens
                List.of(new KafkaPublishTopic("p1s1-produces-topic")), // Publishes to a distinct topic for p1s1
                null, // grpcForwardTo
                List.of("p1s2"), // nextSteps
                null  // errorSteps
        ));
        // p1s2 now listens to a *different* topic than what p1s1 produces,
        // or no Kafka topic if the data flow is purely logical via nextSteps.
        // To make it clearly distinct for InterPipelineLoopValidator:
        p1Steps.put("p1s2", new PipelineStepConfig("p1s2", "mod2_impl", null,
                List.of("p1s2-listens-topic"), // Listens to its own distinct input topic
                List.of(new KafkaPublishTopic("output-topic")),
                List.of("grpc-service-A"),
                null, // no next Kafka step modeled here
                null));
        PipelineConfig pipeline1 = new PipelineConfig("pipelineOne", p1Steps);

        // --- Pipeline 2 Steps (simple, no kafka/grpc) ---
        Map<String, PipelineStepConfig> p2Steps = new HashMap<>();
        p2Steps.put("p2s1", new PipelineStepConfig("p2s1", "mod2_impl", null, null, null, null, null, null));
        PipelineConfig pipeline2 = new PipelineConfig("pipelineTwo", p2Steps);

        // --- Graph ---
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(
                pipeline1.name(), pipeline1,
                pipeline2.name(), pipeline2
        ));

        // --- Cluster Config ---
        PipelineClusterConfig config = new PipelineClusterConfig(
                "complexValidCluster", graphConfig, moduleMap, allowedKafkaTopics, allowedGrpcServices
        );

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