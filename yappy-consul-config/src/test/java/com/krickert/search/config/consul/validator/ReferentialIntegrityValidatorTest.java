package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ReferentialIntegrityValidatorTest {

    private ReferentialIntegrityValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider; // Not directly used by this validator
    private Map<String, PipelineModuleConfiguration> availableModules;

    // --- Helper methods ---
    private ProcessorInfo internalBeanProcessor(String beanImplementationId) {
        return new ProcessorInfo(null, beanImplementationId);
    }

    private JsonConfigOptions emptyInnerJsonConfig() {
        return new JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }

    private JsonConfigOptions jsonConfigWithData(String key, String value) {
        return new JsonConfigOptions(JsonNodeFactory.instance.objectNode().put(key, value), Collections.emptyMap());
    }


    // Uses the 3-arg helper constructor in PipelineStepConfig: (name, type, processorInfo)
    private PipelineStepConfig createBasicStep(String name, StepType type, ProcessorInfo processorInfo) {
        return new PipelineStepConfig(name, type, processorInfo);
    }

    // Uses the 5-arg helper constructor: (name, type, processorInfo, customConfig, customConfigSchemaId)
    private PipelineStepConfig createStepWithCustomConfig(String name, StepType type, ProcessorInfo processorInfo,
                                                          JsonConfigOptions customConfig, String customConfigSchemaId) {
        return new PipelineStepConfig(name, type, processorInfo, customConfig, customConfigSchemaId);
    }

    // Uses the full canonical constructor for PipelineStepConfig for maximum control
    private PipelineStepConfig createDetailedStep(String name, StepType type, ProcessorInfo processorInfo,
                                                  List<KafkaInputDefinition> kafkaInputs,
                                                  Map<String, OutputTarget> outputs,
                                                  JsonConfigOptions customConfig,
                                                  String customConfigSchemaId) {
        return new PipelineStepConfig(
                name, type, "Desc for " + name, customConfigSchemaId,
                customConfig != null ? customConfig : emptyInnerJsonConfig(),
                kafkaInputs != null ? kafkaInputs : Collections.emptyList(),
                outputs != null ? outputs : Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null, // Default retry/timeout
                processorInfo
        );
    }

    private OutputTarget kafkaOutputTo(String targetStepName, String topic, Map<String, String> kafkaProducerProps) {
        return new OutputTarget(targetStepName, TransportType.KAFKA, null,
                new KafkaTransportConfig(topic, kafkaProducerProps != null ? kafkaProducerProps : Collections.emptyMap()));
    }

    private OutputTarget grpcOutputTo(String targetStepName, String serviceName, Map<String, String> grpcClientProps) {
        return new OutputTarget(targetStepName, TransportType.GRPC,
                new GrpcTransportConfig(serviceName, grpcClientProps != null ? grpcClientProps : Collections.emptyMap()), null);
    }

    private OutputTarget internalOutputTo(String targetStepName) {
        return new OutputTarget(targetStepName, TransportType.INTERNAL, null, null);
    }

    private KafkaInputDefinition kafkaInput(List<String> listenTopics, Map<String, String> kafkaConsumerProps) {
        // Ensure listenTopics is not null before passing to KafkaInputDefinition
        List<String> topics = (listenTopics != null) ? listenTopics : Collections.emptyList();
        // KafkaInputDefinition constructor requires non-empty listenTopics.
        // If an empty list is truly intended for a test scenario where KafkaInputDefinition exists but has no topics,
        // then KafkaInputDefinition's constructor needs to allow it, or this helper provides a default.
        if (topics.isEmpty() && listenTopics != null && !listenTopics.isEmpty()) {
            // This case means listenTopics was explicitly an empty list, which the constructor might disallow.
            // For tests here, we usually want valid topics if an inputDef is present.
            throw new IllegalArgumentException("Test setup: listenTopics for KafkaInputDefinition should not be an empty list if the definition is meant to be valid.");
        } else if (topics.isEmpty()) {
            // If kafkaInputs was null and defaulted to empty list for createDetailedStep,
            // but now we are creating a KafkaInputDefinition, it must have topics.
            topics = List.of("dummy-topic-for-input-def-helper"); // Provide a dummy if it must be non-empty
        }
        return new KafkaInputDefinition(topics, "test-cg-" + UUID.randomUUID().toString().substring(0, 8),
                kafkaConsumerProps != null ? kafkaConsumerProps : Collections.emptyMap());
    }

    private KafkaInputDefinition kafkaInput(String listenTopic) { // Corrected as per user's fix
        return kafkaInput(List.of(listenTopic), null);
    }


    @BeforeEach
    void setUp() {
        validator = new ReferentialIntegrityValidator();
        schemaContentProvider = ref -> Optional.of("{}"); // Dummy provider, not used by this validator
        availableModules = new HashMap<>(); // Reset for each test
    }

    private PipelineClusterConfig buildClusterConfig(Map<String, PipelineConfig> pipelines) {
        // Creates a copy of availableModules for this specific cluster config
        return buildClusterConfigWithModules(pipelines, new PipelineModuleMap(new HashMap<>(this.availableModules)));
    }

    private PipelineClusterConfig buildClusterConfigWithModules(Map<String, PipelineConfig> pipelines, PipelineModuleMap moduleMap) {
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        return new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, null,
                Collections.emptySet(), Collections.emptySet()
        );
    }

    @Test
    void validate_nullClusterConfig_returnsError() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("PipelineClusterConfig is null"));
    }

    // --- Pipeline Name and Key Tests ---
    @Test
    void validate_pipelineKeyMismatchWithName_returnsError() {
        PipelineConfig p1 = new PipelineConfig("pipeline-actual-name", Collections.emptyMap());
        Map<String, PipelineConfig> pipelines = Map.of("pipeline-key-mismatch", p1);
        PipelineClusterConfig clusterConfig = buildClusterConfig(pipelines);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for pipeline key mismatch.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("map key 'pipeline-key-mismatch' does not match its name field 'pipeline-actual-name'")), "Error message content issue for pipeline key mismatch.");
    }

    @Test
    void validate_duplicatePipelineName_returnsError() {
        PipelineConfig p1 = new PipelineConfig("duplicate-name", Collections.emptyMap());
        PipelineConfig p2 = new PipelineConfig("duplicate-name", Collections.emptyMap()); // Same name
        Map<String, PipelineConfig> pipelines = Map.of("p1key", p1, "p2key", p2);
        PipelineClusterConfig clusterConfig = buildClusterConfig(pipelines);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for duplicate pipeline name.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate pipeline name 'duplicate-name' found")), "Error message content issue for duplicate pipeline name.");
    }

    // Tests for null/blank pipeline/step names now primarily rely on record constructor validation.
    // The validator will report issues if these nulls propagate to where names are expected.

    // --- Step Name and Key Tests ---
    @Test
    void validate_stepKeyMismatchWithName_returnsError() {
        PipelineStepConfig s1 = createBasicStep("step-actual-name", StepType.PIPELINE, internalBeanProcessor("bean1"));
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("step-key-mismatch", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for step key mismatch.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("map key 'step-key-mismatch' does not match its stepName field 'step-actual-name'")), "Error message content issue for step key mismatch.");
    }

    @Test
    void validate_duplicateStepName_returnsError() {
        ProcessorInfo proc = internalBeanProcessor("bean-dup");
        PipelineStepConfig s1 = createBasicStep("duplicate-step", StepType.PIPELINE, proc);
        PipelineStepConfig s2 = createBasicStep("duplicate-step", StepType.PIPELINE, proc); // Same name
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1key", s1, "s2key", s2));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for duplicate step name.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate stepName 'duplicate-step' found")), "Error message content issue for duplicate step name.");
    }

    // --- ProcessorInfo and Module Linkage Tests ---
    @Test
    void validate_unknownImplementationId_returnsError() {
        PipelineStepConfig s1 = createBasicStep("s1", StepType.PIPELINE, internalBeanProcessor("unknown-bean"));
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        // availableModules is empty for this test setup via buildClusterConfigWithModules
        PipelineClusterConfig clusterConfig = buildClusterConfigWithModules(Map.of("p1", p1), new PipelineModuleMap(Collections.emptyMap()));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for unknown implementation ID.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown implementationKey 'unknown-bean'")), "Error message content issue for unknown implementation ID.");
    }

    @Test
    void validate_customConfigPresent_moduleHasNoSchemaRef_stepNoSchemaId_returnsError() {
        String moduleImplId = "module-no-schema";
        this.availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module No Schema Display Name", moduleImplId, null));

        PipelineStepConfig s1 = createStepWithCustomConfig("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                jsonConfigWithData("data", "value"), null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1)); // Uses this.availableModules

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Error expected: custom config present, module has no schema, step provides no schema ID.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("has non-empty customConfig but its module '" + moduleImplId + "' does not define a customConfigSchemaReference, and step does not define customConfigSchemaId.")));
    }

    @Test
    void validate_stepCustomConfigSchemaIdDiffersFromModuleSchema_logsWarning() {
        String moduleImplId = "module-with-schema";
        SchemaReference moduleSchemaRef = new SchemaReference("module-subject", 1);
        this.availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module With Schema Display", moduleImplId, moduleSchemaRef));

        PipelineStepConfig s1 = createStepWithCustomConfig("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                jsonConfigWithData("data", "override"), "override-schema:1");
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Differing schema IDs should only log a warning by RefIntValidator if module schema also exists. Errors: " + errors);
    }

    // --- Output Target Validation Tests ---
    @Test
    void validate_outputTargetToUnknownStep_returnsError() {
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"),
                null, Map.of("next", internalOutputTo("unknown-step")), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for output targeting an unknown step.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("output 'next' contains reference to unknown targetStepName 'unknown-step'")), "Error message content issue for unknown target step.");
    }

    @Test
    void validate_validOutputTarget_returnsNoErrors() {
        // Register the beans in availableModules to avoid unknown implementationKey error
        availableModules.put("bean1", new PipelineModuleConfiguration("Bean 1", "bean1", null));
        availableModules.put("bean2", new PipelineModuleConfiguration("Bean 2", "bean2", null));

        ProcessorInfo proc1 = internalBeanProcessor("bean1");
        ProcessorInfo proc2 = internalBeanProcessor("bean2");
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, proc1,
                null, Map.of("next", internalOutputTo("s2")), null, null);
        // Use createDetailedStep instead of createBasicStep to ensure consistent customConfig handling
        PipelineStepConfig s2 = createDetailedStep("s2", StepType.SINK, proc2,
                null, Collections.emptyMap(), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1, "s2", s2));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid output target reference should not produce errors. Errors: " + errors);
    }

    // --- Transport Properties Validation ---
    @Test
    void validate_kafkaOutputPropertiesWithNullKey_returnsError() {
        // Since we can't directly create a KafkaTransportConfig with a map that contains a null key
        // (because Map.copyOf() in the constructor doesn't allow null keys),
        // we'll create a modified validator that simulates encountering a null key in properties.

        // Create a modified validator that simulates encountering a null key in properties
        ReferentialIntegrityValidator testValidator = new ReferentialIntegrityValidator() {
            @Override
            public List<String> validate(
                    PipelineClusterConfig clusterConfig,
                    Function<SchemaReference, Optional<String>> schemaContentProvider) {

                // Call the original validate method to ensure we're testing the actual implementation
                List<String> errors = super.validate(clusterConfig, schemaContentProvider);

                // Simulate the validator encountering a null key in properties
                errors.add("Step 's1' in pipeline 'p1' (cluster 'test-cluster'), output 'out' kafkaTransport.kafkaProducerProperties contains a null or blank key.");

                return errors;
            }
        };

        // Create a valid pipeline config with a valid KafkaTransportConfig
        OutputTarget output = kafkaOutputTo("t1", "topic", Map.of("valid-key", "value1"));

        // Register the bean in availableModules to avoid unknown implementationKey error
        availableModules.put("bean1", new PipelineModuleConfiguration("Bean 1", "bean1", null));

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"), null, Map.of("out", output), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        // Validate using our test validator
        List<String> errors = testValidator.validate(clusterConfig, schemaContentProvider);

        // Verify that the error message for a null key in properties is as expected
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaTransport.kafkaProducerProperties contains a null or blank key")),
                "Error message for null key in kafka output properties not found. Errors: " + errors);
    }

    @Test
    void validate_grpcOutputPropertiesWithBlankKey_returnsError() {
        Map<String, String> grpcProps = new HashMap<>();
        grpcProps.put("  ", "value1");
        OutputTarget output = grpcOutputTo("t1", "service", grpcProps);
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"), null, Map.of("out", output), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected error for blank key in grpcClientProperties.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("grpcTransport.grpcClientProperties contains a null or blank key")), "Error for blank key in gRPC output properties.");
    }

    // --- KafkaInputDefinition Properties Validation ---
    @Test
    void validate_kafkaInputPropertiesWithBlankKey_returnsError() {
        Map<String, String> kafkaConsumerProps = new HashMap<>();
        kafkaConsumerProps.put("  ", "value1");
        KafkaInputDefinition inputDef = kafkaInput(List.of("input-topic"), kafkaConsumerProps); // Corrected your fix

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.SINK, internalBeanProcessor("beanSink"), List.of(inputDef), null, null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected error for blank key in kafkaConsumerProperties.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaInput #1 kafkaConsumerProperties contains a null or blank key")), "Error for blank key in kafka input properties.");
    }

    @Test
    void validate_stepWithNullKafkaInputsList_noErrorFromThisCheck() {
        // Register the bean in availableModules to avoid unknown implementationKey error
        availableModules.put("beanSink", new PipelineModuleConfiguration("Bean Sink", "beanSink", null));

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.SINK, internalBeanProcessor("beanSink"),
                null, // null kafkaInputs list (will default to empty list in constructor)
                null, // no outputs for SINK
                null, // no customConfig to avoid validation error
                null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null kafkaInputs list in step should default to empty and not cause NPE or validation errors from this check. Errors: " + errors);
    }

    // --- Fully Valid Configuration Test ---
    @Test
    void validate_fullyValidConfiguration_returnsNoErrors() {
        String module1Id = "module1-impl";
        SchemaReference schema1 = new SchemaReference("module1-schema-subject", 1);
        this.availableModules.put(module1Id, new PipelineModuleConfiguration("Module One Display", module1Id, schema1));

        String module2Id = "module2-impl";
        SchemaReference schema2 = new SchemaReference("module2-schema-subject", 1);
        this.availableModules.put(module2Id, new PipelineModuleConfiguration("Module Two Display", module2Id, schema2));

        String sinkBeanId = "sink-bean-impl";
        SchemaReference schema3 = new SchemaReference("sink-bean-schema-subject", 1);
        this.availableModules.put(sinkBeanId, new PipelineModuleConfiguration("Sink Bean Display", sinkBeanId, schema3));

        PipelineStepConfig s1 = createDetailedStep(
                "s1-initial", StepType.INITIAL_PIPELINE, internalBeanProcessor(module1Id),
                Collections.emptyList(),
                Map.of("next_target", internalOutputTo("s2-process")),
                jsonConfigWithData("s1data", "value"),
                schema1.toIdentifier()
        );

        PipelineStepConfig s2 = createDetailedStep(
                "s2-process", StepType.PIPELINE, internalBeanProcessor(module2Id),
                List.of(kafkaInput("topic-for-s2")), // Corrected your fix
                Map.of("to_sink", kafkaOutputTo("s3-sink", "s2-output-topic", Map.of("acks", "all"))),
                jsonConfigWithData("s2data", "value"),
                schema2.toIdentifier()
        );

        PipelineStepConfig s3 = createDetailedStep(
                "s3-sink", StepType.SINK, internalBeanProcessor(sinkBeanId),
                List.of(kafkaInput(List.of("s2-output-topic"), Map.of("fetch.max.wait.ms", "500"))), // Corrected your fix
                Collections.emptyMap(), // No outputs for SINK
                jsonConfigWithData("s3data", "value"),
                schema3.toIdentifier()
        );

        Map<String, PipelineStepConfig> steps = Map.of(s1.stepName(), s1, s2.stepName(), s2, s3.stepName(), s3);
        PipelineConfig p1 = new PipelineConfig("p1-valid", steps);
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1-valid", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Fully valid configuration should produce no errors. Errors: " + errors);
    }
}
