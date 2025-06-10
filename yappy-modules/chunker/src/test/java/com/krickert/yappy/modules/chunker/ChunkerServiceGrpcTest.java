package com.krickert.yappy.modules.chunker; // Match the package of your EchoService

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Property(name = "grpc.client.plaintext", value = "true")
@Property(name = "micronaut.test.resources.enabled", value = "false")
class ChunkerServiceGrpcTest {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerServiceGrpcTest.class);

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorStub asyncClient;

    PipeDocAssertionTest assertionTest = new PipeDocAssertionTest();

    private ProcessRequest createTestProcessRequest(
            String pipelineName, String stepName, String streamId, String docId, long hopNumber,
            PipeDoc document, Struct customJsonConfig, java.util.Map<String, String> configParams) {

        ServiceMetadata.Builder metadataBuilder = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(hopNumber);
        // Add history, stream_error_data, context_params to metadataBuilder if needed for specific tests

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customJsonConfig != null) {
            configBuilder.setCustomJsonConfig(customJsonConfig);
        }
        if (configParams != null) {
            configBuilder.putAllConfigParams(configParams);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setConfig(configBuilder.build())
                .setMetadata(metadataBuilder.build())
                .build();
    }

    // In ChunkerServiceGrpcTest.java

    @Test
    @DisplayName("Should chunk back the document (with blob) successfully (Blocking Client)")
    void testProcessData_echoesSuccessfully_blocking() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline";
        String stepName = "echo-step-1"; // Note: Test name implies echo, but it's testing ChunkerService
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

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder().putFields("key1", Value.newBuilder().setStringValue("value1").build()).build())
                .setBlob(inputBlob)
                .build();

        SemanticChunk expectedChunk = SemanticChunk.newBuilder()
                .setChunkNumber(0)
                .setChunkId(String.format(ChunkerOptions.DEFAULT_CHUNK_ID_TEMPLATE, streamId, docId, 0))
                .setEmbeddingInfo(
                        ChunkEmbedding.newBuilder()
                                .setTextContent("This is the body of the test document.")
                                .setChunkConfigId(ChunkerOptions.DEFAULT_CHUNK_CONFIG_ID)
                                .setOriginalCharStartOffset(0)
                                .setOriginalCharEndOffset("This is the body of the test document.".length() - 1)
                                .build())
                .build();

        String expectedResultSetName = String.format(
                ChunkerOptions.DEFAULT_RESULT_SET_NAME_TEMPLATE,
                stepName,
                ChunkerOptions.DEFAULT_CHUNK_CONFIG_ID
        ).replaceAll("[^a-zA-Z0-9_\\-]", "_");

        SemanticProcessingResult expectedSemanticResult = SemanticProcessingResult.newBuilder()
                .setResultId("this_is_randomly_generated_result_id")
                .setSourceFieldName(ChunkerOptions.DEFAULT_SOURCE_FIELD)
                .setChunkConfigId(ChunkerOptions.DEFAULT_CHUNK_CONFIG_ID)
                .setResultSetName(expectedResultSetName)
                // .setEmbeddingConfigId("none") // REMOVE THIS - expect empty string by default
                .addChunks(expectedChunk)
                .build();

        PipeDoc expectedOutputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder().putFields("key1", Value.newBuilder().setStringValue("value1").build()).build())
                .setBlob(inputBlob)
                .addSemanticResults(expectedSemanticResult)
                .build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request to ChunkerService (Blocking) for default options test: {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response from ChunkerService (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Response should have an output document");

        PipeDoc actualOutputDoc = response.getOutputDoc();
        // ... (other assertions for PipeDoc fields) ...
        assertEquals(expectedOutputDoc.getId(), actualOutputDoc.getId());
        assertEquals(expectedOutputDoc.getTitle(), actualOutputDoc.getTitle());
        assertEquals(expectedOutputDoc.getBody(), actualOutputDoc.getBody());
        assertEquals(expectedOutputDoc.getCustomData(), actualOutputDoc.getCustomData());
        assertEquals(expectedOutputDoc.getBlob(), actualOutputDoc.getBlob());


        assertEquals(1, actualOutputDoc.getSemanticResultsCount(), "Should be one semantic result for default chunking of short text");
        SemanticProcessingResult actualSemanticResult = actualOutputDoc.getSemanticResults(0);

        assertEquals(expectedSemanticResult.getSourceFieldName(), actualSemanticResult.getSourceFieldName());
        assertEquals(expectedSemanticResult.getChunkConfigId(), actualSemanticResult.getChunkConfigId());
        assertEquals(expectedSemanticResult.getResultSetName(), actualSemanticResult.getResultSetName());
        // Assert that it's an empty string now
        assertEquals("", actualSemanticResult.getEmbeddingConfigId(), "SemanticResult EmbeddingConfigId mismatch - should be empty");
        assertEquals(1, actualSemanticResult.getChunksCount());
        assertionTest.assertSemanticChunkEquals(expectedSemanticResult.getChunks(0), actualSemanticResult.getChunks(0));

        // ... (rest of assertions) ...
        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty");
        String expectedLogMessagePart = String.format("Successfully created and added metadata to %d chunks from source field '%s'",
                actualSemanticResult.getChunksCount(), ChunkerOptions.DEFAULT_SOURCE_FIELD);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");

        assertFalse(response.hasErrorDetails() && response.getErrorDetails().getFieldsCount() > 0, "There should be no error details");
    }

    @Test
    @DisplayName("Should return back successfully with custom log prefix (Blocking Client)")
    void testProcessData_withCustomLogPrefix_blocking() {
        String pipelineName = "test-pipeline-custom";
        String stepName = "echo-step-custom";
        String streamId = "stream-456";
        String docId = "doc-def";
        long hopNumber = 2;
        String logPrefix = "CustomPrefix: ";

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Custom Config Doc").build(); // No blob for this test

        Struct customJsonConfig = Struct.newBuilder()
                .putFields("log_prefix", Value.newBuilder().setStringValue(logPrefix).build())
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(500).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(50).build())
                .putFields("chunk_id_template", Value.newBuilder().setStringValue("%s_%s_chunk_%d").build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("default_overlap_500_50").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue("%s_chunks_default_overlap_500_50").build())
                .build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        LOG.info("Sending request with custom config to ChunkerService (Blocking): {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response with custom config (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc()); // Output doc should be the same
        assertFalse(response.getOutputDoc().hasBlob(), "Output document should not have a blob in this test case");


        assertFalse(response.getProcessorLogsList().isEmpty());
        String expectedLogMessage = "CustomPrefix: No content in 'body' to chunk for document ID: doc-def";
        assertEquals(expectedLogMessage, response.getProcessorLogs(0), "Processor log message mismatch with custom prefix.");
    }

    @Test
    @DisplayName("Should handle request with an empty document (Blocking Client)")
    void testProcessData_emptyDoc_blocking() {
        String pipelineName = "test-pipeline-empty-doc";
        String stepName = "echo-step-empty-doc";
        String streamId = "stream-789";
        String docId = "doc-empty"; // Document has an ID but might be otherwise empty
        long hopNumber = 3;

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).build(); // An "empty" document, no blob

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request with empty document to EchoService (Blocking): {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response for empty document request (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc(), "Should have an output document even if input was 'empty'");
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match the 'empty' input document");
        assertFalse(response.getOutputDoc().hasBlob(), "Output document should not have a blob");


        assertFalse(response.getProcessorLogsList().isEmpty());
        // The EchoService uses document.getId() for the log, which is "doc-empty" here.
        String expectedLogMessagePart = "No content in 'body' to chunk for document ID: doc-empty";
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch for empty request. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }

    @Test
    @DisplayName("Should chunk back the document (with blob) successfully (Async Client)")
    void testProcessData_echoesSuccessfully_async() throws InterruptedException {
        // 1. Prepare input data
        String pipelineName = "test-pipeline-async";
        String stepName = "echo-step-async-1";
        String streamId = "stream-async-123";
        String docId = "doc-async-abc";
        long hopNumber = 1;

        String blobId = "blob-async-xyz";
        String blobFilename = "test-async.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Yappy Async!");
        Blob inputBlob = Blob.newBuilder().setBlobId(blobId).setFilename(blobFilename).setData(blobData).setMimeType("text/plain").build();
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Async Test Doc")
                .setBody("This is the body of the async test document.")
                .setBlob(inputBlob)
                .build();


        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request to EchoService (Async): {}", stepName);

        // 2. Setup for async call
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        asyncClient.processData(request, new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse value) {
                LOG.info("Async onNext called with response. Success: {}", value.getSuccess());
                responseReference.set(value);
            }

            @Override
            public void onError(Throwable t) {
                LOG.error("Async onError called", t);
                errorReference.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                LOG.info("Async onCompleted called");
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Async call did not complete in time");
        assertNull(errorReference.get(), "Async call should not have produced an error");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response from async call should not be null");

        assertTrue(response.getSuccess(), "Processing should be successful (Async)");
        assertTrue(response.hasOutputDoc(), "Response should have an output document (Async)");
        SemanticProcessingResult asyncResult = response.getOutputDoc().getSemanticResults(0);
        assertEquals(ChunkerOptions.DEFAULT_CHUNK_CONFIG_ID, asyncResult.getChunkConfigId(), "ChunkConfigId should match default (Async)");
        assertEquals("", asyncResult.getEmbeddingConfigId(), "EmbeddingConfigId should be empty (Async)"); // <<< Align here too

        // Check specific fields instead of the entire document
        assertEquals(docId, response.getOutputDoc().getId(), "Output document ID should match input document ID (Async)");
        assertEquals(inputDoc.getTitle(), response.getOutputDoc().getTitle(), "Output document title should match input document title (Async)");
        assertEquals(inputDoc.getBody(), response.getOutputDoc().getBody(), "Output document body should match input document body (Async)");

        assertTrue(response.getOutputDoc().hasBlob(), "Output document should contain the blob (Async)");
        assertEquals(inputBlob, response.getOutputDoc().getBlob(), "Blob in output document should match input blob (Async)");

        // Check that semantic results were added
        assertTrue(response.getOutputDoc().getSemanticResultsCount() > 0, "Output document should have semantic results (Async)");

        // Check that the chunk ID follows the expected format
        String expectedChunkIdPrefix = streamId + "_" + docId + "_chunk_";
        assertTrue(response.getOutputDoc().getSemanticResults(0).getChunks(0).getChunkId().startsWith(expectedChunkIdPrefix),
                "Chunk ID should start with the expected prefix (Async)");

        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty (Async)");
        String expectedLogMessagePart = "Successfully created and added metadata to 1 chunks from source field 'body'";
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch (Async). Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }
    @Test
    @DisplayName("Should respect different chunk configurations for the same document")
    void testProcessData_respectsDifferentChunkConfigurations() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline-config";
        String stepName = "chunker-step-config";
        String streamId = "stream-config-123";
        String docId = "doc-config-abc";
        long hopNumber = 1;

        // Create a longer document to ensure multiple chunks
        StringBuilder bodyBuilder = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            bodyBuilder.append("This is paragraph ").append(i + 1).append(" of the test document. ");
            bodyBuilder.append("It contains multiple sentences to ensure that we have enough text for chunking. ");
            bodyBuilder.append("The chunker service should split this text into chunks based on the configured chunk size and overlap. ");
            bodyBuilder.append("Each chunk will be processed and metadata will be extracted.\n\n");
        }
        String body = bodyBuilder.toString();

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document for Chunk Configuration")
                .setBody(body)
                .build();

        // 2. Create first configuration with small chunks and small overlap
        Struct smallChunksConfig = Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(200).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(20).build())
                .putFields("chunk_id_template", Value.newBuilder().setStringValue("%s_%s_chunk_%d").build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("small_chunks_200_20").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue("%s_chunks_%s").build())
                .build();

        ProcessRequest smallChunksRequest = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, smallChunksConfig, null);

        LOG.info("Sending request with small chunks configuration to ChunkerService");
        ProcessResponse smallChunksResponse = blockingClient.processData(smallChunksRequest);
        LOG.info("Received response with small chunks configuration. Success: {}", smallChunksResponse.getSuccess());

        // 3. Create second configuration with large chunks and large overlap
        Struct largeChunksConfig = Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(500).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(100).build())
                .putFields("chunk_id_template", Value.newBuilder().setStringValue("%s_%s_chunk_%d").build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("large_chunks_500_100").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue("%s_chunks_%s").build())
                .build();

        ProcessRequest largeChunksRequest = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, largeChunksConfig, null);

        LOG.info("Sending request with large chunks configuration to ChunkerService");
        ProcessResponse largeChunksResponse = blockingClient.processData(largeChunksRequest);
        LOG.info("Received response with large chunks configuration. Success: {}", largeChunksResponse.getSuccess());

        // 4. Verify both responses
        assertNotNull(smallChunksResponse, "Small chunks response should not be null");
        assertTrue(smallChunksResponse.getSuccess(), "Small chunks processing should be successful");
        assertTrue(smallChunksResponse.hasOutputDoc(), "Small chunks response should have an output document");

        assertNotNull(largeChunksResponse, "Large chunks response should not be null");
        assertTrue(largeChunksResponse.getSuccess(), "Large chunks processing should be successful");
        assertTrue(largeChunksResponse.hasOutputDoc(), "Large chunks response should have an output document");

        // 5. Verify that the semantic results have the correct chunk config IDs
        assertTrue(smallChunksResponse.getOutputDoc().getSemanticResultsCount() > 0, 
                "Small chunks output document should have semantic results");
        assertEquals("small_chunks_200_20", 
                smallChunksResponse.getOutputDoc().getSemanticResults(0).getChunkConfigId(),
                "Small chunks should have the correct chunk config ID");

        assertTrue(largeChunksResponse.getOutputDoc().getSemanticResultsCount() > 0, 
                "Large chunks output document should have semantic results");
        assertEquals("large_chunks_500_100", 
                largeChunksResponse.getOutputDoc().getSemanticResults(0).getChunkConfigId(),
                "Large chunks should have the correct chunk config ID");

        // 6. Verify that the number of chunks is different between the two configurations
        int smallChunksCount = smallChunksResponse.getOutputDoc().getSemanticResults(0).getChunksCount();
        int largeChunksCount = largeChunksResponse.getOutputDoc().getSemanticResults(0).getChunksCount();

        LOG.info("Small chunks count: {}, Large chunks count: {}", smallChunksCount, largeChunksCount);

        // Small chunks should produce more chunks than large chunks
        assertTrue(smallChunksCount > largeChunksCount, 
                "Small chunks configuration should produce more chunks than large chunks configuration");
    }

    @Test
    @DisplayName("Should handle long documents and produce multiple chunks")
    void testProcessData_longDocument() throws IOException {
        // 1. Load the US Constitution document
        String usConstitutionPath = "/us_constitution.txt";
        InputStream inputStream = getClass().getResourceAsStream(usConstitutionPath);
        if (inputStream == null) {
            fail("Could not find US Constitution document at " + usConstitutionPath);
        }

        String usConstitution = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        inputStream.close();

        // 2. Prepare input data
        String pipelineName = "test-pipeline-long";
        String stepName = "chunker-step-long";
        String streamId = "stream-long-123";
        String docId = "doc-long-abc";
        long hopNumber = 1;

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("US Constitution")
                .setBody(usConstitution)
                .build();

        // 3. Create configuration with moderate chunk size
        Struct config = Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(1000).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(100).build())
                .putFields("chunk_id_template", Value.newBuilder().setStringValue("%s_%s_chunk_%d").build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("constitution_1000_100").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue("%s_chunks_%s").build())
                .build();

        ProcessRequest request = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, config, null);

        LOG.info("Sending US Constitution document to ChunkerService");
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response for US Constitution document. Success: {}", response.getSuccess());

        // 4. Verify the response
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Response should have an output document");

        // 5. Verify that multiple chunks were created
        assertTrue(response.getOutputDoc().getSemanticResultsCount() > 0, 
                "Output document should have semantic results");

        int chunksCount = response.getOutputDoc().getSemanticResults(0).getChunksCount();
        LOG.info("US Constitution document produced {} chunks", chunksCount);

        assertTrue(chunksCount > 5, 
                "US Constitution document should produce more than 5 chunks");

        // 6. Verify that the chunks have the expected properties
        for (int i = 0; i < Math.min(3, chunksCount); i++) {
            LOG.info("Chunk {}: {} characters", i, 
                    response.getOutputDoc().getSemanticResults(0).getChunks(i).getEmbeddingInfo().getTextContent().length());
        }
    }
}
