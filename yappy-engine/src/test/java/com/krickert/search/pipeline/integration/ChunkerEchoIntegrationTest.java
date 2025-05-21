package com.krickert.search.pipeline.integration;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticChunk;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.pipeline.integration.config.TestGrpcClientFactory; // Import your client factory
import com.krickert.search.pipeline.integration.util.TestDocumentGenerator;
import com.krickert.search.sdk.*;
import com.krickert.yappy.modules.chunker.ChunkerOptions; // If you need to reference defaults
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(
        environments = {"combined-test"}, // This environment should load application-combined-test.yml
        startApplication = true,
        transactional = false // Typically false for gRPC/integration tests not directly using DB transactions
)
public class ChunkerEchoIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerEchoIntegrationTest.class);

    @Inject
    @Named("chunkerClientStub")
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient;

    @Inject
    @Named("echoClientStub")
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClient;

    private String pipelineName;
    private String chunkerStepName;
    private String echoStepName;
    private String streamIdBase;

    // Sample documents for testing
    private static List<PipeDoc> sampleDocuments;

    @BeforeAll
    static void loadDocuments() {
        // Load documents once for all tests in this class
        LOG.info("Loading sample documents for ChunkerEchoIntegrationTest...");
        sampleDocuments = TestDocumentGenerator.createSampleDocuments();
        assertTrue(sampleDocuments.size() > 0, "Should load at least one sample document.");
        LOG.info("Loaded {} sample documents.", sampleDocuments.size());

        // Add a delay here to give services time to start up and register with Consul
        // This is often necessary when using additional-applications.
        // Adjust the duration as needed based on your environment.
        try {
            LOG.info("Pausing for 10 seconds to allow services to start and register...");
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Sleep interrupted", e);
        }
    }

    @BeforeEach
    void setUp() {
        pipelineName = "test-pipeline-combined";
        chunkerStepName = "chunker-service-step";
        echoStepName = "echo-service-step";
        streamIdBase = "stream-" + UUID.randomUUID().toString();
        LOG.info("Test setup complete. Clients are injected. StreamId base: {}", streamIdBase);
    }

    private ProcessRequest createProcessRequest(PipeDoc document, String stepName, Struct customConfig, String uniqueStreamIdSuffix, long hop) {
        String currentStreamId = streamIdBase + "_" + uniqueStreamIdSuffix;
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(currentStreamId)
                .setCurrentHopNumber(hop)
                .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customConfig != null) {
            configBuilder.setCustomJsonConfig(customConfig);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(configBuilder.build())
                .build();
    }

    @Test
    @DisplayName("Test Chunker and Echo services sequentially")
    void testChunkerThenEcho() {
        PipeDoc testDoc = sampleDocuments.get(0); // Get a sample document
        assertNotNull(testDoc, "Sample document should not be null");
        LOG.info("Testing with document ID: {}", testDoc.getId());

        // 1. Call Chunker Service
        LOG.info("Sending request to Chunker service...");
        // Using default chunker options by passing null for customConfig
        Struct chunkerCustomConfig = Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(100).build()) // Small chunk size for testing
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(10).build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("test_config_100_10").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue(chunkerStepName + "_chunks_%s").build())
                .build();

        ProcessRequest chunkerRequest = createProcessRequest(testDoc, chunkerStepName, chunkerCustomConfig, testDoc.getId() + "_chunker", 1);
        ProcessResponse chunkerResponse = null;
        try {
            chunkerResponse = chunkerClient.processData(chunkerRequest);
        } catch (Exception e) {
            fail("Call to Chunker service failed: " + e.getMessage(), e);
        }

        LOG.info("Received response from Chunker service. Success: {}", chunkerResponse.getSuccess());
        assertNotNull(chunkerResponse, "Chunker response should not be null");
        assertTrue(chunkerResponse.getSuccess(), "Chunker processing should be successful");
        assertTrue(chunkerResponse.hasOutputDoc(), "Chunker response should have an output document");

        PipeDoc chunkedDoc = chunkerResponse.getOutputDoc();
        if (testDoc.getBody() != null && !testDoc.getBody().isEmpty()) {
            assertTrue(chunkedDoc.getSemanticResultsCount() > 0, "Chunked document should have semantic results");
            SemanticProcessingResult chunkResult = chunkedDoc.getSemanticResults(0);
            assertTrue(chunkResult.getChunksCount() > 0, "Chunker should produce at least one chunk");
            LOG.info("Chunker produced {} chunks for doc ID: {}", chunkResult.getChunksCount(), testDoc.getId());
        } else {
            LOG.info("Input document body was empty, expecting no semantic results from chunker.");
            assertEquals(0, chunkedDoc.getSemanticResultsCount(), "Chunked document should have no semantic results for empty body");
        }

        // 2. Call Echo Service with the (potentially modified) document from Chunker
        LOG.info("Sending request to Echo service with document from Chunker...");
        ProcessRequest echoRequest = createProcessRequest(chunkedDoc, echoStepName, null, testDoc.getId() + "_echo", 2);
        ProcessResponse echoResponse = null;
        try {
            echoResponse = echoClient.processData(echoRequest);
        } catch (Exception e) {
            fail("Call to Echo service failed: " + e.getMessage(), e);
        }

        LOG.info("Received response from Echo service. Success: {}", echoResponse.getSuccess());
        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful");
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        PipeDoc echoedDoc = echoResponse.getOutputDoc();
        // Echo service should return the document as is (including semantic results from chunker)
        assertEquals(chunkedDoc.getId(), echoedDoc.getId(), "Echoed document ID should match");
        assertEquals(chunkedDoc.getTitle(), echoedDoc.getTitle(), "Echoed document title should match");
        assertEquals(chunkedDoc.getBody(), echoedDoc.getBody(), "Echoed document body should match");
        assertEquals(chunkedDoc.getSemanticResultsList(), echoedDoc.getSemanticResultsList(), "Echoed document should retain semantic results from chunker");
        if (chunkedDoc.hasBlob()) {
            assertTrue(echoedDoc.hasBlob(), "Echoed document should retain blob if present");
            assertEquals(chunkedDoc.getBlob(), echoedDoc.getBlob());
        }
    }

    @Test
    @DisplayName("Test Echo service independently")
    void testEchoServiceIndependently() {
        PipeDoc testDoc = TestDocumentGenerator.createSampleDocuments().get(1); // Use a different sample document
        assertNotNull(testDoc, "Sample document for echo test should not be null");
        LOG.info("Testing Echo service independently with document ID: {}", testDoc.getId());

        ProcessRequest echoRequest = createProcessRequest(testDoc, echoStepName, null, testDoc.getId() + "_echo_ind", 1);
        ProcessResponse echoResponse = null;
        try {
            echoResponse = echoClient.processData(echoRequest);
        } catch (Exception e) {
            fail("Call to Echo service (independent) failed: " + e.getMessage(), e);
        }

        LOG.info("Received response from Echo service (independent). Success: {}", echoResponse.getSuccess());
        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful");
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        PipeDoc echoedDoc = echoResponse.getOutputDoc();
        assertEquals(testDoc.getId(), echoedDoc.getId());
        assertEquals(testDoc.getTitle(), echoedDoc.getTitle());
        // Add more assertions as needed for the echo service's behavior
    }
}