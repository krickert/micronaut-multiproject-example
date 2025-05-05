package com.krickert.search.config.consul.service;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.engine.EngineProcessRequest;
import com.krickert.search.engine.EngineProcessResponse;
import com.krickert.search.model.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify the functionality of the DummyPipelineServiceImpl.
 * This is a direct test of the implementation without using the Micronaut context.
 */
public class DummyPipelineServiceImplTest {

    private DummyPipelineServiceImpl dummyPipelineService;

    @BeforeEach
    void setUp() {
        dummyPipelineService = new DummyPipelineServiceImpl();
    }

    @Test
    @DisplayName("process method should enhance existing PipeDoc")
    void testProcessEnhancesExistingPipeDoc() throws Exception {
        // Arrange
        PipeDoc inputDoc = createTestPipeDoc();
        PipeStream inputStream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setPipelineName("test-pipeline")
                .setCurrentHopNumber(0)
                .setCurrentDoc(inputDoc)
                .build();

        EngineProcessRequest request = EngineProcessRequest.newBuilder()
                .setPipelineName("test-pipeline")
                .setInputStream(inputStream)
                .putInitialContextParams("test-param", "test-value")
                .build();

        // Create a latch to wait for the response
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EngineProcessResponse> responseHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();

        // Create a real StreamObserver to capture the response
        StreamObserver<EngineProcessResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(EngineProcessResponse value) {
                responseHolder.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorHolder.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // Act
        dummyPipelineService.process(request, responseObserver);

        // Wait for the response with a timeout
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        // Assert
        assertTrue(completed, "Response should complete within timeout");
        assertNull(errorHolder.get(), "No error should occur");

        EngineProcessResponse response = responseHolder.get();
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getOverallSuccess(), "Response should indicate success");
        assertFalse(response.getEngineLogsList().isEmpty(), "Response should include logs");
        assertTrue(response.hasFinalStream(), "Response should have final stream");

        PipeStream finalStream = response.getFinalStream();
        assertNotNull(finalStream, "Final stream should not be null");
        assertEquals(inputStream.getStreamId(), finalStream.getStreamId(), "Stream ID should be preserved");
        assertEquals(request.getPipelineName(), finalStream.getPipelineName(), "Pipeline name should match");
        assertTrue(finalStream.hasCurrentDoc(), "Final stream should have current doc");

        PipeDoc outputDoc = finalStream.getCurrentDoc();
        assertNotNull(outputDoc, "Output document should not be null");
        assertEquals(inputDoc.getId(), outputDoc.getId(), "Document ID should be preserved");
        assertEquals(inputDoc.getTitle(), outputDoc.getTitle(), "Document title should be preserved");
        assertEquals(inputDoc.getBody(), outputDoc.getBody(), "Document body should be preserved");

        // Check that the document was enhanced
        assertTrue(outputDoc.getKeywordsList().contains("dummy-processed"), "Keywords should include 'dummy-processed'");
        assertTrue(outputDoc.getKeywordsList().contains("enhanced"), "Keywords should include 'enhanced'");
        assertTrue(outputDoc.hasLastModified(), "Last modified timestamp should be set");
        assertTrue(outputDoc.hasCustomData(), "Custom data should be set");
        assertTrue(outputDoc.getCustomData().getFieldsMap().containsKey("processed_by"), 
                "Custom data should include 'processed_by'");
    }

    @Test
    @DisplayName("process method should create new PipeDoc when none is provided")
    void testProcessCreatesNewPipeDocWhenNoneProvided() throws Exception {
        // Arrange
        EngineProcessRequest request = EngineProcessRequest.newBuilder()
                .setPipelineName("test-pipeline")
                .build();

        // Create a latch to wait for the response
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EngineProcessResponse> responseHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();

        // Create a real StreamObserver to capture the response
        StreamObserver<EngineProcessResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(EngineProcessResponse value) {
                responseHolder.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorHolder.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // Act
        dummyPipelineService.process(request, responseObserver);

        // Wait for the response with a timeout
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        // Assert
        assertTrue(completed, "Response should complete within timeout");
        assertNull(errorHolder.get(), "No error should occur");

        EngineProcessResponse response = responseHolder.get();
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getOverallSuccess(), "Response should indicate success");
        assertFalse(response.getEngineLogsList().isEmpty(), "Response should include logs");
        assertTrue(response.hasFinalStream(), "Response should have final stream");

        PipeStream finalStream = response.getFinalStream();
        assertNotNull(finalStream, "Final stream should not be null");
        assertNotNull(finalStream.getStreamId(), "Stream ID should be set");
        assertEquals(request.getPipelineName(), finalStream.getPipelineName(), "Pipeline name should match");
        assertTrue(finalStream.hasCurrentDoc(), "Final stream should have current doc");

        PipeDoc outputDoc = finalStream.getCurrentDoc();
        assertNotNull(outputDoc, "Output document should not be null");
        assertNotNull(outputDoc.getId(), "Document ID should be set");
        assertEquals("Dummy Document", outputDoc.getTitle(), "Document title should be set");
        assertTrue(outputDoc.getBody().contains("dummy document"), "Document body should contain 'dummy document'");
        assertTrue(outputDoc.getKeywordsList().contains("dummy"), "Keywords should include 'dummy'");
        assertTrue(outputDoc.hasCreationDate(), "Creation date should be set");
        assertTrue(outputDoc.hasLastModified(), "Last modified timestamp should be set");
        assertTrue(outputDoc.hasCustomData(), "Custom data should be set");
        assertTrue(outputDoc.hasChunkEmbeddings(), "Chunk embeddings should be set");
        assertTrue(outputDoc.getEmbeddingsCount() > 0, "Embeddings should be present");
    }

    @Test
    @DisplayName("processAsync method should return Empty response")
    void testProcessAsyncReturnsEmptyResponse() throws Exception {
        // Arrange
        EngineProcessRequest request = EngineProcessRequest.newBuilder()
                .setPipelineName("test-pipeline")
                .build();

        // Create a latch to wait for the response
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Empty> responseHolder = new AtomicReference<>();
        AtomicReference<Throwable> errorHolder = new AtomicReference<>();

        // Create a real StreamObserver to capture the response
        StreamObserver<Empty> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(Empty value) {
                responseHolder.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorHolder.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // Act
        dummyPipelineService.processAsync(request, responseObserver);

        // Wait for the response with a timeout
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        // Assert
        assertTrue(completed, "Response should complete within timeout");
        assertNull(errorHolder.get(), "No error should occur");

        Empty response = responseHolder.get();
        assertNotNull(response, "Response should not be null");
        assertEquals(Empty.getDefaultInstance(), response, "Response should be Empty.getDefaultInstance()");
    }

    /**
     * Creates a test PipeDoc for use in tests.
     * 
     * @return A test PipeDoc
     */
    private PipeDoc createTestPipeDoc() {
        String docId = UUID.randomUUID().toString();

        // Create custom data
        Struct customData = Struct.newBuilder()
                .putFields("source", Value.newBuilder().setStringValue("TestSource").build())
                .putFields("test_field", Value.newBuilder().setNumberValue(123.0).build())
                .build();

        // Create the PipeDoc
        return PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document")
                .setBody("This is a test document for testing the DummyPipelineServiceImpl.")
                .addKeywords("test")
                .addKeywords("document")
                .setDocumentType("test")
                .setRevisionId("1")
                .setCreationDate(ProtobufUtils.now())
                .setLastModified(ProtobufUtils.now())
                .setCustomData(customData)
                .build();
    }
}
