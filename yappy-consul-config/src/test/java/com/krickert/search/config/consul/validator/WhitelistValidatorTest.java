package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the WhitelistValidator class.
 * <br/>
 * These tests verify that the validator correctly:
 * 1. Validates that Kafka topics and gRPC services are in the allowed lists
 * 2. Handles null/blank topics and services (though record validation often catches this first)
 * 3. Handles null cluster config
 * 4. Handles empty whitelists
 */
class WhitelistValidatorTest {

    private WhitelistValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;

    @BeforeEach
    void setUp() {
        validator = new WhitelistValidator();
        // Simple schema content provider that always returns an empty schema
        schemaContentProvider = ref -> Optional.of("{}");
    }

    @Test
    void validate_allWhitelisted_returnsNoErrors() {
        Set<String> allowedKafkaTopics = Set.of("topic1", "publish-topic-from-pattern");
        Set<String> allowedGrpcServices = Set.of("service1");

        // Test a Kafka step
        PipelineClusterConfig clusterConfigKafka = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.KAFKA,
                List.of("topic1"),                          // listenTopics
                "publish-topic-from-pattern",              // publishTopicPattern
                null,                                       // kafkaProps
                null,                                       // grpcServiceId (not used for Kafka)
                null                                        // grpcProps (not used for Kafka)
        );
        List<String> errorsKafka = validator.validate(clusterConfigKafka, schemaContentProvider);
        assertTrue(errorsKafka.isEmpty(), "Valid Kafka config should not produce errors. Errors: " + errorsKafka);

