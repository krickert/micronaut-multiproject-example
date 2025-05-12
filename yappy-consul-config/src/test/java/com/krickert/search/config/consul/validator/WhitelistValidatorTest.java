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
        // Create a pipeline configuration with all topics and services in the whitelist
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("topic1", "topic2"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service1", "service2"));

        List<String> kafkaListenTopics = List.of("topic1");
        List<KafkaPublishTopic> kafkaPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        List<String> grpcForwardTo = List.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Valid configuration should not produce any errors");
    }

    @Test
    void validate_nonWhitelistedKafkaListenTopic_returnsError() {
        // Create a pipeline configuration with a non-whitelisted Kafka listen topic
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("topic1", "topic2"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service1", "service2"));

        List<String> kafkaListenTopics = List.of("non-whitelisted-topic");
        List<KafkaPublishTopic> kafkaPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        List<String> grpcForwardTo = List.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Non-whitelisted Kafka listen topic should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("listens to non-whitelisted Kafka topic 'non-whitelisted-topic'")),
                "Error should indicate non-whitelisted Kafka listen topic");
    }

    @Test
    void validate_nonWhitelistedKafkaPublishTopic_returnsError() {
        // Create a pipeline configuration with a non-whitelisted Kafka publish topic
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("topic1", "topic2"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service1", "service2"));

        List<String> kafkaListenTopics = List.of("topic1");
        List<KafkaPublishTopic> kafkaPublishTopics = List.of(new KafkaPublishTopic("non-whitelisted-topic"));
        List<String> grpcForwardTo = List.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Non-whitelisted Kafka publish topic should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("publishes to non-whitelisted Kafka topic 'non-whitelisted-topic'")),
                "Error should indicate non-whitelisted Kafka publish topic");
    }

    @Test
    void validate_nonWhitelistedGrpcForwardTo_returnsError() {
        // Create a pipeline configuration with a non-whitelisted gRPC service
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("topic1", "topic2"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service1", "service2"));

        List<String> kafkaListenTopics = List.of("topic1");
        List<KafkaPublishTopic> kafkaPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        List<String> grpcForwardTo = List.of("non-whitelisted-service");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Non-whitelisted gRPC service should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("forwards to non-whitelisted gRPC service 'non-whitelisted-service'")),
                "Error should indicate non-whitelisted gRPC service");
    }

    @Test
    void validate_emptyWhitelists_anyTopicOrServiceIsError() {
        // Create a pipeline configuration with empty whitelists
        Set<String> allowedKafkaTopics = Collections.emptySet();
        Set<String> allowedGrpcServices = Collections.emptySet();

        List<String> kafkaListenTopics = List.of("topic1");
        List<KafkaPublishTopic> kafkaPublishTopics = List.of(new KafkaPublishTopic("topic2"));
        List<String> grpcForwardTo = List.of("service1");

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertEquals(3, errors.size(), "Empty whitelists should produce errors for all topics and services");
        assertTrue(errors.stream().anyMatch(e -> e.contains("listens to non-whitelisted Kafka topic 'topic1'")),
                "Error should indicate non-whitelisted Kafka listen topic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("publishes to non-whitelisted Kafka topic 'topic2'")),
                "Error should indicate non-whitelisted Kafka publish topic");
        assertTrue(errors.stream().anyMatch(e -> e.contains("forwards to non-whitelisted gRPC service 'service1'")),
                "Error should indicate non-whitelisted gRPC service");
    }

    @Test
    void validate_stepHasNoKafkaOrGrpc_returnsNoErrors() {
        // Create a pipeline configuration with no Kafka topics or gRPC services in the step
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("topic1", "topic2"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service1", "service2"));

        PipelineClusterConfig clusterConfig = createTestClusterConfig(
                allowedKafkaTopics, allowedGrpcServices,
                null, // kafkaListenTopics
                null, // kafkaPublishTopics
                null  // grpcForwardTo
        );

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Configuration with no Kafka topics or gRPC services in steps should not produce errors");
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

    /**
     * Helper method to create a test cluster configuration with specified whitelists and topics/services.
     */
    private PipelineClusterConfig createTestClusterConfig(
            Set<String> allowedKafkaTopics,
            Set<String> allowedGrpcServices,
            List<String> kafkaListenTopics,
            List<KafkaPublishTopic> kafkaPublishTopics,
            List<String> grpcForwardTo) {

        // Create a module that will be referenced by the step
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", "test-module", new SchemaReference("test-schema", 1)
        );
        modules.put("test-module", module);

        // Create a step with the specified topics and services
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
                "step1", "test-module", null, kafkaListenTopics, kafkaPublishTopics, grpcForwardTo,
                null, null // Added null for nextSteps and errorSteps
        );
        steps.put("step1", step);

        // Create a pipeline with the step
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        // Create a cluster config with the pipeline, module, and whitelists
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        return new PipelineClusterConfig(
                "test-cluster", graphConfig, moduleMap, allowedKafkaTopics, allowedGrpcServices
        );
    }
}