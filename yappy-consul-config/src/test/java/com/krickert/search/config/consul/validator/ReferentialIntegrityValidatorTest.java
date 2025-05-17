package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.pipeline.model.*;
// Explicit model imports
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
// import org.junit.jupiter.params.ParameterizedTest; // Not used in the provided test, can remove if still unused
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

    // Uses the 3-arg helper constructor in PipelineStepConfig
    private PipelineStepConfig createBasicStep(String name, StepType type, ProcessorInfo processorInfo) {
        return new PipelineStepConfig(name, type, processorInfo);
    }

    // Uses the full canonical constructor for PipelineStepConfig
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
                0, 1000L, 30000L, 2.0, null,
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
        return new KafkaInputDefinition(listenTopics, "test-cg-" + UUID.randomUUID().toString().substring(0,8), 
                                        kafkaConsumerProps != null ? kafkaConsumerProps : Collections.emptyMap());
    }
    private KafkaInputDefinition kafkaInput(String listenTopic) {
        return kafkaInput(List.of(listenTopic), null);
    }


    @BeforeEach
    void setUp() {
        validator = new ReferentialIntegrityValidator();
        schemaContentProvider = ref -> Optional.of("{}"); // Dummy provider, not used by this validator
        availableModules = new HashMap<>();
    }

    private PipelineClusterConfig buildClusterConfig(Map<String, PipelineConfig> pipelines) {
        return buildClusterConfigWithModules(pipelines, new PipelineModuleMap(new HashMap<>(availableModules))); // Pass a copy
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
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("map key 'pipeline-key-mismatch' does not match its name field 'pipeline-actual-name'")));
    }

    @Test
    void validate_duplicatePipelineName_returnsError() {
        PipelineConfig p1 = new PipelineConfig("duplicate-name", Collections.emptyMap());
        PipelineConfig p2 = new PipelineConfig("duplicate-name", Collections.emptyMap()); // Same name
        Map<String, PipelineConfig> pipelines = Map.of("p1key", p1, "p2key", p2);
        PipelineClusterConfig clusterConfig = buildClusterConfig(pipelines);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate pipeline name 'duplicate-name' found")));
    }
    
    @Test
    void validate_pipelineNameNull_returnsError() {
        // The PipelineConfig constructor now prevents null/blank names.
        // This test now effectively tests the record's own validation.
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, Collections.emptyMap()));
    }

    @Test
    void validate_pipelineNameBlank_returnsError() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig("  ", Collections.emptyMap()));
    }


    // --- Step Name and Key Tests ---
    @Test
    void validate_stepKeyMismatchWithName_returnsError() {
        PipelineStepConfig s1 = createBasicStep("step-actual-name", StepType.PIPELINE, internalBeanProcessor("bean1"));
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("step-key-mismatch", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("map key 'step-key-mismatch' does not match its stepName field 'step-actual-name'")));
    }

    @Test
    void validate_duplicateStepName_returnsError() {
        ProcessorInfo proc = internalBeanProcessor("bean-dup");
        PipelineStepConfig s1 = createBasicStep("duplicate-step", StepType.PIPELINE, proc);
        PipelineStepConfig s2 = createBasicStep("duplicate-step", StepType.PIPELINE, proc); // Same name
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1key", s1, "s2key", s2));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate stepName 'duplicate-step' found")));
    }
    
    @Test
    void validate_stepNameNull_returnsErrorFromModel() {
        // PipelineStepConfig constructor now prevents null/blank names.
        assertThrows(NullPointerException.class, () -> new PipelineStepConfig(null, StepType.PIPELINE, internalBeanProcessor("b1")));
    }

    @Test
    void validate_stepNameBlank_returnsErrorFromModel() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig("  ", StepType.PIPELINE, internalBeanProcessor("b1")));
    }


    // --- ProcessorInfo and Module Linkage Tests ---
    @Test
    void validate_unknownImplementationId_returnsError() {
        // "unknown-bean" not in availableModules
        PipelineStepConfig s1 = createBasicStep("s1", StepType.PIPELINE, internalBeanProcessor("unknown-bean"));
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        // availableModules is empty for this test setup via buildClusterConfig
        PipelineClusterConfig clusterConfig = buildClusterConfigWithModules(Map.of("p1", p1), new PipelineModuleMap(Collections.emptyMap()));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("references unknown implementationKey 'unknown-bean'")));
    }

    @Test
    void validate_customConfigPresent_moduleHasNoSchemaRef_stepNoSchemaId_returnsError() {
        String moduleImplId = "module-no-schema";
        availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module No Schema", moduleImplId, null)); // No schema ref

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                null, null, jsonConfigWithData("key", "val"), null); // Custom config, no step schemaId
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1)); // availableModules is used

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Error expected when custom config present but no schema locatable");
        assertTrue(errors.stream().anyMatch(e -> e.contains("has customConfig but its module 'module-no-schema' does not define a customConfigSchemaReference")));
    }
    
    @Test
    void validate_stepCustomConfigSchemaIdDiffersFromModule_logsWarningNoErrorsFromThisValidator() {
        String moduleImplId = "module-with-schema";
        SchemaReference moduleSchema = new SchemaReference("module-subject", 1);
        availableModules.put(moduleImplId, new PipelineModuleConfiguration("Module With Schema", moduleImplId, moduleSchema));

        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor(moduleImplId),
                null, null, jsonConfigWithData("key","val"), "step-specific-schema-id:2"); // Step provides its own schemaId
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Differing schema IDs (step vs module) should only log a warning by RefIntValidator if module schema also exists. Errors: " + errors);
        // Note: The validator's LOG.warn behavior isn't asserted here, only that no *error* is added.
    }


    // --- Output Target Validation Tests ---
    @Test
    void validate_outputTargetToUnknownStep_returnsError() {
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"),
                null, Map.of("next", internalOutputTo("unknown-step")), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1)); // "unknown-step" does not exist
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("output 'next' contains reference to unknown targetStepName 'unknown-step'")));
    }

    @Test
    void validate_validOutputTarget_returnsNoErrors() {
        ProcessorInfo proc1 = internalBeanProcessor("bean1");
        ProcessorInfo proc2 = internalBeanProcessor("bean2");
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, proc1,
                null, Map.of("next", internalOutputTo("s2")), null, null);
        PipelineStepConfig s2 = createBasicStep("s2", StepType.SINK, proc2); // Target step "s2" exists
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1, "s2", s2));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid output target reference should not produce errors. Errors: " + errors);
    }
    
    @Test
    void validate_outputTargetWithNullTargetStepName_returnsErrorFromModel() {
        // OutputTarget constructor should prevent null/blank targetStepName
        assertThrows(NullPointerException.class, () -> new OutputTarget(null, TransportType.INTERNAL, null, null));
    }
    @Test
    void validate_outputTargetWithBlankTargetStepName_returnsErrorFromModel() {
        assertThrows(IllegalArgumentException.class, () -> new OutputTarget("  ", TransportType.INTERNAL, null, null));
    }


    // --- Transport Properties Validation (Kafka/gRPC properties in Outputs) ---
    @Test
    void validate_kafkaOutputPropertiesWithNullKey_returnsError() {
        Map<String, String> kafkaProps = new HashMap<>();
        kafkaProps.put(null, "value1");
        OutputTarget output = kafkaOutputTo("t1", "topic", kafkaProps);
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.PIPELINE, internalBeanProcessor("bean1"), null, Map.of("out", output), null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaTransport.kafkaProducerProperties contains a null or blank key")));
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
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("grpcTransport.grpcClientProperties contains a null or blank key")));
    }
    
    // --- KafkaInputDefinition Properties Validation ---
    @Test
    void validate_kafkaInputPropertiesWithNullKey_returnsError() {
        Map<String, String> kafkaConsumerProps = new HashMap<>();
        kafkaConsumerProps.put(null, "value1");
        KafkaInputDefinition inputDef = kafkaInput(List.of("input-topic"), kafkaConsumerProps);
        
        PipelineStepConfig s1 = createDetailedStep("s1", StepType.SINK, internalBeanProcessor("beanSink"), List.of(inputDef), null, null, null);
        PipelineConfig p1 = new PipelineConfig("p1", Map.of("s1", s1));
        PipelineClusterConfig clusterConfig = buildClusterConfig(Map.of("p1", p1));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Expected error for null key in kafkaConsumerProperties. Errors: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("kafkaInput #1 kafkaConsumerProperties contains a null or blank key")), "Error message content mismatch.");
    }

    // --- Fully Valid Configuration Test ---
    @Test
    void validate_fullyValidConfiguration_returnsNoErrors() {
        String module1Id = "module1-impl";
        SchemaReference schema1 = new SchemaReference("module1-schema-subject", 1);
        availableModules.put(module1Id, new PipelineModuleConfiguration("Module One Display", module1Id, schema1));

        String module2Id = "module2-impl";
        availableModules.put(module2Id, new PipelineModuleConfiguration("Module Two Display", module2Id, null)); // Module with no schema

        String sinkBeanId = "sink-bean-impl";
        availableModules.put(sinkBeanId, new PipelineModuleConfiguration("Sink Bean Display", sinkBeanId, null));


        PipelineStepConfig s1 = createDetailedStep(
                "s1-initial", StepType.INITIAL_PIPELINE, internalBeanProcessor(module1Id),
                null, // no kafka inputs
                Map.of("next_step", internalOutputTo("s2-process")), // output
                jsonConfigWithData("name", "s1Config"), // custom config
                schema1.toIdentifier() // custom config schema id (matches module's schema)
        );

        PipelineStepConfig s2 = createDetailedStep(
            "s2-process", StepType.PIPELINE, internalBeanProcessor(module2Id),
            List.of(kafkaInput("s1-output-topic")), // kafka inputs
            Map.of("to_sink", kafkaOutputTo("s3-sink", "s2-to-s3-topic", Map.of("producer.acks","all"))), // output with kafka props
            null, // no custom config
            null  // no custom config schema id
        );

        PipelineStepConfig s3 = createDetailedStep(
            "s3-sink", StepType.SINK, internalBeanProcessor(sinkBeanId),
            List.of(kafkaInput(List.of("s2-to-s3-topic"), Map.of("fetch.min.bytes","1024"))), // kafka inputs with props
            null, // no outputs
            null, null
        );

        PipelineConfig p1 = new PipelineConfig("p1-valid", Map.of(s1.stepName(), s1, s2.stepName(), s2, s3.stepName(), s3));
        PipelineClusterConfig clusterConfig = buildClusterConfigWithModules(Map.of("p1-valid", p1), new PipelineModuleMap(availableModules));

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Fully valid configuration should produce no errors. Errors: " + errors);
    }
}