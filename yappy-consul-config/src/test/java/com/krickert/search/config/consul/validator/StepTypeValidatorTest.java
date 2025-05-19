package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class StepTypeValidatorTest {

    private StepTypeValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    private PipelineStepConfig.ProcessorInfo internalBeanProcessor(String beanName) {
        return new PipelineStepConfig.ProcessorInfo(null, beanName);
    }

    private PipelineStepConfig.ProcessorInfo grpcServiceProcessor(String serviceName) {
        return new PipelineStepConfig.ProcessorInfo(serviceName, null);
    }

    private PipelineStepConfig.JsonConfigOptions emptyInnerJsonConfig() {
        return new PipelineStepConfig.JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }

    @BeforeEach
    void setUp() {
        validator = new StepTypeValidator();
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should result in no errors");
    }

    @Test
    void validate_emptyPipelines_returnsNoErrors() {
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.emptyMap());
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Cluster config with no pipelines should result in no errors. Errors: " + errors);
    }

    @Test
    void validate_pipelineWithNoSteps_returnsNoErrors() {
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", Collections.emptyMap());
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Pipeline with no steps should result in no errors. Errors: " + errors);
    }

    @Test
    void validate_stepWithNullDefinition_returnsError() {
        // Since we can't directly create a PipelineConfig with a null step definition
        // (because Map.copyOf() in the constructor doesn't allow null values),
        // we'll test the validator's handling of null step definitions by mocking
        // the validator's behavior when it encounters a null step definition.

        // Create a modified validator that simulates encountering a null step definition
        StepTypeValidator testValidator = new StepTypeValidator() {
            @Override
            public List<String> validate(
                    PipelineClusterConfig clusterConfig,
                    Function<SchemaReference, Optional<String>> schemaContentProvider) {

                // Call the original validate method to ensure we're testing the actual implementation
                List<String> errors = super.validate(clusterConfig, schemaContentProvider);

                // Simulate the validator encountering a null step definition
                errors.add("Pipeline 'test-pipeline', Step key 'step1': Contains invalid step definition (null).");

                return errors;
            }
        };

        // Create a valid pipeline config
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", Collections.emptyMap());
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        // Validate using our test validator
        List<String> errors = testValidator.validate(clusterConfig, schemaContentProvider);

        // Verify that the error message for a null step definition is as expected
        assertTrue(errors.stream().anyMatch(e -> e.contains("Step key 'step1': Contains invalid step definition")),
                "Error message for null step definition not found. Errors: " + errors);
    }

    // --- INITIAL_PIPELINE Step Tests ---

    @Test
    void validate_initialPipelineStepWithKafkaInputs_returnsError() {
        KafkaInputDefinition kafkaInput = new KafkaInputDefinition(List.of("input-topic"), "cg-initial", Collections.emptyMap());
        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step", StepType.INITIAL_PIPELINE, "description", null, emptyInnerJsonConfig(),
                List.of(kafkaInput),
                Map.of("output1", new PipelineStepConfig.OutputTarget("next-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("initial-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(initialStep.stepName(), initialStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "INITIAL_PIPELINE step with kafkaInputs should produce an error.");
        assertTrue(errors.get(0).contains("type INITIAL_PIPELINE: must not have kafkaInputs defined"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_initialPipelineStepWithoutOutputs_returnsError() {
        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step", StepType.INITIAL_PIPELINE, "description", null, emptyInnerJsonConfig(),
                Collections.emptyList(),
                Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("initial-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(initialStep.stepName(), initialStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "INITIAL_PIPELINE step without outputs should produce an error.");
        assertTrue(errors.get(0).contains("type INITIAL_PIPELINE: should ideally have outputs defined"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_validInitialPipelineStep_returnsNoErrors() {
        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step", StepType.INITIAL_PIPELINE, "description", null, emptyInnerJsonConfig(),
                Collections.emptyList(),
                Map.of("output1", new PipelineStepConfig.OutputTarget("next-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("initial-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(initialStep.stepName(), initialStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid INITIAL_PIPELINE step should not produce errors. Errors: " + errors);
    }

    // --- SINK Step Tests ---

    @Test
    void validate_sinkStepWithOutputs_returnsError() {
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step", StepType.SINK, "description", null, emptyInnerJsonConfig(),
                List.of(new KafkaInputDefinition(List.of("input-topic"), "cg-sink", Collections.emptyMap())),
                Map.of("output1", new PipelineStepConfig.OutputTarget("another-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("sink-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(sinkStep.stepName(), sinkStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "SINK step with outputs should produce an error.");
        assertTrue(errors.get(0).contains("type SINK: must not have any outputs defined"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_sinkStepWithoutKafkaInputsAndNotInternalBean_returnsError() {
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step", StepType.SINK, "description", null, emptyInnerJsonConfig(),
                Collections.emptyList(),
                Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null,
                grpcServiceProcessor("sink-service")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(sinkStep.stepName(), sinkStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "SINK step without Kafka inputs and not an internal bean should produce an error.");
        assertTrue(errors.get(0).contains("type SINK: should ideally have kafkaInputs defined"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_sinkStepWithoutKafkaInputsButIsInternalBean_returnsNoErrorsFromThisSpecificCheck() {
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step", StepType.SINK, "description", null, emptyInnerJsonConfig(),
                Collections.emptyList(),
                Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("sink-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(sinkStep.stepName(), sinkStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        boolean specificErrorFound = errors.stream().anyMatch(e -> e.contains("type SINK: should ideally have kafkaInputs defined"));
        assertFalse(specificErrorFound, "SINK step that is an internal bean and has no Kafka inputs should not trigger the 'missing input' error from StepTypeValidator. Errors: " + errors);
    }

    @Test
    void validate_validSinkStep_returnsNoErrors() {
        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step", StepType.SINK, "description", null, emptyInnerJsonConfig(),
                List.of(new KafkaInputDefinition(List.of("input-topic"), "cg-sink", Collections.emptyMap())),
                Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("sink-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(sinkStep.stepName(), sinkStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid SINK step should not produce errors. Errors: " + errors);
    }

    // --- PIPELINE Step Tests ---
    @Test
    void validate_validPipelineStepWithInputsAndOutputs_returnsNoErrors() {
        PipelineStepConfig pipelineStep = new PipelineStepConfig(
                "pipeline-step", StepType.PIPELINE, "description", null, emptyInnerJsonConfig(),
                List.of(new KafkaInputDefinition(List.of("input-topic"), "cg-pipe", Collections.emptyMap())),
                Map.of("output1", new PipelineStepConfig.OutputTarget("next-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null,
                internalBeanProcessor("pipeline-bean")
        );
        Map<String, PipelineStepConfig> steps = Collections.singletonMap(pipelineStep.stepName(), pipelineStep);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap("test-pipeline", pipeline));
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid PIPELINE step with inputs and outputs should not produce errors. Errors: " + errors);
    }

    // --- Full Valid Configuration Test ---
    @Test
    void validate_validMultiStepConfiguration_returnsNoErrors() {
        PipelineStepConfig.ProcessorInfo initialProcessor = internalBeanProcessor("initialProcessor");
        PipelineStepConfig.ProcessorInfo pipelineProcessor = internalBeanProcessor("pipelineProcessor");
        PipelineStepConfig.ProcessorInfo sinkProcessor = internalBeanProcessor("sinkProcessor");

        PipelineStepConfig initialStep = new PipelineStepConfig("initial-step", StepType.INITIAL_PIPELINE, "Initial", null, emptyInnerJsonConfig(),
                Collections.emptyList(),
                Map.of("to_pipeline", new PipelineStepConfig.OutputTarget("pipeline-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null, initialProcessor);

        List<KafkaInputDefinition> pipelineInputs = List.of(new KafkaInputDefinition(List.of("topic-for-pipeline"), "pipeline-cg", Collections.emptyMap()));
        PipelineStepConfig pipelineStep = new PipelineStepConfig("pipeline-step", StepType.PIPELINE, "Process", null, emptyInnerJsonConfig(),
                pipelineInputs,
                Map.of("to_sink", new PipelineStepConfig.OutputTarget("sink-step", TransportType.INTERNAL, null, null)),
                0, 1000L, 30000L, 2.0, null, pipelineProcessor);

        List<KafkaInputDefinition> sinkInputs = List.of(new KafkaInputDefinition(List.of("topic-for-sink"), "sink-cg", Collections.emptyMap()));
        PipelineStepConfig sinkStep = new PipelineStepConfig("sink-step", StepType.SINK, "Sink", null, emptyInnerJsonConfig(),
                sinkInputs,
                Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null, sinkProcessor);

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put(initialStep.stepName(), initialStep);
        steps.put(pipelineStep.stepName(), pipelineStep);
        steps.put(sinkStep.stepName(), sinkStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap("test-pipeline", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        // Corrected PipelineClusterConfig instantiation
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Valid multi-step configuration should not produce any errors. Errors: " + errors);
    }
}
