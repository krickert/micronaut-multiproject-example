package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*; // Wildcard for models
// Explicit imports for clarity if needed, especially for nested records
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions;


import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class WhitelistValidatorTest {

    private WhitelistValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    // --- Helper methods for creating model instances ---
    private ProcessorInfo internalBeanProcessor(String beanName) {
        return new ProcessorInfo(null, beanName);
    }

    private JsonConfigOptions emptyInnerJsonConfig() {
        return new JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap());
    }

    // Updated createTestClusterConfig helper method
    private PipelineClusterConfig createTestClusterConfig(
            String clusterName,
            String pipelineName,
            String stepName,
            List<KafkaInputDefinition> stepKafkaInputs,
            Map<String, OutputTarget> stepOutputs,
            StepType stepType,
            ProcessorInfo processorInfo,
            Set<String> allowedKafkaTopics,
            Set<String> allowedGrpcServices) {

        // Using the 5-argument helper constructor from PipelineStepConfig
        // (stepName, stepType, processorInfo, customConfig, customConfigSchemaId)
        // This helper constructor then fills in the defaults for other fields like kafkaInputs, outputs, retries etc.
        // However, our test needs to control stepKafkaInputs and stepOutputs.
        // So, we must call the CANONICAL constructor or a helper that allows setting these.

        // The helper constructor for (name, type, processor, customConfig, customConfigSchemaId)
        // itself sets kafkaInputs and outputs to empty. This is not what we want here.
        // We need to use the full canonical constructor.

        PipelineStepConfig step = new PipelineStepConfig(
                stepName,
                stepType,
                "Test step description for " + stepName, // description
                null, // customConfigSchemaId - let's assume null for these tests unless specified
                emptyInnerJsonConfig(), // customConfig
                stepKafkaInputs != null ? stepKafkaInputs : Collections.emptyList(), // kafkaInputs
                stepOutputs != null ? stepOutputs : Collections.emptyMap(),          // outputs
                0,       // default maxRetries
                1000L,   // default retryBackoffMs
                30000L,  // default maxRetryBackoffMs
                2.0,     // default retryBackoffMultiplier
                null,    // default stepTimeoutMs
                processorInfo // processorInfo
        );

        Map<String, PipelineStepConfig> steps = Collections.singletonMap(step.stepName(), step);
        PipelineConfig pipeline = new PipelineConfig(pipelineName, steps);
        Map<String, PipelineConfig> pipelines = Collections.singletonMap(pipeline.name(), pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);

        return new PipelineClusterConfig(
                clusterName,
                graphConfig,
                null,
                null,
                allowedKafkaTopics,
                allowedGrpcServices
        );
    }


    @BeforeEach
    void setUp() {
        validator = new WhitelistValidator();
        schemaContentProvider = ref -> Optional.of("{}"); // Dummy provider
    }

    @Test
    void validate_allWhitelistedKafkaAndGrpc_returnsNoErrors() {
        String pipelineName = "p1";
        String stepName = "s1";
        String conventionalTopic = "yappy.pipeline." + pipelineName + ".step." + stepName + ".output";

        Set<String> allowedKafkaTopics = Set.of("explicit-topic", conventionalTopic);
        Set<String> allowedGrpcServices = Set.of("grpc-service1");

        OutputTarget kafkaOutput = new OutputTarget(
                "next-kafka-step",
                TransportType.KAFKA,
                null, // grpcTransport
                new KafkaTransportConfig(conventionalTopic, Collections.emptyMap()) // kafkaTransport
        );
        OutputTarget grpcOutput = new OutputTarget(
                "next-grpc-step",
                TransportType.GRPC,
                new GrpcTransportConfig("grpc-service1", Collections.emptyMap()), // grpcTransport
                null // kafkaTransport
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName,
                null, // kafkaInputs
                Map.of("kafkaOut", kafkaOutput, "grpcOut", grpcOutput), // outputs
                StepType.PIPELINE, // stepType
                internalBeanProcessor("bean-" + stepName), // processorInfo
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "All whitelisted Kafka topics and gRPC services should result in no errors. Errors: " + errors);
    }

    @Test
    void validate_kafkaTopicNotWhitelistedAndNotConventional_returnsError() {
        String pipelineName = "p1";
        String stepName = "s1";
        String nonWhitelistedTopic = "some-other-topic";

        Set<String> allowedKafkaTopics = Set.of("explicit-topic"); // Does not contain nonWhitelistedTopic
        Set<String> allowedGrpcServices = Set.of("grpc-service1");

        OutputTarget kafkaOutput = new OutputTarget(
                "next-kafka-step",
                TransportType.KAFKA,
                null,
                new KafkaTransportConfig(nonWhitelistedTopic, Collections.emptyMap())
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName,
                null,
                Map.of("kafkaOut", kafkaOutput),
                StepType.PIPELINE,
                internalBeanProcessor("bean-" + stepName),
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Non-whitelisted and non-conventional Kafka topic should produce an error.");
        assertTrue(errors.get(0).contains("publishes to topic '" + nonWhitelistedTopic + "' which is not whitelisted"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_grpcServiceNotWhitelisted_returnsError() {
        String pipelineName = "p1";
        String stepName = "s1";
        String nonWhitelistedService = "unknown-grpc-service";

        Set<String> allowedKafkaTopics = Set.of("any-topic");
        Set<String> allowedGrpcServices = Set.of("grpc-service1"); // Does not contain nonWhitelistedService

        OutputTarget grpcOutput = new OutputTarget(
                "next-grpc-step",
                TransportType.GRPC,
                new GrpcTransportConfig(nonWhitelistedService, Collections.emptyMap()),
                null
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName,
                null,
                Map.of("grpcOut", grpcOutput),
                StepType.PIPELINE,
                internalBeanProcessor("bean-" + stepName),
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty(), "Non-whitelisted gRPC service should produce an error.");
        assertTrue(errors.get(0).contains("uses non-whitelisted serviceName '" + nonWhitelistedService + "'"), "Error message mismatch. Got: " + errors.get(0));
    }

    @Test
    void validate_topicMatchesConvention_returnsNoErrorsEvenIfNotInExplicitList() {
        String pipelineName = "pipeX";
        String stepName = "stepY";
        // This topic follows the convention defined in WhitelistValidator
        String conventionalTopic = "yappy.pipeline." + pipelineName + ".step." + stepName + ".output";

        Set<String> allowedKafkaTopics = Set.of("some-other-explicit-topic"); // Explicit list does NOT contain conventionalTopic
        Set<String> allowedGrpcServices = Collections.emptySet();

        OutputTarget kafkaOutput = new OutputTarget(
                "next-step",
                TransportType.KAFKA,
                null,
                new KafkaTransportConfig(conventionalTopic, Collections.emptyMap())
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName,
                null,
                Map.of("kafkaOut", kafkaOutput),
                StepType.PIPELINE,
                internalBeanProcessor("bean-" + stepName),
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Topic matching convention should be permitted even if not in explicit list. Errors: " + errors);
    }
    
    @Test
    void validate_topicWithResolvablePlaceholdersMatchingConvention_returnsNoErrors() {
        String pipelineName = "pipeRes";
        String stepName = "stepRes";
        String topicWithPlaceholders = "yappy.pipeline.${pipelineName}.step.${stepName}.output";
        // WhitelistValidator's isKafkaTopicPermitted should resolve these placeholders

        Set<String> allowedKafkaTopics = Collections.emptySet(); // Not explicitly whitelisted
        Set<String> allowedGrpcServices = Collections.emptySet();

        OutputTarget kafkaOutput = new OutputTarget(
                "next-res-step",
                TransportType.KAFKA,
                null,
                new KafkaTransportConfig(topicWithPlaceholders, Collections.emptyMap())
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName, // These values will be used for resolving placeholders
                null,
                Map.of("kafkaOut", kafkaOutput),
                StepType.PIPELINE,
                internalBeanProcessor("bean-" + stepName),
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Topic with resolvable placeholders matching convention should be permitted. Errors: " + errors);
    }

    @Test
    void validate_topicWithResolvablePlaceholdersToExplicitWhitelist_returnsNoErrors() {
        String pipelineName = "pipeExpl";
        String stepName = "stepExpl";
        String resolvedTopic = "explicitly-allowed-topic";
        String topicWithPlaceholders = "prefix-${pipelineName}-${stepName}"; // Assume this resolves to "explicitly-allowed-topic"
                                                                    // For this test, we'll ensure it does by how we set it up.
                                                                    // The WhitelistValidator's isKafkaTopicPermitted will do the replace.
                                                                    // We need to make sure our WhitelistValidator's resolvePattern uses these exact placeholder names.
                                                                    // Let's assume `pipelineName` is "pipeExpl" and `stepName` is "stepExpl" making it "prefix-pipeExpl-stepExpl"
                                                                    // And "prefix-pipeExpl-stepExpl" is in the whitelist.
        String actualResolvedTopic = "prefix-" + pipelineName + "-" + stepName;


        Set<String> allowedKafkaTopics = Set.of(actualResolvedTopic);
        Set<String> allowedGrpcServices = Collections.emptySet();

        OutputTarget kafkaOutput = new OutputTarget(
                "next-expl-step",
                TransportType.KAFKA,
                null,
                new KafkaTransportConfig(topicWithPlaceholders, Collections.emptyMap())
        );

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                "test-cluster", pipelineName, stepName,
                null,
                Map.of("kafkaOut", kafkaOutput),
                StepType.PIPELINE,
                internalBeanProcessor("bean-" + stepName),
                allowedKafkaTopics,
                allowedGrpcServices
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Topic with resolvable placeholders matching an explicit whitelist entry should be permitted. Errors: " + errors);
    }


    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should produce no errors.");
    }

    @Test
    void validate_emptyClusterConfig_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "empty-cluster",
                new PipelineGraphConfig(Collections.emptyMap()),
                null, null,
                Collections.emptySet(), Collections.emptySet()
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Empty cluster config should produce no errors. Errors: " + errors);
    }
}