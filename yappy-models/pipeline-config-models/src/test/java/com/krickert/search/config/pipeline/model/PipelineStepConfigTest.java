package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class PipelineStepConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule()); // For record deserialization
        objectMapper.registerModule(new Jdk8Module());           // For readable JSON output
    }

    @Test
    void testSerializationDeserialization_KafkaStep() throws Exception {
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\":\"value\"}");
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                List.of("test-input-topic"),
                "pipeline.step.out",
                Map.of("retries", "3")
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "test-kafka-step",
                "kafka-test-module",
                customConfig,
                List.of("next-step-1"),
                List.of("error-step-1"),
                TransportType.KAFKA,
                kafkaConfig,
                null, // grpcConfig must be null for KAFKA
                null  // stepType defaults to PIPELINE
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized KAFKA PipelineStepConfig JSON:\n" + json);

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("test-kafka-step", deserialized.pipelineStepId());
        assertEquals("kafka-test-module", deserialized.pipelineImplementationId());
        assertEquals("{\"key\":\"value\"}", deserialized.customConfig().jsonConfig());
        assertEquals(List.of("next-step-1"), deserialized.nextSteps());
        assertEquals(List.of("error-step-1"), deserialized.errorSteps());
        assertEquals(TransportType.KAFKA, deserialized.transportType());

        assertNotNull(deserialized.kafkaConfig());
        assertNull(deserialized.grpcConfig());

        assertEquals(List.of("test-input-topic"), deserialized.kafkaConfig().listenTopics());
        assertEquals("pipeline.step.out", deserialized.kafkaConfig().publishTopicPattern());
        assertEquals(Map.of("retries", "3"), deserialized.kafkaConfig().kafkaProperties());
    }

    @Test
    void testSerializationDeserialization_GrpcStep() throws Exception {
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"serviceSpecific\":\"config\"}");
        GrpcTransportConfig grpcConfig = new GrpcTransportConfig(
                "my-grpc-service",
                Map.of("deadlineMs", "5000")
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "test-grpc-step",
                "grpc-test-module",
                customConfig,
                List.of("next-grpc-target"),
                Collections.emptyList(),
                TransportType.GRPC,
                null, // kafkaConfig must be null for GRPC
                grpcConfig,
                null  // stepType defaults to PIPELINE
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized GRPC PipelineStepConfig JSON:\n" + json);

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("test-grpc-step", deserialized.pipelineStepId());
        assertEquals("grpc-test-module", deserialized.pipelineImplementationId());
        assertEquals("{\"serviceSpecific\":\"config\"}", deserialized.customConfig().jsonConfig());
        assertEquals(List.of("next-grpc-target"), deserialized.nextSteps());
        assertTrue(deserialized.errorSteps().isEmpty());
        assertEquals(TransportType.GRPC, deserialized.transportType());

        assertNull(deserialized.kafkaConfig());
        assertNotNull(deserialized.grpcConfig());

        assertEquals("my-grpc-service", deserialized.grpcConfig().serviceId());
        assertEquals(Map.of("deadlineMs", "5000"), deserialized.grpcConfig().grpcProperties());
    }

    @Test
    void testSerializationDeserialization_InternalStep() throws Exception {
        PipelineStepConfig config = new PipelineStepConfig(
                "test-internal-step",
                "internal-test-module",
                null, // No custom config
                List.of("next-internal-action"),
                null, // errorSteps defaults to empty
                TransportType.INTERNAL,
                null, // kafkaConfig must be null
                null, // grpcConfig must be null
                null  // stepType defaults to PIPELINE
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized INTERNAL PipelineStepConfig JSON:\n" + json);

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("test-internal-step", deserialized.pipelineStepId());
        assertEquals("internal-test-module", deserialized.pipelineImplementationId());
        assertNull(deserialized.customConfig());
        assertEquals(List.of("next-internal-action"), deserialized.nextSteps());
        assertTrue(deserialized.errorSteps().isEmpty()); // Defaulted to empty
        assertEquals(TransportType.INTERNAL, deserialized.transportType());
        assertNull(deserialized.kafkaConfig());
        assertNull(deserialized.grpcConfig());
    }

    @Test
    void testValidation_ConstructorArgs() {
        // Test null pipelineStepId validation
        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                null, "m", null, null, null, TransportType.INTERNAL, null, null, null));
        assertTrue(e1.getMessage().contains("pipelineStepId cannot be null or blank"));

        // Test blank pipelineStepId validation
        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                " ", "m", null, null, null, TransportType.INTERNAL, null, null, null));
        assertTrue(e2.getMessage().contains("pipelineStepId cannot be null or blank"));

        // Test null pipelineImplementationId validation
        Exception e3 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", null, null, null, null, TransportType.INTERNAL, null, null, null));
        assertTrue(e3.getMessage().contains("pipelineImplementationId cannot be null or blank"));

        // Test null transportType validation
        Exception e4 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, null, null, null, null));
        assertTrue(e4.getMessage().contains("transportType cannot be null"));

        // Test null collections handling (implicitly tested by constructors above and their defaulting)
        PipelineStepConfig configWithNullLists = new PipelineStepConfig(
                "test-step", "test-module", null, null, null, TransportType.INTERNAL, null, null, null);
        assertTrue(configWithNullLists.nextSteps().isEmpty());
        assertTrue(configWithNullLists.errorSteps().isEmpty());

        List<String> nextStepsWithNullEl = new ArrayList<>();
        nextStepsWithNullEl.add("valid");
        nextStepsWithNullEl.add(null);
        Exception e5 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, nextStepsWithNullEl, null, TransportType.INTERNAL, null, null, null));
        assertTrue(e5.getMessage().contains("nextSteps cannot contain null or blank step IDs: [valid, null]"));

        List<String> errorStepsWithBlankEl = List.of("");
        Exception e6 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, errorStepsWithBlankEl, TransportType.INTERNAL, null, null, null));
        assertTrue(e6.getMessage().contains("errorSteps cannot contain null or blank step IDs"));
    }

    @Test
    void testValidation_TransportSpecificConfigs() {
        KafkaTransportConfig kCfg = new KafkaTransportConfig(Collections.emptyList(), null, null);
        GrpcTransportConfig gCfg = new GrpcTransportConfig("service1", null);

        // KAFKA type but missing kafkaConfig
        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.KAFKA, null, null, null));
        assertTrue(e1.getMessage().contains("KafkaTransportConfig must be provided"));

        // KAFKA type but grpcConfig is present
        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.KAFKA, kCfg, gCfg, null));
        assertTrue(e2.getMessage().contains("GrpcTransportConfig should only be provided"));

        // GRPC type but missing grpcConfig
        Exception e3 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.GRPC, null, null, null));
        assertTrue(e3.getMessage().contains("GrpcTransportConfig must be provided"));

        // GRPC type but kafkaConfig is present
        Exception e4 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.GRPC, kCfg, gCfg, null));
        assertTrue(e4.getMessage().contains("KafkaTransportConfig should only be provided"));

        // INTERNAL type but kafkaConfig is present
        Exception e5 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.INTERNAL, kCfg, null, null));
        assertTrue(e5.getMessage().contains("KafkaTransportConfig should only be provided"));

        // INTERNAL type but grpcConfig is present
        Exception e6 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", "m", null, null, null, TransportType.INTERNAL, null, gCfg, null));
        assertTrue(e6.getMessage().contains("GrpcTransportConfig should only be provided"));
    }


    @Test
    void testJsonPropertyNames_WithNewModel() throws Exception {
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                List.of("input1"), "pattern.out", Map.of("prop","val")
        );
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step-json",
                "test-module-json",
                new JsonConfigOptions("{\"mode\":\"test\"}"),
                List.of("next-json"),
                List.of("error-json"),
                TransportType.KAFKA,
                kafkaConfig,
                null,
                null  // stepType defaults to PIPELINE
        );

        String json = objectMapper.writeValueAsString(config);

        assertTrue(json.contains("\"pipelineStepId\":\"test-step-json\""));
        assertTrue(json.contains("\"pipelineImplementationId\":\"test-module-json\""));
        assertTrue(json.contains("\"customConfig\":{\"jsonConfig\":\"{\\\"mode\\\":\\\"test\\\"}\"}"));
        assertTrue(json.contains("\"nextSteps\":[\"next-json\"]"));
        assertTrue(json.contains("\"errorSteps\":[\"error-json\"]"));
        assertTrue(json.contains("\"transportType\":\"KAFKA\""));
        assertTrue(json.contains("\"kafkaConfig\":{"));
        assertTrue(json.contains("\"listenTopics\":[\"input1\"]"));
        assertTrue(json.contains("\"publishTopicPattern\":\"pattern.out\""));
        assertTrue(json.contains("\"kafkaProperties\":{\"prop\":\"val\"}"));
        assertTrue(json.contains("\"grpcConfig\":null") || !json.contains("\"grpcConfig\":"));

        // Verify old direct properties are gone
        assertTrue(!json.contains("\"kafkaListenTopics\"") || json.contains("\"kafkaListenTopics\":null"), "Old kafkaListenTopics field should not exist or be null");
        assertTrue(!json.contains("\"kafkaPublishTopics\"") || json.contains("\"kafkaPublishTopics\":null"), "Old kafkaPublishTopics field should not exist or be null");
        assertTrue(!json.contains("\"grpcForwardTo\"") || json.contains("\"grpcForwardTo\":null"), "Old grpcForwardTo field should not exist or be null");
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        // Assuming pipeline-step-config-new-model.json contains the Kafka step example
        try (InputStream is = getClass().getResourceAsStream("/pipeline-step-config-new-model.json")) {
            assertNotNull(is, "Could not load resource /pipeline-step-config-new-model.json. Please create it with the new model.");
            PipelineStepConfig config = objectMapper.readValue(is, PipelineStepConfig.class);

            // Assertions for the Kafka Step Example
            assertEquals("imageProcessorKafka", config.pipelineStepId());
            assertEquals("image-processing-module-v2", config.pipelineImplementationId());
            assertNotNull(config.customConfig());
            assertEquals("{\"format\":\"jpeg\", \"quality\":90, \"targetSize\":\"1024x768\"}", config.customConfig().jsonConfig());
            assertEquals(List.of("storeImageMetadata", "notifyCompletion"), config.nextSteps());
            assertEquals(List.of("logImageProcessingError", "quarantineImage"), config.errorSteps());
            assertEquals(TransportType.KAFKA, config.transportType());

            assertNotNull(config.kafkaConfig(), "KafkaConfig should not be null for KAFKA type");
            assertNull(config.grpcConfig(), "GrpcConfig should be null for KAFKA type");

            assertEquals(List.of("pending-images-topic", "retry-images-topic"), config.kafkaConfig().listenTopics());
            assertEquals("processed.images.${stepId}.output", config.kafkaConfig().publishTopicPattern());
            assertNotNull(config.kafkaConfig().kafkaProperties());
            assertEquals("all", config.kafkaConfig().kafkaProperties().get("acks"));
            assertEquals("snappy", config.kafkaConfig().kafkaProperties().get("compression.type"));
            assertEquals("50", config.kafkaConfig().kafkaProperties().get("max.poll.records"));
        }
    }
}