        // Test a gRPC step
        PipelineClusterConfig clusterConfigGrpc = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.GRPC,
                null, null, null,                           // Kafka params null
                "service1",                                 // grpcServiceId
                null                                        // grpcProps
        );
        List<String> errorsGrpc = validator.validate(clusterConfigGrpc, schemaContentProvider);
        assertTrue(errorsGrpc.isEmpty(), "Valid gRPC config should not produce errors. Errors: " + errorsGrpc);
    }

    @Test
    void validate_nonWhitelistedKafkaListenTopic_returnsError() {
        Set<String> allowedKafkaTopics = Set.of("topic1");
        Set<String> allowedGrpcServices = Set.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.KAFKA,
                List.of("non-whitelisted-listen"), // The non-whitelisted topic
                "topic1",                           // A valid publish pattern/topic
                null, null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("listens to non-whitelisted topic 'non-whitelisted-listen'")), errors.toString());
    }

    @Test
    void validate_nonWhitelistedKafkaPublishTopic_returnsError() {
        // This test assumes the publishTopicPattern is treated as a direct topic name for whitelisting
        Set<String> allowedKafkaTopics = Set.of("topic1");
        Set<String> allowedGrpcServices = Set.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.KAFKA,
                List.of("topic1"),
                "non-whitelisted-publish", // The non-whitelisted publish pattern/topic
                null, null, null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        // The error message will now refer to publishTopicPattern
        assertTrue(errors.stream().anyMatch(e -> e.contains("uses a publishTopicPattern 'non-whitelisted-publish'")), errors.toString());
    }

    @Test
    void validate_nonWhitelistedGrpcForwardTo_returnsError() {
        Set<String> allowedKafkaTopics = Set.of("topic1");
        Set<String> allowedGrpcServices = Set.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.GRPC,
                null, null, null,
                "non-whitelisted-service", // The non-whitelisted gRPC serviceId
                null
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("uses non-whitelisted serviceId 'non-whitelisted-service'")), errors.toString());
    }

    @Test
    void validate_emptyWhitelists_anyTopicOrServiceIsError() {
        Set<String> allowedKafkaTopics = Collections.emptySet();
        Set<String> allowedGrpcServices = Collections.emptySet();

        // Scenario 1: Test a Kafka step with topics against empty Kafka whitelist
        KafkaTransportConfig kafkaConfigForTest = new KafkaTransportConfig(
                List.of("listenTopicAgainstEmptyWhitelist"),
                "publishPatternAgainstEmptyWhitelist",
                null);
        PipelineClusterConfig clusterConfigKafka = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, // Empty whitelists
                TransportType.KAFKA,
                kafkaConfigForTest.listenTopics(),
                kafkaConfigForTest.publishTopicPattern(),
                kafkaConfigForTest.kafkaProperties(),
                null, null // No gRPC for this step
        );

        List<String> errorsKafka = validator.validate(clusterConfigKafka, schemaContentProvider);
        assertEquals(2, errorsKafka.size(), "Empty Kafka whitelist should produce 2 errors for a Kafka step with listen and publish. Errors: " + errorsKafka);
        assertTrue(errorsKafka.stream().anyMatch(e -> e.contains("listens to non-whitelisted topic 'listenTopicAgainstEmptyWhitelist'")), errorsKafka.toString());
        assertTrue(errorsKafka.stream().anyMatch(e -> e.contains("uses a publishTopicPattern 'publishPatternAgainstEmptyWhitelist'")), errorsKafka.toString());


        // Scenario 2: Test a gRPC step with a serviceId against empty gRPC whitelist
        GrpcTransportConfig grpcConfigForTest = new GrpcTransportConfig("serviceAgainstEmptyWhitelist", null);
        PipelineClusterConfig clusterConfigGrpc = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, // Empty whitelists
                TransportType.GRPC,
                null, null, null, // No Kafka for this step
                grpcConfigForTest.serviceId(),
                grpcConfigForTest.grpcProperties()
        );
        List<String> errorsGrpc = validator.validate(clusterConfigGrpc, schemaContentProvider);
        assertEquals(1, errorsGrpc.size(), "Empty gRPC whitelist should produce 1 error for a gRPC step. Errors: " + errorsGrpc);
        assertTrue(errorsGrpc.stream().anyMatch(e -> e.contains("uses non-whitelisted serviceId 'serviceAgainstEmptyWhitelist'")), errorsGrpc.toString());
    }

    @Test
    void validate_stepHasNoKafkaOrGrpc_InternalStep_returnsNoErrors() {
        Set<String> allowedKafkaTopics = Set.of("topic1");
        Set<String> allowedGrpcServices = Set.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                TransportType.INTERNAL, // Type
                null, null, null,     // Kafka params
                null, null            // Grpc params
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty());
    }

    @Test
    void validate_nullClusterConfig_returnsEmptyErrorList() {
        List<String> errors = validator.validate(null, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null cluster config should not produce errors");
    }

    @Test
    void validate_nullPipelineGraphConfig_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                null, // pipelineGraphConfig
                new PipelineModuleMap(Collections.emptyMap()),
                Collections.singleton("allowedTopic"),
                Collections.singleton("allowedService")
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "Null pipelineGraphConfig should not produce errors from WhitelistValidator");
    }

    @Test
    void validate_pipelineGraphConfigWithNullPipelinesMap_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                new PipelineGraphConfig(null), // pipelines map is null
                new PipelineModuleMap(Collections.emptyMap()),
                Collections.singleton("allowedTopic"),
                Collections.singleton("allowedService")
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "PipelineGraphConfig with null pipelines map should not produce errors");
    }

    @Test
    void validate_pipelineGraphConfigWithEmptyPipelinesMap_returnsNoErrors() {
        PipelineClusterConfig clusterConfig = new PipelineClusterConfig(
                "test-cluster",
                new PipelineGraphConfig(Collections.emptyMap()), // pipelines map is empty
                new PipelineModuleMap(Collections.emptyMap()),
                Collections.singleton("allowedTopic"),
                Collections.singleton("allowedService")
        );
        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);
        assertTrue(errors.isEmpty(), "PipelineGraphConfig with empty pipelines map should not produce errors");
    }


    // Note: We can't directly test null/blank values in the lists for topics/services within PipelineStepConfig
    // because the PipelineStepConfig record constructor validates these inputs and throws exceptions.
    // The WhitelistValidator's internal null/blank checks for these are still good for robustness,
    // especially if PipelineStepConfig instances could somehow be created bypassing its own validation.

    // In WhitelistValidatorTest.java
    private PipelineClusterConfig createTestClusterConfig(
            Set<String> allowedKafkaTopics,
            Set<String> allowedGrpcServices,
            // Parameters now reflect the new model for a single step's transport for simplicity in this helper
            TransportType stepTransportType,
            List<String> stepKafkaListenTopics,    // Only if KAFKA
            String stepKafkaPublishPattern,  // Only if KAFKA
            Map<String, String> stepKafkaProps,    // Only if KAFKA
            String stepGrpcServiceId,        // Only if GRPC
            Map<String, String> stepGrpcProps      // Only if GRPC
    ) {

        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        Map<String, PipelineStepConfig> steps = new HashMap<>();
        KafkaTransportConfig kafkaConfig = null;
        GrpcTransportConfig grpcConfig = null;

        if (stepTransportType == TransportType.KAFKA) {
            kafkaConfig = new KafkaTransportConfig(stepKafkaListenTopics, stepKafkaPublishPattern, stepKafkaProps);
        } else if (stepTransportType == TransportType.GRPC) {
            grpcConfig = new GrpcTransportConfig(stepGrpcServiceId, stepGrpcProps);
        }

        PipelineStepConfig step = new PipelineStepConfig(
                "step1",
                "test-module",
                null, // customConfig
                Collections.singletonList("nextStep"), // nextSteps
                Collections.emptyList(),              // errorSteps
                stepTransportType,
                kafkaConfig,
                grpcConfig
        );
        steps.put("step1", step);

        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);

        return new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, allowedKafkaTopics, allowedGrpcServices
        );
    }
}