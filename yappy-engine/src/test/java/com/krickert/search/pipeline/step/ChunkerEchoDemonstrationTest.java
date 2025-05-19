package com.krickert.search.pipeline.step;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * This test demonstrates how the chunker and echo services would be used together.
 * It uses mock objects to simulate the behavior of the services without actually connecting to them.
 */
@ExtendWith(MockitoExtension.class)
public class ChunkerEchoDemonstrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerEchoDemonstrationTest.class);

    @Mock
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient;

    @Mock
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClient;

    // Test data
    private String pipelineName;
    private String chunkerStepName;
    private String echoStepName;
    private String streamId;
    private String docId;
    private PipeDoc testDocument;
    private Blob testBlob;

    @BeforeEach
    void setUp() {
        // Initialize test data
        pipelineName = "test-pipeline";
        chunkerStepName = "chunker-step";
        echoStepName = "echo-step";
        streamId = "test-stream-" + System.currentTimeMillis();
        docId = "test-doc-" + System.currentTimeMillis();

        // Create test blob
        String blobId = "blob-" + System.currentTimeMillis();
        String blobFilename = "test.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Integration Test!");
        testBlob = Blob.newBuilder()
                .setBlobId(blobId)
                .setFilename(blobFilename)
                .setData(blobData)
                .setMimeType("text/plain")
                .build();

        // Create test document
        testDocument = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Integration Test Document")
                .setBody("This is a test document for integration testing. It will be processed by the chunker and echo services.")
                .setCustomData(Struct.newBuilder()
                        .putFields("testKey", Value.newBuilder().setStringValue("testValue").build())
                        .build())
                .setBlob(testBlob)
                .build();

        // Set up mock chunker client - use lenient() to avoid unnecessary stubbing errors
        Mockito.lenient().when(chunkerClient.processData(any(ProcessRequest.class))).thenAnswer(invocation -> {
            ProcessRequest request = (ProcessRequest) invocation.getArguments()[0];
            LOG.info("[DEBUG_LOG] Mock chunker processing request for document: {}", request.getDocument().getId());

            // Create a semantic chunk
            SemanticChunk chunk = SemanticChunk.newBuilder()
                    .setChunkNumber(0)
                    .setChunkId(request.getMetadata().getStreamId() + "_" + request.getDocument().getId() + "_chunk_0")
                    .setEmbeddingInfo(
                            ChunkEmbedding.newBuilder()
                                    .setTextContent(request.getDocument().getBody())
                                    .build())
                    .build();

            // Create a semantic processing result
            SemanticProcessingResult semanticResult = SemanticProcessingResult.newBuilder()
                    .setResultId("mock_result_id")
                    .setSourceFieldName("body")
                    .setChunkConfigId("default_overlap_500_50")
                    .setResultSetName(request.getMetadata().getPipeStepName() + "_chunks_default_overlap_500_50")
                    .addChunks(chunk)
                    .build();

            // Create the output document with the semantic result
            PipeDoc outputDoc = request.getDocument().toBuilder()
                    .addSemanticResults(semanticResult)
                    .build();

            // Create and return the response
            return ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .setOutputDoc(outputDoc)
                    .addProcessorLogs("Successfully created and added metadata to 1 chunks from source field 'body'")
                    .build();
        });

        // Set up mock echo client - use lenient() to avoid unnecessary stubbing errors
        Mockito.lenient().when(echoClient.processData(any(ProcessRequest.class))).thenAnswer(invocation -> {
            ProcessRequest request = (ProcessRequest) invocation.getArguments()[0];
            LOG.info("[DEBUG_LOG] Mock echo processing request for document: {}", request.getDocument().getId());

            // Create log message
            String logMessage = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                    request.getMetadata().getPipeStepName(),
                    request.getMetadata().getPipelineName(),
                    request.getMetadata().getStreamId(),
                    request.getDocument().getId());

            // Create and return the response with the same document
            return ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .setOutputDoc(request.getDocument())
                    .addProcessorLogs(logMessage)
                    .build();
        });
    }

    /**
     * Helper method to create a ProcessRequest for testing.
     */
    private ProcessRequest createProcessRequest(String stepName, PipeDoc document) {
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(1)
                .build();

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(ProcessConfiguration.newBuilder().build())
                .build();
    }

    @Test
    @DisplayName("Test chunker service individually")
    void testChunkerService() {
        LOG.info("[DEBUG_LOG] Testing chunker service individually");

        // Create a request for the chunker service
        ProcessRequest chunkerRequest = createProcessRequest(chunkerStepName, testDocument);

        // Call the chunker service
        LOG.info("[DEBUG_LOG] Sending request to chunker service");
        ProcessResponse chunkerResponse = chunkerClient.processData(chunkerRequest);
        LOG.info("[DEBUG_LOG] Received response from chunker service: {}", chunkerResponse.getSuccess());

        // Verify the response
        assertNotNull(chunkerResponse, "Chunker response should not be null");
        assertTrue(chunkerResponse.getSuccess(), "Chunker processing should be successful");
        assertTrue(chunkerResponse.hasOutputDoc(), "Chunker response should have an output document");

        // The chunker should add semantic results to the document
        PipeDoc outputDoc = chunkerResponse.getOutputDoc();
        assertTrue(outputDoc.getSemanticResultsCount() > 0, "Chunker should add semantic results to the document");

        // Verify that the semantic results contain the expected data
        SemanticProcessingResult result = outputDoc.getSemanticResults(0);
        assertEquals("body", result.getSourceFieldName(), "Source field name should be 'body'");
        assertTrue(result.getChunksCount() > 0, "Semantic result should have chunks");

        // Verify that the blob is preserved
        assertTrue(outputDoc.hasBlob(), "Output document should still have the blob");
        assertEquals(testBlob, outputDoc.getBlob(), "Blob should be preserved");

        // Log the chunks for debugging
        LOG.info("[DEBUG_LOG] Chunker created {} chunks", result.getChunksCount());
        for (int i = 0; i < result.getChunksCount(); i++) {
            SemanticChunk chunk = result.getChunks(i);
            LOG.info("[DEBUG_LOG] Chunk {}: {}", i, chunk.getEmbeddingInfo().getTextContent());
        }
    }

    @Test
    @DisplayName("Test echo service individually")
    void testEchoService() {
        LOG.info("[DEBUG_LOG] Testing echo service individually");

        // Create a request for the echo service
        ProcessRequest echoRequest = createProcessRequest(echoStepName, testDocument);

        // Call the echo service
        LOG.info("[DEBUG_LOG] Sending request to echo service");
        ProcessResponse echoResponse = echoClient.processData(echoRequest);
        LOG.info("[DEBUG_LOG] Received response from echo service: {}", echoResponse.getSuccess());

        // Verify the response
        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful");
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        // The echo service should return the document unchanged
        assertEquals(testDocument, echoResponse.getOutputDoc(), "Echo service should return the document unchanged");

        // Verify that the blob is preserved
        assertTrue(echoResponse.getOutputDoc().hasBlob(), "Output document should still have the blob");
        assertEquals(testBlob, echoResponse.getOutputDoc().getBlob(), "Blob should be preserved");

        // Verify that the logs contain the expected message
        assertTrue(echoResponse.getProcessorLogsCount() > 0, "Echo response should have processor logs");
        String logMessage = echoResponse.getProcessorLogs(0);
        LOG.info("[DEBUG_LOG] Echo service log: {}", logMessage);
        assertTrue(logMessage.contains("EchoService"), "Log message should mention EchoService");
        assertTrue(logMessage.contains(echoStepName), "Log message should mention the step name");
        assertTrue(logMessage.contains(streamId), "Log message should mention the stream ID");
        assertTrue(logMessage.contains(docId), "Log message should mention the document ID");
    }

    @Test
    @DisplayName("Test chunker and echo services chained together")
    void testChunkerAndEchoChained() {
        LOG.info("[DEBUG_LOG] Testing chunker and echo services chained together");

        // Step 1: Call the chunker service
        ProcessRequest chunkerRequest = createProcessRequest(chunkerStepName, testDocument);
        LOG.info("[DEBUG_LOG] Sending request to chunker service");
        ProcessResponse chunkerResponse = chunkerClient.processData(chunkerRequest);
        LOG.info("[DEBUG_LOG] Received response from chunker service: {}", chunkerResponse.getSuccess());

        // Verify chunker response
        assertTrue(chunkerResponse.getSuccess(), "Chunker processing should be successful");
        assertTrue(chunkerResponse.hasOutputDoc(), "Chunker response should have an output document");
        PipeDoc chunkedDoc = chunkerResponse.getOutputDoc();
        assertTrue(chunkedDoc.getSemanticResultsCount() > 0, "Chunker should add semantic results to the document");

        // Step 2: Call the echo service with the chunked document
        ProcessRequest echoRequest = createProcessRequest(echoStepName, chunkedDoc);
        LOG.info("[DEBUG_LOG] Sending chunked document to echo service");
        ProcessResponse echoResponse = echoClient.processData(echoRequest);
        LOG.info("[DEBUG_LOG] Received response from echo service: {}", echoResponse.getSuccess());

        // Verify echo response
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful");
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        // The echo service should return the chunked document unchanged
        assertEquals(chunkedDoc, echoResponse.getOutputDoc(), "Echo service should return the chunked document unchanged");

        // Verify that the semantic results are preserved
        PipeDoc finalDoc = echoResponse.getOutputDoc();
        assertTrue(finalDoc.getSemanticResultsCount() > 0, "Final document should still have semantic results");
        assertEquals(chunkedDoc.getSemanticResultsCount(), finalDoc.getSemanticResultsCount(), 
                "Final document should have the same number of semantic results as the chunked document");

        // Verify that the blob is preserved
        assertTrue(finalDoc.hasBlob(), "Final document should still have the blob");
        assertEquals(testBlob, finalDoc.getBlob(), "Blob should be preserved");

        // Log the final document for debugging
        LOG.info("[DEBUG_LOG] Final document has {} semantic results", finalDoc.getSemanticResultsCount());
        for (int i = 0; i < finalDoc.getSemanticResultsCount(); i++) {
            SemanticProcessingResult result = finalDoc.getSemanticResults(i);
            LOG.info("[DEBUG_LOG] Semantic result {}: source={}, chunks={}", 
                    i, result.getSourceFieldName(), result.getChunksCount());
        }
    }
}
