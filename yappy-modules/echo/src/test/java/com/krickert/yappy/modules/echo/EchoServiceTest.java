package com.krickert.yappy.modules.echo; // Match the package of your EchoService

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class EchoServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(EchoServiceTest.class);

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorStub asyncClient;

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

    @Test
    @DisplayName("Should echo back the document (with blob) successfully (Blocking Client)")
    void testProcessData_echoesSuccessfully_blocking() {
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

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder().putFields("key1", Value.newBuilder().setStringValue("value1").build()).build())
                .setBlob(inputBlob) // Blob is now part of PipeDoc
                .build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request to EchoService (Blocking): {}", stepName);

        // 2. Call the gRPC service (method name changed to processData)
        ProcessResponse response = blockingClient.processData(request);

        LOG.info("Received response from EchoService (Blocking). Success: {}", response.getSuccess());

        // 3. Assert the response
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");

        assertTrue(response.hasOutputDoc(), "Response should have an output document");
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match input document");
        assertEquals(docId, response.getOutputDoc().getId(), "Output document ID should match");

        assertTrue(response.getOutputDoc().hasBlob(), "Output document should contain the blob");
        assertEquals(inputBlob, response.getOutputDoc().getBlob(), "Blob in output document should match input blob");

        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty");
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");

        assertFalse(response.hasErrorDetails() && response.getErrorDetails().getFieldsCount() > 0, "There should be no error details");
    }

    @Test
    @DisplayName("Should echo back successfully with custom log prefix (Blocking Client)")
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
                .build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        LOG.info("Sending request with custom config to EchoService (Blocking): {}", stepName);
        ProcessResponse response = blockingClient.processData(request);
        LOG.info("Received response with custom config (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc()); // Output doc should be the same
        assertFalse(response.getOutputDoc().hasBlob(), "Output document should not have a blob in this test case");


        assertFalse(response.getProcessorLogsList().isEmpty());
        String expectedLogMessage = String.format("%sEchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix, stepName, pipelineName, streamId, docId);
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
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch for empty request. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }

    @Test
    @DisplayName("Should echo back the document (with blob) successfully (Async Client)")
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
        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Async Test Doc").setBlob(inputBlob).build();

        ProcessRequest request = createTestProcessRequest(pipelineName, stepName, streamId, docId, hopNumber, inputDoc, null, null);

        LOG.info("Sending request to EchoService (Async): {}", stepName);

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
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match input document (Async)");
        assertTrue(response.getOutputDoc().hasBlob(), "Output document should contain the blob (Async)");
        assertEquals(inputBlob, response.getOutputDoc().getBlob(), "Blob in output document should match input blob (Async)");

        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty (Async)");
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch (Async). Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }
}
