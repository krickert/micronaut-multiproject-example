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

class InterPipelineLoopValidatorTest {

    private InterPipelineLoopValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    // --- Helper methods for creating model instances (reused from Intra test) ---
    private ProcessorInfo internalBeanProcessor(String beanName) {
        return new ProcessorInfo(null, beanName);
    }

    private JsonConfigOptions emptyInnerJsonConfig() {
        return new JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }

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

    private OutputTarget kafkaOutput(String targetStepNameWithinSamePipeline, String topic) {
        return new OutputTarget(targetStepNameWithinSamePipeline != null ? targetStepNameWithinSamePipeline : "kafka-target",
                TransportType.KAFKA, null,
                new KafkaTransportConfig(topic, Collections.emptyMap()));
    }

    private KafkaInputDefinition kafkaInput(List<String> listenTopics) {
        return new KafkaInputDefinition(listenTopics, "test-cg-" + UUID.randomUUID().toString().substring(0, 8), Collections.emptyMap());
    }

    private KafkaInputDefinition kafkaInput(String listenTopic) {
        return kafkaInput(List.of(listenTopic));
    }

    private PipelineConfig createPipeline(String pipelineName, Map<String, PipelineStepConfig> steps) {
        return new PipelineConfig(pipelineName, steps);
    }

