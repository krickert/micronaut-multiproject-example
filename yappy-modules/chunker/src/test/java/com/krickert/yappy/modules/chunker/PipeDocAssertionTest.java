package com.krickert.yappy.modules.chunker;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Assuming you have a gRPC client (blockingClient) and a method to create ProcessRequest (createTestProcessRequest)

class PipeDocAssertionTest {

    private static final Logger LOG = LoggerFactory.getLogger(PipeDocAssertionTest.class);

    // Dummy blockingClient for compilation - replace with your actual client
    private ProcessResponse processData(ProcessRequest request) {
        // This is a mock implementation. In your actual test, this would be the gRPC call.
        // For this example, let's assume it returns a ProcessResponse containing the outputDoc
        // that was passed in the request (or a modified version of it).
        // Here, we'll construct a response similar to what the echo service might do.

        PipeDoc serviceOutputDoc = request.getDocument(); // Simulate echo service

        // Simulate that the service might generate a new result_id
        if (serviceOutputDoc.getSemanticResultsCount() > 0) {
            SemanticProcessingResult firstResult = serviceOutputDoc.getSemanticResults(0);
            SemanticProcessingResult modifiedResult = firstResult.toBuilder()
                    .setResultId("new_randomly_generated_id_" + System.nanoTime())
                    .build();
            serviceOutputDoc = serviceOutputDoc.toBuilder().setSemanticResults(0, modifiedResult).build();
        }


        return ProcessResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(serviceOutputDoc)
                .build();
    }

    // Dummy createTestProcessRequest for compilation - replace with your actual method
    private ProcessRequest createTestProcessRequest(String pipelineName, String stepName, String streamId, String docId, long hopNumber, PipeDoc inputDoc, Object unused1, Object unused2) {
        // This is a mock implementation.
        return ProcessRequest.newBuilder()
                // .setMetadata(...) // Set metadata if your createTestProcessRequest does
                .setDocument(inputDoc)
                .build();
    }


    @Test
    void testPipeDocWithSemanticResults() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline";
        String stepName = "echo-step-1";
        String streamId = "stream-123";
        String docId = "doc-abc";
        long hopNumber = 1;

