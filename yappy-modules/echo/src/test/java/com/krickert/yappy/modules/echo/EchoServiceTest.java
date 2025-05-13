package com.krickert.yappy.modules.echo; // Match the package of your EchoService

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessPipeDocRequest;
import com.krickert.search.sdk.ProcessResponse;

import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel; // Micronaut constant for in-process server
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

@MicronautTest // This annotation starts an embedded Micronaut server with your gRPC service
@Property(name = "grpc.client.plaintext", value = "true") // For tests, disable TLS for the client
@Property(name = "micronaut.test.resources.enabled", value = "false") // Explicitly disable test resources client for this test run
class EchoServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(EchoServiceTest.class);

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME) // Injects a client stub targeting the in-process gRPC server
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient; // For existing tests

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorStub asyncClient; // Asynchronous (non-blocking) stub

    @Test
    @DisplayName("Should echo back the document and blob successfully (Blocking Client)")
    void testProcessDocument_echoesSuccessfully_blocking() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline";
        String stepName = "echo-step-1";
        String streamId = "stream-123";
        String docId = "doc-abc";
        String blobId = "blob-xyz";
        String blobFilename = "test.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Yappy!");


        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document Title")
                .setBody("This is the body of the test document.")
                .setCustomData(
                        Struct.newBuilder()
                                .putFields("key1", Value.newBuilder().setStringValue("value1")
                                        .build())
                                .build())
                .build();

        Blob inputBlob = Blob.newBuilder()
                .setBlobId(blobId)
                .setFilename(blobFilename)
                .setData(blobData)
                .setMimeType("text/plain")
                .build();

        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(inputDoc)
                .setBlob(inputBlob)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        ProcessPipeDocRequest request = ProcessPipeDocRequest.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setPipeStreamData(inputStream)
                .build();

        LOG.info("Sending request to EchoService (Blocking): {}", request.getPipeStepName());

        // 2. Call the gRPC service
        ProcessResponse response = blockingClient.processDocument(request);

        LOG.info("Received response from EchoService (Blocking). Success: {}", response.getSuccess());

        // 3. Assert the response
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Response should have an output document");
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match input document");
        assertEquals(docId, response.getOutputDoc().getId(), "Output document ID should match");
        assertTrue(response.hasOutputBlob(), "Response should have an output blob");
        assertEquals(inputBlob, response.getOutputBlob(), "Output blob should match input blob");
        assertEquals(blobId, response.getOutputBlob().getBlobId(), "Output blob ID should match");
        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty");
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
        assertFalse(response.hasErrorDetails() && response.getErrorDetails().getFieldsCount() > 0, "There should be no error details");
    }

    @Test
    @DisplayName("Should echo back successfully with custom log prefix (Blocking Client)")
    void testProcessDocument_withCustomLogPrefix_blocking() {
        String pipelineName = "test-pipeline-custom";
        String stepName = "echo-step-custom";
        String streamId = "stream-456";
        String docId = "doc-def";
        String logPrefix = "CustomPrefix: ";

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Custom Config Doc").build();
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(inputDoc)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        Struct customConfig = Struct.newBuilder()
                .putFields("log_prefix", Value.newBuilder().setStringValue(logPrefix).build())
                .build();

        ProcessPipeDocRequest request = ProcessPipeDocRequest.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setPipeStreamData(inputStream)
                .setCustomJsonConfig(customConfig)
                .build();

        LOG.info("Sending request with custom config to EchoService (Blocking): {}", request.getPipeStepName());
        ProcessResponse response = blockingClient.processDocument(request);
        LOG.info("Received response with custom config (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc());
        assertFalse(response.getProcessorLogsList().isEmpty());
        String expectedLogMessage = String.format("%sEchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix, stepName, pipelineName, streamId, docId);
        assertEquals(expectedLogMessage, response.getProcessorLogs(0), "Processor log message mismatch with custom prefix.");
    }

    @Test
    @DisplayName("Should handle request with no document and no blob (Blocking Client)")
    void testProcessDocument_noDocNoBlob_blocking() {
        String pipelineName = "test-pipeline-empty";
        String stepName = "echo-step-empty";
        String streamId = "stream-789";

        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        ProcessPipeDocRequest request = ProcessPipeDocRequest.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setPipeStreamData(inputStream)
                .build();

        LOG.info("Sending request with no document/blob to EchoService (Blocking): {}", request.getPipeStepName());
        ProcessResponse response = blockingClient.processDocument(request);
        LOG.info("Received response for empty request (Blocking). Success: {}", response.getSuccess());

        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertFalse(response.hasOutputDoc(), "Should be no output document if none was input");
        assertFalse(response.hasOutputBlob(), "Should be no output blob if none was input");
        assertFalse(response.getProcessorLogsList().isEmpty());
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, "N/A");
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch for empty request. Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }

    // --- New Asynchronous Client Test ---
    @Test
    @DisplayName("Should echo back the document and blob successfully (Async Client)")
    void testProcessDocument_echoesSuccessfully_async() throws InterruptedException {
        // 1. Prepare input data (same as blocking test)
        String pipelineName = "test-pipeline-async";
        String stepName = "echo-step-async-1";
        String streamId = "stream-async-123";
        String docId = "doc-async-abc";
        String blobId = "blob-async-xyz";
        String blobFilename = "test-async.txt";
        ByteString blobData = ByteString.copyFromUtf8("Hello, Yappy Async!");

        PipeDoc inputDoc = PipeDoc.newBuilder().setId(docId).setTitle("Async Test Doc").build();
        Blob inputBlob = Blob.newBuilder().setBlobId(blobId).setFilename(blobFilename).setData(blobData).setMimeType("text/plain").build();
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(inputDoc)
                .setBlob(inputBlob)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        ProcessPipeDocRequest request = ProcessPipeDocRequest.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setPipeStreamData(inputStream)
                .build();

        LOG.info("Sending request to EchoService (Async): {}", request.getPipeStepName());

        // 2. Setup for async call
        final CountDownLatch latch = new CountDownLatch(1); // To wait for the response
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        // 3. Call the gRPC service asynchronously
        asyncClient.processDocument(request, new StreamObserver<ProcessResponse>() {
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

        // Wait for the callback to complete, with a timeout
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Async call did not complete in time");

        // 4. Assert the response (or error)
        assertNull(errorReference.get(), "Async call should not have produced an error");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response from async call should not be null");

        assertTrue(response.getSuccess(), "Processing should be successful (Async)");
        assertTrue(response.hasOutputDoc(), "Response should have an output document (Async)");
        assertEquals(inputDoc, response.getOutputDoc(), "Output document should match input document (Async)");
        assertTrue(response.hasOutputBlob(), "Response should have an output blob (Async)");
        assertEquals(inputBlob, response.getOutputBlob(), "Output blob should match input blob (Async)");
        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty (Async)");
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                stepName, pipelineName, streamId, docId);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log message mismatch (Async). Expected to contain: '" + expectedLogMessagePart + "', Actual: '" + response.getProcessorLogs(0) + "'");
    }
}
