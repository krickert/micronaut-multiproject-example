package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.pipeline.model.*;
// Explicit model imports
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// import org.junit.jupiter.params.ParameterizedTest; // Not used in the provided test
// import org.junit.jupiter.params.provider.ValueSource; // Not used

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
        List<String> topics = (listenTopics != null) ? listenTopics : Collections.emptyList();
        // KafkaInputDefinition constructor requires non-empty listenTopics.
        // If an empty list is truly intended for a test scenario where KafkaInputDefinition exists but has no topics,
        // then KafkaInputDefinition's constructor needs to allow it, or this helper provides a default.
        // For now, assuming tests will provide valid non-empty lists if KafkaInputDefinition is created.
        if (topics.isEmpty() && listenTopics != null) { // If an empty list was explicitly passed, respect it if constructor allows
            // throw new IllegalArgumentException("Test setup: KafkaInputDefinition listenTopics cannot be empty if provided.");
            // Or provide a dummy if constructor strictly requires non-empty
            // For this validator, we are testing properties *within* a valid KafkaInputDefinition, so topics should be valid.
        }
        if (topics.isEmpty()) { // if kafkaInputs was null and defaulted to empty, provide a dummy for constructor
            topics = List.of("dummy-topic-for-input-test");
        }


        return new KafkaInputDefinition(topics, "test-cg-" + UUID.randomUUID().toString().substring(0, 8),
                kafkaConsumerProps != null ? kafkaConsumerProps : Collections.emptyMap());
    }

    private KafkaInputDefinition kafkaInput(String listenTopic) {
        return kafkaInput(List.of(listenTopic), null);
    }


    @BeforeEach
    void setUp() {
        validator = new ReferentialIntegrityValidator();
        schemaContentProvider = ref -> Optional.of("{}");
        availableModules = new HashMap<>();
    }

    private PipelineClusterConfig buildClusterConfig(Map<String, PipelineConfig> pipelines) {
        return buildClusterConfigWithModules(pipelines, new PipelineModuleMap(new HashMap<>(this.availableModules))); // Use instance availableModules
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
        PipelineConfig p2 = new PipelineConfig("duplicate-name", Collections.emptyMap());
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
        PipelineStepConfig s2 = createBasicStep("duplicate-step", StepType.PIPELINE, proc);
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
        PipelineClusterConfig clusterConfig = buildClusterConfigWithModules(Map.of("p1", p1), new PipelineModuleMap(Collections.emptyMap())); // No modules in availableModules

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected errors for unknown implementation ID.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown implementationKey 'unknown-bean'")), "Error message content issue for unknown implementation ID.");
    }

    @Test
    void validate_customConfigPresent_moduleHasNoSchemaRef_stepNoSchemaId_returnsError() {
        String moduleImplId = "module-no-schema";
        // availableModules is an instance variable, populated here for this test.
        this.availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module No Schema Display Name", moduleImplId, null));

        PipelineStepConfig s1 = createStepWithCustomConfig("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                jsonConfigWithData("data", "value"), null); // Step has custom config, no step-specific schemaId
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1)); // buildClusterConfig uses this.availableModules

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Error expected: custom config present, module has no schema, step provides no schema ID.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("has customConfig but its module '" + moduleImplId + "' does not define a customConfigSchemaReference, and step does not define customConfigSchemaId.")));
    }

    @Test
    void validate_stepCustomConfigSchemaIdDiffersFromModuleSchema_logsWarning() {
        String moduleImplId = "module-with-schema";
        SchemaReference moduleSchemaRef = new SchemaReference("module-subject", 1);
        this.availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module With Schema Display", moduleImplId, moduleSchemaRef));

        PipelineStepConfig s1 = createStepWithCustomConfig("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                jsonConfigWithData("data", "override"), "override-schema:1"); // Step provides its own schemaId
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
        ProcessorInfo proc1 = internalBeanProcessor("bean1");
        ProcessorInfo proc2 = internalBeanProcessor("bean2");
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, proc1,
                null, Map.of("next", internalOutputTo("s2")), null, null);
        PipelineStepConfig s2 = createBasicStep("s2", StepType.SINK, proc2);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1, "s2", s2));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid output target reference should not produce errors. Errors: " + errors);
    }

    // --- Transport Properties Validation ---
    @Test
    void validate_kafkaOutputPropertiesWithNullKey_returnsError() {
        Map<String, String> kafkaProps = new HashMap<>();
        kafkaProps.put(null, "value1");
        OutputTarget output = kafkaOutputTo("t1", "topic", kafkaProps);
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"), null, Map.of("out", output), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected error for null key in kafkaProducerProperties.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaTransport.kafkaProducerProperties contains a null or blank key")), "Error for null key in kafka output properties.");
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
        KafkaInputDefinition inputDef = kafkaInput(List.of("input-topic"), kafkaConsumerProps);

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.SINK, internalBeanProcessor("beanSink"), List.of(inputDef), null, null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected error for blank key in kafkaConsumerProperties.");
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaInput #1 kafkaConsumerProperties contains a null or blank key")), "Error for blank key in kafka input properties.");
    }

    // Test to ensure KafkaInputDefinition's listenTopics (if empty after construction attempts) are handled
    // The KafkaInputDefinition constructor requires non-empty list, so this tests if validator handles null KafkaInputDefinition list.
    @Test
    void validate_stepWithNullKafkaInputsList_noErrorFromThisCheck() {
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.SINK, internalBeanProcessor("beanSink"),
                null, // null kafkaInputs list
                null, null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null kafkaInputs list in step should not cause NPE in validator. Errors: " + errors);
    }


    // --- Fully Valid Configuration Test ---
    @Test
    void validate_fullyValidConfiguration_returnsNoErrors() {
        String module1Id = "module1-impl";
        SchemaReference schema1 = new SchemaReference("module1-schema-subject", 1);
        this.availableModules.put(module1Id, new PipelineModuleConfiguration("Module One Display", module1Id, schema1));

        String module2Id = "module2-impl";
        this.availableModules.put(module2Id, new PipelineModuleConfiguration("Module Two Display", module2Id, null));

        String sinkBeanId = "sink-bean-impl";
        this.availableModules.put(sinkBeanId, new PipelineModuleConfiguration("Sink Bean Display", sinkBeanId, null));


        PipelineStepConfig s1 = createDetailedStep(
                "s1-initial", StepType.INITIAL_PIPELINE, internalBeanProcessor(module1Id),
                Collections.emptyList(),
                Map.of("next_target", internalOutputTo("s2-process")),
                jsonConfigWithData("s1data", "value"),
                schema1.toIdentifier()
        );

        PipelineStepConfig s2 = createDetailedStep(
                "s2-process", StepType.PIPELINE, internalBeanProcessor(module2Id),
                List.of(kafkaInput("topic-for-s2")),
                Map.of("to_sink", kafkaOutputTo("s3-sink", "s2-output-topic", Map.of("acks", "all"))),
                null,
                null
        );

        PipelineStepConfig s3 = createDetailedStep(
                "s3-sink", StepType.SINK, internalBeanProcessor(sinkBeanId),
                List.of(kafkaInput(List.of("s2-output-topic"), Map.of("fetch.max.wait.ms", "500"))),
                Collections.emptyMap(),
                null, null
        );

        Map<String, PipelineStepConfig> steps = Map.of(s1.stepName(), s1, s2.stepName(), s2, s3.stepName(), s3);
        PipelineConfig p1 = new PipelineConfig("p1-valid", steps);
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1-valid", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Fully valid configuration should produce no errors. Errors: " + errors);
    }
}