        String blobId = "blob-xyz";
        String blobFilename = "test.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Yappy!");
        Blob inputBlob = Blob.newBuilder()
                .setBlobId(blobId)
                .setFilename(blobFilename)
                .setData(blobData)
                .setMimeType("text/plain")
                .build();

        // Create a semantic chunk
        SemanticChunk chunk = SemanticChunk.newBuilder()
                .setChunkNumber(1)
                .setChunkId("stream-123_body_chunk_0")
                .setEmbeddingInfo(
                        ChunkEmbedding.newBuilder()
                                .setTextContent("This is the body of the test document.")
                                .build())
                .build();

        // Expected SemanticProcessingResult (result_id will be ignored in comparison)
        SemanticProcessingResult expectedSemanticResult = SemanticProcessingResult.newBuilder()
                .setSourceFieldName("body")
                .setChunkConfigId("default_overlap_500_50")
                .setResultSetName("body_chunks_default_overlap_500_50")
                .addChunks(chunk)
                // .setResultId("this_is_randomly_generated_result_id") // We don't set it here as it's random in actual
                .build();

        PipeDoc expectedOutputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder().putFields("key1", Value.newBuilder().setStringValue("value1").build()).build())
                .setBlob(inputBlob)
                .addSemanticResults(expectedSemanticResult) // Add the semantic processing result
                .build();


        // The inputDoc for the request might not have the semantic result,
        // or it might have one with a placeholder ID if the service generates it.
        // For this example, let's assume the inputDoc is simpler and the service adds/modifies semantic results.
        PipeDoc inputDocForRequest = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder().putFields("key1", Value.newBuilder().setStringValue("value1").build()).build())
                .setBlob(inputBlob)
                // Let's add a semantic result with the known ID to the input if that's what your service expects
                // or modify this part based on how your `processData` call works with semantic results.
                // If the service *always* generates a *new* ID, then the inputDoc might have a different one or none.
                .addSemanticResults(expectedSemanticResult.toBuilder().setResultId("placeholder_or_initial_id").build())
                .build();


        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDocForRequest, null, null);

        LOG.info("Sending request to EchoService (Blocking): {}", stepName);

        // 2. Call the gRPC service
        // Replace this with your actual gRPC client call:
        // ProcessResponse response = blockingClient.processData(request);
        ProcessResponse response = processData(request); // Using the dummy method for this example


        LOG.info("Received response from EchoService (Blocking). Success: {}", response.getSuccess());

        // 3. Assert the response
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Response should have an output document");

        // Custom assertion for PipeDoc
        assertPipeDocEquals(expectedOutputDoc, response.getOutputDoc());
    }

    void assertPipeDocEquals(PipeDoc expected, PipeDoc actual) {
        assertNotNull(expected, "Expected PipeDoc should not be null");
        assertNotNull(actual, "Actual PipeDoc should not be null");

        assertEquals(expected.getId(), actual.getId(), "PipeDoc ID mismatch");
        assertEquals(expected.getTitle(), actual.getTitle(), "PipeDoc Title mismatch");
        assertEquals(expected.getBody(), actual.getBody(), "PipeDoc Body mismatch");
        assertEquals(expected.getCustomData(), actual.getCustomData(), "PipeDoc CustomData mismatch");

        // Compare Blob
        if (expected.hasBlob()) {
            assertTrue(actual.hasBlob(), "Expected Blob but was missing in actual");
            assertBlobEquals(expected.getBlob(), actual.getBlob());
        } else {
            assertFalse(actual.hasBlob(), "Did not expect Blob but was present in actual");
        }

        // Compare SemanticResults (ignoring result_id)
        List<SemanticProcessingResult> expectedResults = expected.getSemanticResultsList();
        List<SemanticProcessingResult> actualResults = actual.getSemanticResultsList();
        assertEquals(expectedResults.size(), actualResults.size(), "SemanticResults list size mismatch");

        for (int i = 0; i < expectedResults.size(); i++) {
            assertSemanticProcessingResultEquals(expectedResults.get(i), actualResults.get(i));
        }
    }

    private void assertBlobEquals(Blob expected, Blob actual) {
        assertNotNull(expected, "Expected Blob should not be null");
        assertNotNull(actual, "Actual Blob should not be null");

        assertEquals(expected.getBlobId(), actual.getBlobId(), "Blob ID mismatch");
        assertEquals(expected.getData(), actual.getData(), "Blob Data mismatch");
        assertEquals(expected.getMimeType(), actual.getMimeType(), "Blob MimeType mismatch");
        assertEquals(expected.getFilename(), actual.getFilename(), "Blob Filename mismatch");
    }

    private void assertSemanticProcessingResultEquals(SemanticProcessingResult expected, SemanticProcessingResult actual) {
        assertNotNull(expected, "Expected SemanticProcessingResult should not be null");
        assertNotNull(actual, "Actual SemanticProcessingResult should not be null");

        // Importantly, we do NOT compare getResultId()
        // assertTrue(actual.hasResultId(), "Actual SemanticProcessingResult missing result_id"); // Optionally check if it's present

        assertEquals(expected.getSourceFieldName(), actual.getSourceFieldName(), "SemanticResult SourceFieldName mismatch");
        assertEquals(expected.getChunkConfigId(), actual.getChunkConfigId(), "SemanticResult ChunkConfigId mismatch");
        assertEquals(expected.getResultSetName(), actual.getResultSetName(), "SemanticResult ResultSetName mismatch");

        // Compare Chunks
        List<SemanticChunk> expectedChunks = expected.getChunksList();
        List<SemanticChunk> actualChunks = actual.getChunksList();
        assertEquals(expectedChunks.size(), actualChunks.size(), "SemanticResult Chunks list size mismatch");

        for (int i = 0; i < expectedChunks.size(); i++) {
            assertSemanticChunkEquals(expectedChunks.get(i), actualChunks.get(i));
        }
    }

    private void assertSemanticChunkEquals(SemanticChunk expected, SemanticChunk actual) {
        assertNotNull(expected, "Expected SemanticChunk should not be null");
        assertNotNull(actual, "Actual SemanticChunk should not be null");

        assertEquals(expected.getChunkId(), actual.getChunkId(), "SemanticChunk ChunkId mismatch");
        assertEquals(expected.getChunkNumber(), actual.getChunkNumber(), "SemanticChunk ChunkNumber mismatch");

        // Compare EmbeddingInfo
        if (expected.hasEmbeddingInfo()) {
            assertTrue(actual.hasEmbeddingInfo(), "Expected EmbeddingInfo but was missing in actual chunk");
            assertChunkEmbeddingEquals(expected.getEmbeddingInfo(), actual.getEmbeddingInfo());
        } else {
            assertFalse(actual.hasEmbeddingInfo(), "Did not expect EmbeddingInfo but was present in actual chunk");
        }
    }

    private void assertChunkEmbeddingEquals(ChunkEmbedding expected, ChunkEmbedding actual) {
        assertNotNull(expected, "Expected ChunkEmbedding should not be null");
        assertNotNull(actual, "Actual ChunkEmbedding should not be null");

        assertEquals(expected.getTextContent(), actual.getTextContent(), "ChunkEmbedding TextContent mismatch");
        // Add comparisons for other fields in ChunkEmbedding if any (e.g., vector, token_count)
    }
}