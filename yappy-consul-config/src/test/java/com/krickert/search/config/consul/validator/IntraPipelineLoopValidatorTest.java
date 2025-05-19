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

class IntraPipelineLoopValidatorTest {

    private IntraPipelineLoopValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    // --- Helper methods for creating model instances ---
    private ProcessorInfo internalBeanProcessor(String beanName) {
        return new ProcessorInfo(null, beanName);
    }

    private JsonConfigOptions emptyInnerJsonConfig() {
        return new JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }

    // Helper to create a PipelineStepConfig, defaulting many fields for loop tests
    private PipelineStepConfig createStep(String name, StepType type, ProcessorInfo processorInfo,
                                          List<KafkaInputDefinition> kafkaInputs,
                                          Map<String, OutputTarget> outputs) {
        return new PipelineStepConfig(
                name, type, "Test Step " + name, null, emptyInnerJsonConfig(),
                kafkaInputs != null ? kafkaInputs : Collections.emptyList(),
                outputs != null ? outputs : Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );
    }

    // Helper to create an OutputTarget for Kafka
    private OutputTarget kafkaOutput(String targetStepName, String topic) {
        return new OutputTarget(targetStepName, TransportType.KAFKA, null,
                new KafkaTransportConfig(topic, Collections.emptyMap()));
    }

    // Helper to create an OutputTarget for Internal/gRPC (if targetStepName is enough)
    private OutputTarget internalOutput(String targetStepName) {
        return new OutputTarget(targetStepName, TransportType.INTERNAL, null, null);
    }


    // Helper to create KafkaInputDefinition
    private KafkaInputDefinition kafkaInput(List<String> listenTopics) {
        return new KafkaInputDefinition(listenTopics, "test-cg-" + UUID.randomUUID(), Collections.emptyMap());
    }

    private KafkaInputDefinition kafkaInput(String listenTopic) {
        return kafkaInput(List.of(listenTopic));
    }


