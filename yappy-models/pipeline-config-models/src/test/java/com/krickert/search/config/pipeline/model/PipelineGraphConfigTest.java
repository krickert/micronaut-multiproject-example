package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature; // For pretty printing JSON if needed for debugging
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module; // For Optional, etc.
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule; // For record deserialization

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


class PipelineGraphConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Register modules needed for records and Optional, etc.
        objectMapper.registerModule(new ParameterNamesModule()); // Essential for record deserialization
        objectMapper.registerModule(new Jdk8Module());          // For Optional and other Java 8 types
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // Makes JSON output readable for debugging
    }

    @Test
    void testSerializationDeserialization_KafkaStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\":\"kafkaValue\"}");
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                List.of("test-input-topic"),
                "pipelineId.stepId.output",
                Map.of("acks", "all")
        );

        PipelineStepConfig kafkaStep = new PipelineStepConfig(
                "kafka-test-step",
                "kafka-module",
                customConfig,
                List.of("next-logical-step"),
                List.of("error-logical-step"),
                TransportType.KAFKA,
                kafkaConfig, // Provide KafkaTransportConfig
                null,        // grpcConfig must be null for KAFKA type
                null         // stepType defaults to PIPELINE
        );
        steps.put("kafka-test-step", kafkaStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-kafka", steps);
        pipelines.put("test-pipeline-kafka", pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized Kafka Step JSON:\n" + json); // For debugging

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        assertNotNull(deserialized.pipelines());
        assertEquals(1, deserialized.pipelines().size());

        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-kafka");
        assertNotNull(deserializedPipeline);
        assertEquals("test-pipeline-kafka", deserializedPipeline.name());

        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("kafka-test-step");
        assertNotNull(deserializedStep);
        assertEquals("kafka-test-step", deserializedStep.pipelineStepId());
        assertEquals("kafka-module", deserializedStep.pipelineImplementationId());
        assertEquals("{\"key\":\"kafkaValue\"}", deserializedStep.customConfig().jsonConfig());
        assertEquals(TransportType.KAFKA, deserializedStep.transportType());

        assertNotNull(deserializedStep.kafkaConfig());
        assertNull(deserializedStep.grpcConfig()); // Should be null for Kafka steps

        assertEquals(1, deserializedStep.kafkaConfig().listenTopics().size());
        assertEquals("test-input-topic", deserializedStep.kafkaConfig().listenTopics().getFirst());
        assertEquals("pipelineId.stepId.output", deserializedStep.kafkaConfig().publishTopicPattern());
        assertNotNull(deserializedStep.kafkaConfig().kafkaProperties());
        assertEquals("all", deserializedStep.kafkaConfig().kafkaProperties().get("acks"));

        assertEquals(List.of("next-logical-step"), deserializedStep.nextSteps());
        assertEquals(List.of("error-logical-step"), deserializedStep.errorSteps());
    }

    @Test
    void testSerializationDeserialization_GrpcStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\":\"grpcValue\"}");
        GrpcTransportConfig grpcConfig = new GrpcTransportConfig(
                "my-grpc-service-id",
                Map.of("timeout", "5s")
        );

        PipelineStepConfig grpcStep = new PipelineStepConfig(
                "grpc-test-step",
                "grpc-module",
                customConfig,
                List.of("another-next-step"),
                Collections.emptyList(), // Empty error steps
                TransportType.GRPC,
                null,       // kafkaConfig must be null for GRPC type
                grpcConfig,  // Provide GrpcTransportConfig
                null        // stepType defaults to PIPELINE
        );
        steps.put("grpc-test-step", grpcStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-grpc", steps);
        pipelines.put("test-pipeline-grpc", pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized gRPC Step JSON:\n" + json); // For debugging

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-grpc");
        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("grpc-test-step");

        assertNotNull(deserializedStep);
        assertEquals("grpc-test-step", deserializedStep.pipelineStepId());
        assertEquals("grpc-module", deserializedStep.pipelineImplementationId());
        assertEquals(TransportType.GRPC, deserializedStep.transportType());

        assertNull(deserializedStep.kafkaConfig()); // Should be null for gRPC steps
        assertNotNull(deserializedStep.grpcConfig());

        assertEquals("my-grpc-service-id", deserializedStep.grpcConfig().serviceId());
        assertNotNull(deserializedStep.grpcConfig().grpcProperties());
        assertEquals("5s", deserializedStep.grpcConfig().grpcProperties().get("timeout"));

        assertEquals(List.of("another-next-step"), deserializedStep.nextSteps());
        assertTrue(deserializedStep.errorSteps().isEmpty());
    }

    @Test
    void testSerializationDeserialization_InternalStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig internalStep = new PipelineStepConfig(
                "internal-test-step",
                "internal-module",
                null, // No custom config
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,       // kafkaConfig must be null
                null,       // grpcConfig must be null
                null        // stepType defaults to PIPELINE
        );
        steps.put("internal-test-step", internalStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-internal", steps);
        pipelines.put("test-pipeline-internal", pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized Internal Step JSON:\n" + json); // For debugging

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);
        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-internal");
        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("internal-test-step");

        assertNotNull(deserializedStep);
        assertEquals("internal-test-step", deserializedStep.pipelineStepId());
        assertEquals(TransportType.INTERNAL, deserializedStep.transportType());
        assertNull(deserializedStep.kafkaConfig());
        assertNull(deserializedStep.grpcConfig());
        assertNull(deserializedStep.customConfig()); // As it was set to null
        assertTrue(deserializedStep.nextSteps().isEmpty());
        assertTrue(deserializedStep.errorSteps().isEmpty());
    }


    @Test
    void testNullHandling_PipelineGraphConfig() throws Exception {
        // Assuming PipelineGraphConfig constructor handles null and defaults to empty map
        PipelineGraphConfig config = new PipelineGraphConfig(null);
        String json = objectMapper.writeValueAsString(config);
        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        assertNotNull(deserialized.pipelines());
        assertTrue(deserialized.pipelines().isEmpty());
    }

    @Test
    void testJsonPropertyNames_WithNewModel() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(List.of("in"), "out", null);
        PipelineStepConfig step = new PipelineStepConfig(
                "test-step",
                "test-module",
                new JsonConfigOptions("{}"),
                List.of("next-step-id"),
                List.of("error-step-id"),
                TransportType.KAFKA,
                kafkaConfig,
                null, // grpcConfig must be null for KAFKA
                null  // stepType defaults to PIPELINE
        );
        steps.put("test-step", step);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        pipelines.put("test-pipeline", pipeline);
        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);

        String json = objectMapper.writeValueAsString(config);

        assertTrue(json.contains("\"pipelines\" : "));
        assertTrue(json.contains("\"test-pipeline\" : "));
        assertTrue(json.contains("\"pipelineStepId\" : \"test-step\""));
        assertTrue(json.contains("\"transportType\" : \"KAFKA\""));
        assertTrue(json.contains("\"kafkaConfig\" : "));
        assertTrue(json.contains("\"listenTopics\" : [ \"in\" ]"));
        assertTrue(json.contains("\"publishTopicPattern\" : \"out\""));
        assertTrue(json.contains("\"nextSteps\" : [ \"next-step-id\" ]"));
        assertTrue(json.contains("\"errorSteps\" : [ \"error-step-id\" ]"));
        // Check that old fields are NOT present
        assertFalse(json.contains("\"kafkaListenTopics\" : "));
        assertFalse(json.contains("\"kafkaPublishTopics\" : "));
        assertFalse(json.contains("\"grpcForwardTo\" : "));
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/pipeline-graph-config-new-model.json")) {
            assertNotNull(is, "Could not load resource /pipeline-graph-config-new-model.json. Please ensure it's in src/test/resources and the path is correct.");
            PipelineGraphConfig graphConfig = objectMapper.readValue(is, PipelineGraphConfig.class);

            assertNotNull(graphConfig.pipelines(), "Pipelines map should not be null");
            assertEquals(2, graphConfig.pipelines().size(), "Should be 2 pipelines defined in the JSON");

            // --- Verify 'dataIngestionPipeline' ---
            PipelineConfig ingestionPipeline = graphConfig.pipelines().get("dataIngestionPipeline");
            assertNotNull(ingestionPipeline, "dataIngestionPipeline not found");
            assertEquals("dataIngestionPipeline", ingestionPipeline.name());
            assertEquals(10, ingestionPipeline.pipelineSteps().size(), "dataIngestionPipeline should have 10 steps"); // Adjusted count

            // Spot check some steps in 'dataIngestionPipeline'
            PipelineStepConfig receiveRawData = ingestionPipeline.pipelineSteps().get("receiveRawData");
            assertNotNull(receiveRawData);
            assertEquals("receiveRawData", receiveRawData.pipelineStepId());
            assertEquals("kafka-generic-ingestor", receiveRawData.pipelineImplementationId());
            assertEquals(TransportType.KAFKA, receiveRawData.transportType());
            assertNotNull(receiveRawData.customConfig());
            assertEquals("{\"validationSchemaId\":\"rawDocEventSchema_v1\"}", receiveRawData.customConfig().jsonConfig());
            assertEquals(List.of("normalizeData"), receiveRawData.nextSteps());
            assertEquals(List.of("logIngestionError"), receiveRawData.errorSteps());
            assertNotNull(receiveRawData.kafkaConfig());
            assertEquals(List.of("topic-raw-documents", "topic-other-source"), receiveRawData.kafkaConfig().listenTopics());
            assertEquals("ingestion.receiveRawData.processed", receiveRawData.kafkaConfig().publishTopicPattern());
            assertEquals(Map.of("group.id","ingestion-group-1"), receiveRawData.kafkaConfig().kafkaProperties());
            assertNull(receiveRawData.grpcConfig());

            PipelineStepConfig normalizeData = ingestionPipeline.pipelineSteps().get("normalizeData");
            assertNotNull(normalizeData);
            assertEquals("normalizeData", normalizeData.pipelineStepId());
            assertEquals(TransportType.INTERNAL, normalizeData.transportType());
            assertEquals(List.of("enrichData"), normalizeData.nextSteps());
            assertNull(normalizeData.kafkaConfig());
            assertNull(normalizeData.grpcConfig());

            PipelineStepConfig enrichData = ingestionPipeline.pipelineSteps().get("enrichData");
            assertNotNull(enrichData);
            assertEquals("enrichData", enrichData.pipelineStepId());
            assertEquals(TransportType.GRPC, enrichData.transportType());
            assertNotNull(enrichData.grpcConfig());
            assertEquals("geo-enrichment-grpc-service", enrichData.grpcConfig().serviceId());
            assertEquals(Map.of("loadBalancingPolicy","round_robin"), enrichData.grpcConfig().grpcProperties());
            assertNull(enrichData.kafkaConfig());

            PipelineStepConfig publishToDocProcessing = ingestionPipeline.pipelineSteps().get("publishToDocProcessing");
            assertNotNull(publishToDocProcessing);
            assertEquals(TransportType.KAFKA, publishToDocProcessing.transportType());
            assertNotNull(publishToDocProcessing.kafkaConfig());
            assertTrue(publishToDocProcessing.kafkaConfig().listenTopics().isEmpty());
            assertEquals("documents.forTextProcessing.v1", publishToDocProcessing.kafkaConfig().publishTopicPattern());


            // --- Verify 'documentProcessingPipeline' ---
            PipelineConfig docProcPipeline = graphConfig.pipelines().get("documentProcessingPipeline");
            assertNotNull(docProcPipeline, "documentProcessingPipeline not found");
            assertEquals("documentProcessingPipeline", docProcPipeline.name());
            assertEquals(7, docProcPipeline.pipelineSteps().size(), "documentProcessingPipeline should have 7 steps"); // Adjusted count

            PipelineStepConfig ocrContent = docProcPipeline.pipelineSteps().get("ocrContent");
            assertNotNull(ocrContent);
            assertEquals("ocrContent", ocrContent.pipelineStepId());
            assertEquals(TransportType.KAFKA, ocrContent.transportType());
            assertNotNull(ocrContent.kafkaConfig());
            assertEquals(List.of("documents.forTextProcessing.v1"), ocrContent.kafkaConfig().listenTopics());
            assertEquals("processing.ocrContent.textOutput", ocrContent.kafkaConfig().publishTopicPattern());
            assertNotNull(ocrContent.customConfig());
            assertEquals("{\"languages\":[\"eng\",\"fra\"], \"minConfidence\":0.7}", ocrContent.customConfig().jsonConfig());

            PipelineStepConfig generateEmbeddings = docProcPipeline.pipelineSteps().get("generateEmbeddings");
            assertNotNull(generateEmbeddings);
            assertEquals("generateEmbeddings", generateEmbeddings.pipelineStepId());
            assertEquals(TransportType.GRPC, generateEmbeddings.transportType());
            assertNotNull(generateEmbeddings.grpcConfig());
            assertEquals("embedding-service-v2", generateEmbeddings.grpcConfig().serviceId());

            PipelineStepConfig indexDocument = docProcPipeline.pipelineSteps().get("indexDocument");
            assertNotNull(indexDocument);
            assertEquals("indexDocument", indexDocument.pipelineStepId());
            assertEquals(TransportType.INTERNAL, indexDocument.transportType());
            assertTrue(indexDocument.nextSteps().isEmpty()); // Assuming no next step from JSON
        }
    }

    // Test for validation logic in PipelineStepConfig constructor
    @Test
    void testPipelineStepConfig_Validation_MissingTransportConfig() {
        // KAFKA type but missing kafkaConfig
        Exception eKafka = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s1", "impl1", null, null, null,
                    TransportType.KAFKA,
                    null, // kafkaConfig is null
                    null,
                    null // stepType defaults to PIPELINE
            );
        });
        assertTrue(eKafka.getMessage().contains("KafkaTransportConfig must be provided"));

        // GRPC type but missing grpcConfig
        Exception eGrpc = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s2", "impl2", null, null, null,
                    TransportType.GRPC,
                    null,
                    null, // grpcConfig is null
                    null  // stepType defaults to PIPELINE
            );
        });
        assertTrue(eGrpc.getMessage().contains("GrpcTransportConfig must be provided"));
    }

    @Test
    void testPipelineStepConfig_Validation_MismatchedTransportConfig() {
        KafkaTransportConfig kConf = new KafkaTransportConfig(null, null, null);
        GrpcTransportConfig gConf = new GrpcTransportConfig("sId", null);

        // KAFKA type but grpcConfig is provided
        Exception eKafkaMismatch = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s1", "impl1", null, null, null,
                    TransportType.KAFKA,
                    kConf,
                    gConf, // grpcConfig should be null
                    null   // stepType defaults to PIPELINE
            );
        });
        assertTrue(eKafkaMismatch.getMessage().contains("GrpcTransportConfig should only be provided"));


        // GRPC type but kafkaConfig is provided
        Exception eGrpcMismatch = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s2", "impl2", null, null, null,
                    TransportType.GRPC,
                    kConf, // kafkaConfig should be null
                    gConf,
                    null  // stepType defaults to PIPELINE
            );
        });
        assertTrue(eGrpcMismatch.getMessage().contains("KafkaTransportConfig should only be provided"));

        // INTERNAL type but kafkaConfig is provided
        Exception eInternalMismatchKafka = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s3", "impl3", null, null, null,
                    TransportType.INTERNAL,
                    kConf, // kafkaConfig should be null
                    null,
                    null  // stepType defaults to PIPELINE
            );
        });
        assertTrue(eInternalMismatchKafka.getMessage().contains("KafkaTransportConfig should only be provided"));

        // INTERNAL type but grpcConfig is provided
        Exception eInternalMismatchGrpc = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig(
                    "s4", "impl4", null, null, null,
                    TransportType.INTERNAL,
                    null,
                    gConf, // grpcConfig should be null
                    null   // stepType defaults to PIPELINE
            );
        });
        assertTrue(eInternalMismatchGrpc.getMessage().contains("GrpcTransportConfig should only be provided"));
    }
}
