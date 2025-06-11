package com.krickert.search.engine.integration;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that runs the engine in a container
 * and verifies the full pipeline: Engine -> Chunker -> TestModule -> Kafka
 * 
 * This test simulates a production-like environment where all components
 * run in containers and communicate over the Docker network.
 */
@MicronautTest
@KafkaListener(groupId = "engine-e2e-test", 
               offsetReset = io.micronaut.configuration.kafka.annotation.OffsetReset.EARLIEST)
class EngineContainerE2ETest {

    private static final Logger logger = LoggerFactory.getLogger(EngineContainerE2ETest.class);

    // List to collect messages from test-module via Kafka
    private final List<PipeStream> receivedMessages = new CopyOnWriteArrayList<>();

    @Topic("test-module-output")
    void receiveFromTestModule(UUID key, PipeStream value) {
        logger.info("Received message from test-module: streamId={}, docId={}, chunks={}", 
            value.getStreamId(), 
            value.hasDocument() ? value.getDocument().getId() : "no-doc",
            value.hasDocument() && value.getDocument().getSemanticResultsCount() > 0 
                ? value.getDocument().getSemanticResults(0).getChunksCount() : 0);
        receivedMessages.add(value);
    }

    // Engine container properties injected by test resources
    @Property(name = "engine.grpc.host")
    String engineHost;
    
    @Property(name = "engine.grpc.port")
    Integer enginePort;

    private ManagedChannel engineChannel;
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub engineStub;

    @BeforeEach
    void setup() {
        // Clear any previous messages
        receivedMessages.clear();

        // Create gRPC channel to engine container
        logger.info("Connecting to engine at {}:{}", engineHost, enginePort);
        engineChannel = ManagedChannelBuilder
            .forAddress(engineHost, enginePort)
            .usePlaintext()
            .build();
        
        engineStub = PipeStreamEngineGrpc.newBlockingStub(engineChannel);

        // Give containers time to fully initialize
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testFullPipeline_EngineToChunkerToTestModule() {
        // Given - a document to process
        String streamId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-e2e")
                .setTitle("E2E Test Document")
                .setBody("This is an end-to-end test document that should be processed by the engine. " +
                        "The engine will route it to the chunker module. " +
                        "The chunker will split this into multiple chunks. " +
                        "Each chunk will then be sent to the test module. " +
                        "Finally, the test module will output the results to Kafka.")
                .build();

        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();

        logger.info("=== Starting E2E test with document: {} ===", doc.getId());
        logger.info("Document body length: {} characters", doc.getBody().length());

        // When - submit document to engine via gRPC
        try {
            var response = engineStub.testPipeStream(inputStream);
            logger.info("Engine accepted document, response: {}", response);
        } catch (Exception e) {
            logger.error("Failed to submit document to engine", e);
            fail("Failed to submit document to engine: " + e.getMessage());
        }

        // Then - wait for messages from test-module via Kafka
        logger.info("Waiting for messages from test-module via Kafka...");

        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertFalse(receivedMessages.isEmpty(), 
                    "Expected to receive messages from test-module via Kafka");
                logger.info("SUCCESS: Received {} messages from test-module", receivedMessages.size());
            });

        // Verify the results
        assertEquals(1, receivedMessages.size(), "Expected exactly one PipeStream message");

        PipeStream resultStream = receivedMessages.get(0);
        PipeDoc resultDoc = resultStream.getDocument();

        logger.info("=== E2E Test Results ===");
        logger.info("Document ID: {}", resultDoc.getId());
        logger.info("Number of semantic results: {}", resultDoc.getSemanticResultsCount());

        // The chunker should have added semantic results
        assertTrue(resultDoc.getSemanticResultsCount() > 0, 
            "Expected at least one semantic result from chunker");

        // Get the semantic result from chunker
        var semanticResult = resultDoc.getSemanticResults(0);
        int chunkCount = semanticResult.getChunksCount();

        logger.info("Result set name: {}", semanticResult.getResultSetName());
        logger.info("Number of chunks created: {}", chunkCount);

        // Verify multiple chunks were created (given the text length and chunk size)
        assertTrue(chunkCount >= 4, 
            "Expected at least 4 chunks for the test document, but got: " + chunkCount);

        // Log details about each chunk
        for (int i = 0; i < chunkCount; i++) {
            var chunk = semanticResult.getChunks(i);
            logger.info("Chunk {}: ID={}, length={} chars", 
                i, 
                chunk.getChunkId(),
                chunk.getEmbeddingInfo().getTextContent().length());
        }

        logger.info("=== E2E test completed successfully! {} chunks processed ===", chunkCount);
    }
}