    // Updated createClusterConfig helper
    private PipelineClusterConfig createClusterConfig(String pipelineName, Map<String, PipelineStepConfig> steps) {
        PipelineConfig pipeline = new PipelineConfig(pipelineName, steps);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Collections.singletonMap(pipelineName, pipeline));
        return new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );
    }


    @BeforeEach
    void setUp() {
        validator = new IntraPipelineLoopValidator();
        schemaContentProvider = ref -> Optional.of("{}"); // Dummy provider
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should not produce errors");
    }

    @Test
    void validate_noPipelines_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster", new PipelineGraphConfig(Collections.emptyMap()), null, null, Collections.emptySet(), Collections.emptySet()
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Cluster with no pipelines should not produce errors");
    }

    @Test
    void validate_pipelineWithNoSteps_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = createClusterConfig("p1", Collections.emptyMap());
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Pipeline with no steps should not produce errors. Errors: " + errors);
    }

    @Test
    void validate_noLoops_returnsNoErrors() {
        ProcessorInfo pi1 = internalBeanProcessor("bean1");
        ProcessorInfo pi2 = internalBeanProcessor("bean2");

        PipelineStepConfig step1 = createStep("step1", StepType.INITIAL_PIPELINE, pi1,
                null, // No Kafka inputs
                Map.of("out", kafkaOutput("step2", "topic-s1-to-s2")) // Publishes to topic
        );
        PipelineStepConfig step2 = createStep("step2", StepType.PIPELINE, pi2,
                List.of(kafkaInput("topic-s1-to-s2")), // Listens to topic from step1
                Map.of("out", internalOutput("step3")) // Outputs internally or to a sink
        );
        PipelineStepConfig step3 = createStep("step3", StepType.SINK, internalBeanProcessor("bean3"),
                List.of(kafkaInput("some-other-input-for-sink")), // Sink might listen to a different topic or be targeted internally
                null
        );


        Map<String, PipelineStepConfig> steps = Map.of(
                step1.stepName(), step1,
                step2.stepName(), step2,
                step3.stepName(), step3
        );
        PipelineClusterConfig clusterConfig = createClusterConfig("pipeline-no-loop", steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Configuration with no loops should not produce errors. Errors: " + errors);
    }

    @Test
    void validate_selfLoop_returnsError() {
        String topic = "self-loop-topic";
        ProcessorInfo pi = internalBeanProcessor("bean-self");

        PipelineStepConfig step1 = createStep("step1", StepType.PIPELINE, pi,
                List.of(kafkaInput(topic)), // Listens to 'topic'
                Map.of("out", kafkaOutput("step1", topic))  // Publishes to 'topic' (targetStepName doesn't matter for Kafka loop as much as topic)
        );

        Map<String, PipelineStepConfig> steps = Map.of(step1.stepName(), step1);
        PipelineClusterConfig clusterConfig = createClusterConfig("pipeline-self-loop", steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Self-loop should produce an error.");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Intra-pipeline loop detected") && errors.get(0).contains("step1 -> step1"));
    }

    @Test
    void validate_directTwoStepLoop_returnsError() {
        String topic1to2 = "topic-1-to-2";
        String topic2to1 = "topic-2-to-1";
        ProcessorInfo pi1 = internalBeanProcessor("bean1");
        ProcessorInfo pi2 = internalBeanProcessor("bean2");

        PipelineStepConfig step1 = createStep("step1", StepType.PIPELINE, pi1,
                List.of(kafkaInput(topic2to1)), // Listens to topic from step2
                Map.of("out", kafkaOutput("step2", topic1to2))  // Publishes to topic for step2
        );
        PipelineStepConfig step2 = createStep("step2", StepType.PIPELINE, pi2,
                List.of(kafkaInput(topic1to2)), // Listens to topic from step1
                Map.of("out", kafkaOutput("step1", topic2to1))  // Publishes to topic for step1
        );

        Map<String, PipelineStepConfig> steps = Map.of(step1.stepName(), step1, step2.stepName(), step2);
        PipelineClusterConfig clusterConfig = createClusterConfig("pipeline-two-step-loop", steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Two-step loop should produce an error.");
        // The cycle path might be step1 -> step2 -> step1 or step2 -> step1 -> step2 depending on JGraphT's algorithm
        assertTrue(errors.get(0).contains("Intra-pipeline loop detected"));
        assertTrue(errors.get(0).contains("step1") && errors.get(0).contains("step2"));
    }

    @Test
    void validate_threeStepLoop_returnsError() {
        String t12 = "t12";
        String t23 = "t23";
        String t31 = "t31";
        ProcessorInfo p1 = internalBeanProcessor("b1");
        ProcessorInfo p2 = internalBeanProcessor("b2");
        ProcessorInfo p3 = internalBeanProcessor("b3");

        PipelineStepConfig s1 = createStep("s1", StepType.PIPELINE, p1, List.of(kafkaInput(t31)), Map.of("out", kafkaOutput("s2", t12)));
        PipelineStepConfig s2 = createStep("s2", StepType.PIPELINE, p2, List.of(kafkaInput(t12)), Map.of("out", kafkaOutput("s3", t23)));
        PipelineStepConfig s3 = createStep("s3", StepType.PIPELINE, p3, List.of(kafkaInput(t23)), Map.of("out", kafkaOutput("s1", t31)));

        Map<String, PipelineStepConfig> steps = Map.of(s1.stepName(), s1, s2.stepName(), s2, s3.stepName(), s3);
        PipelineClusterConfig clusterConfig = createClusterConfig("pipeline-three-step-loop", steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Three-step loop should produce an error.");
        assertTrue(errors.get(0).contains("Intra-pipeline loop detected"));
        assertTrue(errors.get(0).contains("s1 -> s2 -> s3 -> s1") || // Order might vary
                errors.get(0).contains("s2 -> s3 -> s1 -> s2") ||
                errors.get(0).contains("s3 -> s1 -> s2 -> s3"));
    }

    @Test
    void validate_topicResolutionWithPlaceholders_noLoop() {
        ProcessorInfo pi1 = internalBeanProcessor("beanRes1");
        ProcessorInfo pi2 = internalBeanProcessor("beanRes2");

        // Step1 publishes to "pipeline-res-loop.${stepName}.out" which resolves to "pipeline-res-loop.stepRes1.out"
        PipelineStepConfig step1 = createStep("stepRes1", StepType.PIPELINE, pi1,
                null,
                Map.of("out", kafkaOutput("stepRes2", "pipeline-res-loop.${stepName}.out"))
        );
        // Step2 listens to "pipeline-res-loop.stepRes1.out" (explicitly, no placeholder here)
        PipelineStepConfig step2 = createStep("stepRes2", StepType.PIPELINE, pi2,
                List.of(kafkaInput("pipeline-res-loop.stepRes1.out")),
                null
        );

        Map<String, PipelineStepConfig> steps = Map.of(step1.stepName(), step1, step2.stepName(), step2);
        PipelineClusterConfig clusterConfig = createClusterConfig("pipeline-res-loop", steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Should be no loop when topics resolve correctly. Errors: " + errors);
    }

    @Test
    void validate_topicResolutionWithPlaceholders_formsLoop() {
        ProcessorInfo pi1 = internalBeanProcessor("beanLoopRes1");
        ProcessorInfo pi2 = internalBeanProcessor("beanLoopRes2");
        String pipelineName = "pipeline-placeholder-loop";

        // Step1 publishes to "loop-topic-${pipelineName}" -> "loop-topic-pipeline-placeholder-loop"
        // Step2 listens to "loop-topic-${pipelineName}"   -> "loop-topic-pipeline-placeholder-loop"
        // Step2 publishes to "another-topic"
        // Step1 listens to "another-topic"

        PipelineStepConfig step1 = createStep("s1loopRes", StepType.PIPELINE, pi1,
                List.of(kafkaInput("another-topic")),
                Map.of("out", kafkaOutput("s2loopRes", "loop-topic-${pipelineName}"))
        );
        PipelineStepConfig step2 = createStep("s2loopRes", StepType.PIPELINE, pi2,
                List.of(kafkaInput("loop-topic-${pipelineName}")),
                Map.of("out", kafkaOutput("s1loopRes", "another-topic"))
        );

        Map<String, PipelineStepConfig> steps = Map.of(step1.stepName(), step1, step2.stepName(), step2);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipelineName, steps);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Loop formed by placeholder resolution should be detected.");
        assertTrue(errors.get(0).contains("Intra-pipeline loop detected"));
    }
}