package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

// Imports from yappy_core_types.proto (e.g., com.krickert.search.model)
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;
import com.krickert.search.model.StepExecutionRecord;

// Imports from pipe_step_processor_service.proto (e.g., com.krickert.search.sdk)
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;

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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Property(name = "grpc.client.plaintext", value = "true")
@Property(name = "micronaut.test.resources.enabled", value = "false")
@Property(name = "grpc.services.tika-parser.enabled", value = "true")
@Property(name = "tika.parser.test-data-buffer.enabled", value = "true")
@Property(name = "tika.parser.test-data-buffer.precision", value = "3")
class TikaParserServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserServiceTest.class);

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorStub asyncClient;

    private ProcessRequest createTestProcessRequest(
            String pipelineName, String stepName, String streamId, String docId, long hopNumber,
            PipeDoc document, Struct customJsonConfig, Map<String, String> configParams) {

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

    @Test
    @DisplayName("Should parse a text document successfully (Blocking Client)")
    void testProcessData_parsesTextSuccessfully_blocking() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline";
        String stepName = "tika-parser-step";
        String streamId = "stream-123";
        String docId = "doc-abc";
        long hopNumber = 1;

        String blobId = "blob-xyz";
        String blobFilename = "test.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, this is a test document for Tika parsing!");
        Blob inputBlob = Blob.newBuilder()
                .setBlobId(blobId)
                .setFilename(blobFilename)
                .setData(blobData)
                .setMimeType("text/plain")
                .build();

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setBlob(inputBlob)
                .build();

        // Create configuration with extractMetadata=true
        Map<String, String> configParams = new HashMap<>();
        configParams.put("extractMetadata", "true");

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, configParams);

        LOG.info("Sending request to TikaParserService (Blocking): {}", stepName);

        // 2. Call the service
        ProcessResponse response = blockingClient.processData(request);

        // 3. Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");
        assertEquals(docId, response.getOutputDoc().getId(), "Document ID should be preserved");
        assertNotNull(response.getOutputDoc().getBody(), "Document should have a body");
        assertTrue(response.getOutputDoc().getBody().contains("Hello, this is a test document"), 
                "Document body should contain the parsed text");

        // Verify that the original blob is preserved
        assertTrue(response.getOutputDoc().hasBlob(), "Document should still have the original blob");
        assertEquals(blobId, response.getOutputDoc().getBlob().getBlobId(), "Blob ID should be preserved");

        assertTrue(response.getOutputDoc().hasBlob(), "Output document should contain the blob");
        assertEquals(inputBlob, response.getOutputDoc().getBlob(), "Blob in output document should match input blob");

        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty");
        String expectedLogMessagePart = String.format("TikaParserService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");

        assertFalse(response.hasErrorDetails() && response.getErrorDetails().getFieldsCount() > 0, "There should be no error details");
    }

    @Test
    @DisplayName("Should handle document without blob gracefully (Blocking Client)")
    void testProcessData_handlesNoBlobGracefully_blocking() {
        String pipelineName = "test-pipeline-custom";
        String stepName = "tika-parser-step-custom";
        String streamId = "stream-456";
        String docId = "doc-def";
        long hopNumber = 2;
        String logPrefix = "CustomPrefix: ";

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Custom Config Doc").build(); // No blob for this test

        Struct customJsonConfig = Struct.newBuilder()
                .putFields("log_prefix", Value.newBuilder().setStringValue(logPrefix).build())
                .build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        LOG.info("Sending request with custom config to TikaParserService (Blocking): {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response with custom config (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc()); // Output doc should be the same
        assertFalse(response.getOutputDoc().hasBlob(), "Output document should not have a blob in this test case");


        assertFalse(response.getProcessorLogsList().isEmpty());
        String expectedLogMessage = String.format("%sTikaParserService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix, stepName, pipelineName, streamId, docId);
        assertEquals(expectedLogMessage, response.getProcessorLogs(0), "Processor log message mismatch with custom prefix.");
    }

    @Test
    @DisplayName("Should parse a document with custom configuration (Blocking Client)")
    void testProcessData_withCustomConfig_blocking() {
        String pipelineName = "test-pipeline-custom-config";
        String stepName = "tika-parser-step-custom-config";
        String streamId = "stream-789";
        String docId = "doc-custom-config"; // Document with custom configuration
        long hopNumber = 3;

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).build(); // An "empty" document, no blob

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request with empty document to TikaParserService (Blocking): {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response for empty document request (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc(), "Should have an output document even if input was 'empty'");
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match the 'empty' input document");
        assertFalse(response.getOutputDoc().hasBlob(), "Output document should not have a blob");


        assertFalse(response.getProcessorLogsList().isEmpty());
        // The TikaParserService uses document.getId() for the log, which is "doc-empty" here.
        String expectedLogMessagePart = String.format("TikaParserService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch for empty request. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }

    @Test
    @DisplayName("Should parse a document asynchronously")
    void testProcessData_parsesSuccessfully_async() throws InterruptedException {
        // 1. Prepare input data
        String pipelineName = "test-pipeline-async";
        String stepName = "tika-parser-step-async";
        String streamId = "stream-async-123";
        String docId = "doc-async-abc";
        long hopNumber = 1;

        String blobId = "blob-async-xyz";
        String blobFilename = "test-async.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Yappy Async!");
        Blob inputBlob = Blob.newBuilder().setBlobId(blobId).setFilename(blobFilename).setData(blobData).setMimeType("text/plain").build();
        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Async Test Doc").setBlob(inputBlob).build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request to TikaParserService (Async): {}", stepName);

        // 2. Setup for async call
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        // 3. Call the gRPC service asynchronously (method name changed to processData)
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

        // 4. Assert the response (or error)
        assertNull(errorReference.get(), "Async call should not have produced an error");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response from async call should not be null");

        assertTrue(response.getSuccess(), "Processing should be successful (Async)");
        assertTrue(response.hasOutputDoc(), "Response should have an output document (Async)");

        // Verify important fields match instead of exact document equality
        assertEquals(inputDoc.getId(), response.getOutputDoc().getId(), "Document ID should match (Async)");
        assertEquals(inputDoc.getTitle(), response.getOutputDoc().getTitle(), "Document title should match (Async)");
        assertTrue(response.getOutputDoc().hasBlob(), "Output document should contain the blob (Async)");
        assertEquals(inputBlob, response.getOutputDoc().getBlob(), "Blob in output document should match input blob (Async)");

        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty (Async)");
        String expectedLogMessagePart = String.format("TikaParserService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch (Async). Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
        assertFalse(response.hasErrorDetails() && response.getErrorDetails().getFieldsCount() > 0, "There should be no error details (Async)");
    }
}