    private PipelineClusterConfig createClusterConfig(Map<String, PipelineConfig> pipelines) {
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        return new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet()  // allowedGrpcServices
        );
    }

    // Specific helper for InterPipelineLoopValidatorTest to create a cluster config with a specific name
    private PipelineClusterConfig createNamedClusterConfig(String clusterName, Map<String, PipelineConfig> pipelines) {
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        return new PipelineClusterConfig(
                clusterName,
                graphConfig,
                null, null, Collections.emptySet(), Collections.emptySet()
        );
    }


    @BeforeEach
    void setUp() {
        validator = new InterPipelineLoopValidator();
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should result in no errors from this validator.");
    }

    @Test
    void validate_noPipelines_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = createClusterConfig(Collections.emptyMap());
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Cluster with no pipelines should result in no errors.");
    }

    @Test
    void validate_singlePipelineNoInternalKafkaActivity_returnsNoErrors() {
        ProcessorInfo pi1 = internalBeanProcessor("p1s1-proc");
        // Step with no Kafka inputs or outputs
        PipelineStepConfig step1 = createStep("p1s1", StepType.INITIAL_PIPELINE, pi1, null, null);

        PipelineConfig pipeline1 = createPipeline("p1-single", Map.of(step1.stepName(), step1));
        PipelineClusterConfig clusterConfig = createClusterConfig(Map.of("p1-single", pipeline1));
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Single pipeline with no Kafka activity should have no inter-pipeline errors. Errors: " + errors);
    }


    @Test
    void validate_twoPipelinesNoLoop_returnsNoErrors() {
        // P_A publishes to topicA
        // P_B listens to topicA (P_A --> P_B)
        // P_B publishes to topicB (which P_A does not listen to)
        ProcessorInfo pA_s1_proc = internalBeanProcessor("pA_s1_proc");
        PipelineStepConfig pA_s1 = createStep("pA_s1", StepType.PIPELINE, pA_s1_proc,
                null, // No Kafka inputs for this step in pA
                Map.of("out", kafkaOutput(null, "topicA")) // pA publishes topicA
        );
        PipelineConfig pipelineA = createPipeline("pipelineA", Map.of(pA_s1.stepName(), pA_s1));

        ProcessorInfo pB_s1_proc = internalBeanProcessor("pB_s1_proc");
        PipelineStepConfig pB_s1 = createStep("pB_s1", StepType.PIPELINE, pB_s1_proc,
                List.of(kafkaInput("topicA")), // pB listens to topicA
                Map.of("out", kafkaOutput(null, "topicB")) // pB publishes topicB
        );
        PipelineConfig pipelineB = createPipeline("pipelineB", Map.of(pB_s1.stepName(), pB_s1));

        Map<String, PipelineConfig> pipelines = Map.of("pipelineA", pipelineA, "pipelineB", pipelineB);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipelines);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Two pipelines with P_A -> P_B flow should not have loops. Errors: " + errors);
    }

    @Test
    void validate_twoPipelinesWithDirectLoop_returnsError() {
        // P_A publishes to topicA, listens to topicB
        // P_B publishes to topicB, listens to topicA
        ProcessorInfo pA_s1_proc = internalBeanProcessor("pA_s1_proc");
        PipelineStepConfig pA_s1 = createStep("pA_s1", StepType.PIPELINE, pA_s1_proc,
                List.of(kafkaInput("topicB")), // pA listens to topicB
                Map.of("out", kafkaOutput(null, "topicA"))  // pA publishes topicA
        );
        PipelineConfig pipelineA = createPipeline("pipelineA", Map.of(pA_s1.stepName(), pA_s1));

        ProcessorInfo pB_s1_proc = internalBeanProcessor("pB_s1_proc");
        PipelineStepConfig pB_s1 = createStep("pB_s1", StepType.PIPELINE, pB_s1_proc,
                List.of(kafkaInput("topicA")), // pB listens to topicA
                Map.of("out", kafkaOutput(null, "topicB"))  // pB publishes topicB
        );
        PipelineConfig pipelineB = createPipeline("pipelineB", Map.of(pB_s1.stepName(), pB_s1));

        Map<String, PipelineConfig> pipelines = Map.of("pipelineA", pipelineA, "pipelineB", pipelineB);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipelines);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Direct loop between two pipelines should produce an error.");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Inter-pipeline loop detected"));
        assertTrue(errors.get(0).contains("pipelineA") && errors.get(0).contains("pipelineB"));
    }

    @Test
    void validate_threePipelinesWithLoop_returnsError() {
        // P_A -> topicA (to P_B)
        // P_B -> topicB (to P_C)
        // P_C -> topicC (to P_A)
        ProcessorInfo pA_proc = internalBeanProcessor("pA_proc");
        PipelineStepConfig pA_s1 = createStep("pA_s1", StepType.PIPELINE, pA_proc,
                List.of(kafkaInput("topicC")), Map.of("out", kafkaOutput(null, "topicA")));
        PipelineConfig pipelineA = createPipeline("pipelineA", Map.of(pA_s1.stepName(), pA_s1));

        ProcessorInfo pB_proc = internalBeanProcessor("pB_proc");
        PipelineStepConfig pB_s1 = createStep("pB_s1", StepType.PIPELINE, pB_proc,
                List.of(kafkaInput("topicA")), Map.of("out", kafkaOutput(null, "topicB")));
        PipelineConfig pipelineB = createPipeline("pipelineB", Map.of(pB_s1.stepName(), pB_s1));

        ProcessorInfo pC_proc = internalBeanProcessor("pC_proc");
        PipelineStepConfig pC_s1 = createStep("pC_s1", StepType.PIPELINE, pC_proc,
                List.of(kafkaInput("topicB")), Map.of("out", kafkaOutput(null, "topicC")));
        PipelineConfig pipelineC = createPipeline("pipelineC", Map.of(pC_s1.stepName(), pC_s1));

        Map<String, PipelineConfig> pipelines = Map.of(
                pipelineA.name(), pipelineA,
                pipelineB.name(), pipelineB,
                pipelineC.name(), pipelineC
        );
        PipelineClusterConfig clusterConfig = createClusterConfig(pipelines);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Three-pipeline loop should produce an error.");
        assertTrue(errors.get(0).contains("Inter-pipeline loop detected"));
        assertTrue(errors.get(0).contains("pipelineA -> pipelineB -> pipelineC -> pipelineA") ||
                errors.get(0).contains("pipelineB -> pipelineC -> pipelineA -> pipelineB") ||
                errors.get(0).contains("pipelineC -> pipelineA -> pipelineB -> pipelineC"));
    }

    @Test
    void validate_pipelinePublishesAndListensToSameTopic_interLoopDetected() {
        // P_A publishes to "shared-topic"
        // P_A also listens to "shared-topic"
        // InterPipelineLoopValidator will create an edge P_A -> P_A if any step in P_A publishes a topic
        // that any step (even the same one) in P_A listens to.
        ProcessorInfo pA_proc = internalBeanProcessor("pA_proc");
        PipelineStepConfig pA_s1 = createStep("pA_s1", StepType.PIPELINE, pA_proc,
                List.of(kafkaInput("shared-topic")),
                Map.of("out", kafkaOutput(null, "shared-topic"))
        );
        PipelineConfig pipelineA = createPipeline("pipelineA", Map.of(pA_s1.stepName(), pA_s1));

        Map<String, PipelineConfig> pipelines = Map.of(pipelineA.name(), pipelineA);
        PipelineClusterConfig clusterConfig = createClusterConfig(pipelines);
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Pipeline publishing to and listening from the same topic should form a P_A -> P_A loop.");
        assertTrue(errors.get(0).contains("Inter-pipeline loop detected"));
        assertTrue(errors.get(0).contains("pipelineA -> pipelineA"));
    }

    @Test
    void validate_topicResolutionWithPlaceholders_interPipelineLoop() {
        String clusterName = "cluster-ph"; // Passed to resolvePattern in validator
        String pipelineAName = "pipelineA-ph";
        String pipelineBName = "pipelineB-ph";

        // P_A listens to "topic-${pipelineName}-from-B" (expects topic-pipelineB-ph-from-B after resolution in validator)
        // P_A publishes to "topic-${pipelineName}-from-A" (becomes topic-pipelineA-ph-from-A after resolution)
        ProcessorInfo paProc = internalBeanProcessor("paProc");
        PipelineStepConfig pAs1 = createStep("pAs1", StepType.PIPELINE, paProc,
                List.of(kafkaInput("topic-" + pipelineBName + "-from-B")), // Topic explicitly resolved for listener for clarity
                Map.of("out", kafkaOutput(null, "topic-" + pipelineAName + "-from-A")) // Topic explicitly resolved for publisher
        );
        // To test placeholder resolution, let's make the configured topics contain placeholders.
        // The validator's resolvePattern will use the *pipelineName* of the step's parent pipeline.
        pAs1 = createStep("pAs1", StepType.PIPELINE, paProc,
                List.of(kafkaInput("topic-${pipelineName}-from-B")), // Listens: topic-pipelineA-ph-from-B (using pA's name)
                Map.of("out", kafkaOutput(null, "topic-${pipelineName}-from-A"))  // Publishes: topic-pipelineA-ph-from-A
        );
        // This setup above won't cause a loop as P_A listens to topic-pipelineA-ph-from-B and P_B (below) publishes topic-pipelineB-ph-from-B.
        // Let's adjust for a loop:
        // P_A listens to topic published BY P_B (e.g., "topic-from-pipelineB")
        // P_A publishes "topic-from-pipelineA"
        // P_B listens to "topic-from-pipelineA"
        // P_B publishes "topic-from-pipelineB"

        String topicFromA = "topic-from-${pipelineName}"; // will be "topic-from-pipelineA-ph" when published by P_A
        String topicFromB = "topic-from-${pipelineName}"; // will be "topic-from-pipelineB-ph" when published by P_B

        pAs1 = createStep("pAs1", StepType.PIPELINE, paProc,
                List.of(kafkaInput(topicFromB.replace("${pipelineName}", pipelineBName))), // P_A listens to concrete topic from P_B
                Map.of("out", kafkaOutput(null, topicFromA)) // P_A publishes with placeholder (resolves to its own name)
        );
        PipelineConfig pipelineA_config = createPipeline(pipelineAName, Map.of(pAs1.stepName(), pAs1));

        ProcessorInfo pbProc = internalBeanProcessor("pbProc");
        PipelineStepConfig pBs1 = createStep("pBs1", StepType.PIPELINE, pbProc,
                List.of(kafkaInput(topicFromA.replace("${pipelineName}", pipelineAName))), // P_B listens to concrete topic from P_A
                Map.of("out", kafkaOutput(null, topicFromB))  // P_B publishes with placeholder (resolves to its own name)
        );
        PipelineConfig pipelineB_config = createPipeline(pipelineBName, Map.of(pBs1.stepName(), pBs1));

        Map<String, PipelineConfig> pipelines = Map.of(pipelineAName, pipelineA_config, pipelineBName, pipelineB_config);
        PipelineClusterConfig clusterConfig = createNamedClusterConfig(clusterName, pipelines);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Inter-pipeline loop with resolved placeholder topics should be detected.");
        assertTrue(errors.get(0).contains("Inter-pipeline loop detected"));
        assertTrue(errors.get(0).contains(pipelineAName) && errors.get(0).contains(pipelineBName));
    }
}