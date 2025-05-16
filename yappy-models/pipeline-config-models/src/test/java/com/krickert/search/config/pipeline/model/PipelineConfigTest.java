package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Test
    void testSerializationDeserialization_WithKafkaAndGrpcSteps() throws Exception {
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // --- Define a KAFKA step ---
        JsonConfigOptions customConfigKafka = new JsonConfigOptions("{\"source\":\"kafka\"}");
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                List.of("input-topic-for-kafka-step"),
                "kafka-step.output.topic",
                Map.of("client.id", "kafka-step-producer")
        );
        PipelineStepConfig kafkaStep = new PipelineStepConfig(
                "kafka-processor-step",
                "kafka-processor-module",
                customConfigKafka,
                List.of("grpc-validator-step"), // nextStep
                List.of("kafka-error-logger"),  // errorStep
                TransportType.KAFKA,
                kafkaConfig,
                null, // grpcConfig must be null
                null  // stepType defaults to PIPELINE
        );
        steps.put(kafkaStep.pipelineStepId(), kafkaStep);

        // --- Define a GRPC step ---
        JsonConfigOptions customConfigGrpc = new JsonConfigOptions("{\"validatorType\":\"strict\"}");
        GrpcTransportConfig grpcConfig = new GrpcTransportConfig(
                "grpc-validation-service",
                Map.of("retryAttempts", "3")
        );
        PipelineStepConfig grpcStep = new PipelineStepConfig(
                "grpc-validator-step",
                "grpc-validator-module",
                customConfigGrpc,
                List.of("final-internal-step"), // nextStep
                null,                          // errorSteps (will default to empty)
                TransportType.GRPC,
                null, // kafkaConfig must be null
                grpcConfig,
                null  // stepType defaults to PIPELINE
        );
        steps.put(grpcStep.pipelineStepId(), grpcStep);

        // --- Define an INTERNAL step ---
        PipelineStepConfig internalStep = new PipelineStepConfig(
                "final-internal-step",
                "internal-aggregator-module",
                null,
                Collections.emptyList(), // No next steps
                Collections.emptyList(), // No error steps
                TransportType.INTERNAL,
                null,
                null,
                null  // stepType defaults to PIPELINE
        );
        steps.put(internalStep.pipelineStepId(), internalStep);


        PipelineConfig config = new PipelineConfig("test-complex-pipeline", steps);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineConfig JSON:\n" + json);

        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        assertEquals("test-complex-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertEquals(3, deserialized.pipelineSteps().size());

        // Verify Kafka Step
        PipelineStepConfig deserializedKafkaStep = deserialized.pipelineSteps().get("kafka-processor-step");
        assertNotNull(deserializedKafkaStep);
        assertEquals("kafka-processor-step", deserializedKafkaStep.pipelineStepId());
        assertEquals("kafka-processor-module", deserializedKafkaStep.pipelineImplementationId());
        assertEquals("{\"source\":\"kafka\"}", deserializedKafkaStep.customConfig().jsonConfig());
        assertEquals(TransportType.KAFKA, deserializedKafkaStep.transportType());
        assertNotNull(deserializedKafkaStep.kafkaConfig());
        assertNull(deserializedKafkaStep.grpcConfig());
        assertEquals(List.of("input-topic-for-kafka-step"), deserializedKafkaStep.kafkaConfig().listenTopics());
        assertEquals("kafka-step.output.topic", deserializedKafkaStep.kafkaConfig().publishTopicPattern());
        assertEquals(Map.of("client.id", "kafka-step-producer"), deserializedKafkaStep.kafkaConfig().kafkaProperties());
        assertEquals(List.of("grpc-validator-step"), deserializedKafkaStep.nextSteps());
        assertEquals(List.of("kafka-error-logger"), deserializedKafkaStep.errorSteps());

        // Verify gRPC Step
        PipelineStepConfig deserializedGrpcStep = deserialized.pipelineSteps().get("grpc-validator-step");
        assertNotNull(deserializedGrpcStep);
        assertEquals("grpc-validator-step", deserializedGrpcStep.pipelineStepId());
        assertEquals(TransportType.GRPC, deserializedGrpcStep.transportType());
        assertNull(deserializedGrpcStep.kafkaConfig());
        assertNotNull(deserializedGrpcStep.grpcConfig());
        assertEquals("grpc-validation-service", deserializedGrpcStep.grpcConfig().serviceId());
        assertEquals(List.of("final-internal-step"), deserializedGrpcStep.nextSteps());
        assertTrue(deserializedGrpcStep.errorSteps().isEmpty());

        // Verify Internal Step
        PipelineStepConfig deserializedInternalStep = deserialized.pipelineSteps().get("final-internal-step");
        assertNotNull(deserializedInternalStep);
        assertEquals("final-internal-step", deserializedInternalStep.pipelineStepId());
        assertEquals(TransportType.INTERNAL, deserializedInternalStep.transportType());
        assertNull(deserializedInternalStep.kafkaConfig());
        assertNull(deserializedInternalStep.grpcConfig());
        assertTrue(deserializedInternalStep.nextSteps().isEmpty());
    }

    @Test
    void testValidation() {
        // Test null name validation
        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, Collections.emptyMap()));
        assertTrue(e1.getMessage().contains("PipelineConfig name cannot be null or blank"));


        // Test blank name validation
        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new PipelineConfig("", Collections.emptyMap()));
        assertTrue(e2.getMessage().contains("PipelineConfig name cannot be null or blank"));

        // Test null pipelineSteps handling (should default to empty map by constructor)
        PipelineConfig configWithNullSteps = new PipelineConfig("test-pipeline-null-steps", null);
        assertNotNull(configWithNullSteps.pipelineSteps(), "pipelineSteps should be an empty map, not null");
        assertTrue(configWithNullSteps.pipelineSteps().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(List.of("in"), "out", null);
        // Create a PipelineStepConfig using the new model
        PipelineStepConfig step = new PipelineStepConfig(
                "json-prop-step",
                "module",
                new JsonConfigOptions("{}"), // Assuming JsonConfigOptions takes a string
                Collections.emptyList(),    // nextSteps
                Collections.emptyList(),    // errorSteps
                TransportType.KAFKA,
                kafkaConfig,
                null, // grpcConfig must be null for KAFKA
                null  // stepType defaults to PIPELINE
        );
        steps.put(step.pipelineStepId(), step);
        PipelineConfig config = new PipelineConfig("json-prop-pipeline", steps);

        String json = objectMapper.writeValueAsString(config);
        System.out.println("JSON for PipelineConfigTest.testJsonPropertyNames:\n" + json);


        assertTrue(json.contains("\"name\"") && json.contains("\"json-prop-pipeline\""),
                "name key/value not found as expected. JSON: " + json);

        assertTrue(json.contains("\"pipelineSteps\""), "'pipelineSteps' key missing. JSON: " + json);
        assertTrue(json.contains("\"json-prop-step\""), "'json-prop-step' key (for the step) missing. JSON: " + json);

        // Check for parts of the nested step's new structure
        assertTrue(json.contains("\"transportType\"") && json.contains("\"KAFKA\""),
                "transportType KAFKA not found for step. JSON: " + json);
        assertTrue(json.contains("\"kafkaConfig\""), "'kafkaConfig' key missing for step. JSON: " + json);
        assertTrue(json.contains("\"listenTopics\"") && json.contains("\"in\""),
                "listenTopics with 'in' not found in kafkaConfig. JSON: " + json);
        assertTrue(json.contains("\"publishTopicPattern\"") && json.contains("\"out\""),
                "publishTopicPattern with 'out' not found in kafkaConfig. JSON: " + json);

        // Ensure old properties are NOT present directly in the step's JSON
        // (they would be inside kafkaConfig or grpcConfig now)
        // This part of the original assertion might be less relevant now as the structure has changed significantly
        // String stepJsonSnippet = ... // extract json for "json-prop-step" if needed for very specific negative assertions
        // assertFalse(stepJsonSnippet.contains("\"kafkaListenTopics\":")); // Old field
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        // This test requires a "pipeline-config-new-model.json" file
        // in src/test/resources that uses the new PipelineStepConfig structure.
        // Example content for pipeline-config-new-model.json:
        // {
        //   "name": "loaded-pipeline",
        //   "pipelineSteps": {
        //     "kafka-entry-step": {
        //       "pipelineStepId": "kafka-entry-step",
        //       "pipelineImplementationId": "entry-module",
        //       "nextSteps": ["processing-step"],
        //       "transportType": "KAFKA",
        //       "kafkaConfig": {
        //         "listenTopics": ["raw-data-input"],
        //         "publishTopicPattern": "pipeline.kafka-entry-step.processed"
        //       }
        //     },
        //     "processing-step": {
        //       "pipelineStepId": "processing-step",
        //       "pipelineImplementationId": "processor-module",
        //       "transportType": "INTERNAL"
        //     }
        //   }
        // }
        try (InputStream is = getClass().getResourceAsStream("/pipeline-config-new-model.json")) {
            assertNotNull(is, "Could not load resource /pipeline-config-new-model.json. Please create it with the new step model.");
            PipelineConfig config = objectMapper.readValue(is, PipelineConfig.class);

            assertEquals("loaded-pipeline", config.name());
            assertNotNull(config.pipelineSteps());
            assertEquals(3, config.pipelineSteps().size());

            PipelineStepConfig kafkaEntry = config.pipelineSteps().get("kafka-entry-step");
            assertNotNull(kafkaEntry);
            assertEquals("entry-module", kafkaEntry.pipelineImplementationId());
            assertEquals(TransportType.KAFKA, kafkaEntry.transportType());
            assertNotNull(kafkaEntry.kafkaConfig());
            assertEquals(List.of("raw-data-input"), kafkaEntry.kafkaConfig().listenTopics());
            assertEquals("pipeline.kafka-entry-step.processed", kafkaEntry.kafkaConfig().publishTopicPattern());
            assertEquals(List.of("processing-step"), kafkaEntry.nextSteps());

            PipelineStepConfig processingStep = config.pipelineSteps().get("processing-step");
            assertNotNull(processingStep);
            assertEquals(TransportType.INTERNAL, processingStep.transportType());
            assertNull(processingStep.kafkaConfig());
            assertNull(processingStep.grpcConfig());
        }
    }

    @Test
    void testImmutabilityOfPipelineStepsMapInPipelineConfig() {
        // Assumes PipelineConfig's constructor uses Map.copyOf for pipelineSteps
        Map<String, PipelineStepConfig> initialSteps = new HashMap<>();
        PipelineStepConfig internalStep = new PipelineStepConfig(
                "s1", "m1", null, null, null, TransportType.INTERNAL, null, null, null);
        initialSteps.put("s1", internalStep);

        PipelineConfig config = new PipelineConfig("immutable-test-pipeline", initialSteps);
        Map<String, PipelineStepConfig> retrievedSteps = config.pipelineSteps();

        assertThrows(UnsupportedOperationException.class, () -> retrievedSteps.put("s2", null),
                "PipelineConfig.pipelineSteps map should be unmodifiable after construction.");
    }
}
