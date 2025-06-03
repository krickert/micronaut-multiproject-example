package com.krickert.yappy.modules.connector.test;

import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TestConnectorService class.
 * These tests verify that the service correctly provides sample PipeDocs and processes requests.
 */
public class TestConnectorServiceTest {

    private TestConnectorService testConnectorService;
    private TestConnectorHelper testConnectorHelper;

    @BeforeEach
    public void setUp() {
        // Create a real TestConnectorHelper (no mocks as per requirements)
        testConnectorHelper = new TestConnectorHelper();
        
        // Create the service with the real helper
        testConnectorService = new TestConnectorService(testConnectorHelper);
    }

    @Test
    public void testProcessConnectorDoc() throws InterruptedException {
        // Create a sample request
        String sourceId = "test-source";
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier(sourceId)
                .build();
        
        // Create a real StreamObserver to capture the response
        TestStreamObserver<ConnectorResponse> responseObserver = new TestStreamObserver<>();
        
        // Process the request
        testConnectorService.processConnectorDoc(request, responseObserver);
        
        // Wait for the response
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS), "Response not received in time");
        
        // Verify the response
        assertEquals(1, responseObserver.getValues().size(), "Expected one response");
        ConnectorResponse response = responseObserver.getValues().get(0);
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getAccepted(), "Response should be accepted");
        assertFalse(response.getStreamId().isEmpty(), "Stream ID should not be empty");
        assertFalse(response.getMessage().isEmpty(), "Message should not be empty");
    }
    
    @Test
    public void testProcessMultipleRequests() throws InterruptedException {
        // Get the total number of sample PipeDocs
        int totalPipeDocs = testConnectorHelper.getTotalPipeDocCount();
        
        // Make sure we have at least one PipeDoc
        assertTrue(totalPipeDocs > 0, "No sample PipeDocs available for testing");
        
        // Process requests for all PipeDocs
        for (int i = 0; i < totalPipeDocs; i++) {
            // Create a sample request
            String sourceId = "test-source-" + i;
            ConnectorRequest request = ConnectorRequest.newBuilder()
                    .setSourceIdentifier(sourceId)
                    .build();
            
            // Create a real StreamObserver to capture the response
            TestStreamObserver<ConnectorResponse> responseObserver = new TestStreamObserver<>();
            
            // Process the request
            testConnectorService.processConnectorDoc(request, responseObserver);
            
            // Wait for the response
            assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS), "Response not received in time");
            
            // Verify the response
            assertEquals(1, responseObserver.getValues().size(), "Expected one response");
            assertNotNull(responseObserver.getValues().get(0), "Response should not be null");
        }
        
        // Verify that all PipeDocs have been sent
        assertEquals(totalPipeDocs, testConnectorHelper.getSentPipeDocCount());
        
        // Process one more request to test the reset functionality
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier("test-source-reset")
                .build();
        
        // Create a real StreamObserver to capture the response
        TestStreamObserver<ConnectorResponse> responseObserver = new TestStreamObserver<>();
        
        // Process the request
        testConnectorService.processConnectorDoc(request, responseObserver);
        
        // Wait for the response
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS), "Response not received in time");
        
        // Verify that the sent count is now 1 (after reset)
        assertEquals(1, testConnectorHelper.getSentPipeDocCount());
    }
    
    @Test
    public void testWithSuggestedStreamId() throws InterruptedException {
        // Create a sample request with a suggested stream ID
        String sourceId = "test-source";
        String suggestedStreamId = UUID.randomUUID().toString();
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier(sourceId)
                .setSuggestedStreamId(suggestedStreamId)
                .build();
        
        // Create a real StreamObserver to capture the response
        TestStreamObserver<ConnectorResponse> responseObserver = new TestStreamObserver<>();
        
        // Process the request
        testConnectorService.processConnectorDoc(request, responseObserver);
        
        // Wait for the response
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS), "Response not received in time");
        
        // Verify the response uses the suggested stream ID
        assertEquals(1, responseObserver.getValues().size(), "Expected one response");
        ConnectorResponse response = responseObserver.getValues().get(0);
        assertEquals(suggestedStreamId, response.getStreamId(), "Stream ID should match suggested ID");
    }
    
    @Test
    public void testLoadSamplePipeDocs() {
        // This test verifies that the loadSamplePipeDocs method works correctly
        Collection<PipeDoc> pipeDocs = ProtobufTestDataHelper.loadSamplePipeDocs();
        
        // Verify that we have at least one PipeDoc
        assertFalse(pipeDocs.isEmpty(), "No sample PipeDocs loaded");
        
        // Verify that each PipeDoc has the required fields
        for (PipeDoc doc : pipeDocs) {
            assertNotNull(doc.getId(), "PipeDoc ID is null");
            assertFalse(doc.getId().isEmpty(), "PipeDoc ID is empty");
            assertNotNull(doc.getSourceUri(), "PipeDoc source URI is null");
            assertFalse(doc.getSourceUri().isEmpty(), "PipeDoc source URI is empty");
            assertTrue(doc.hasBlob(), "PipeDoc does not have a blob");
            assertNotNull(doc.getBlob().getData(), "PipeDoc blob data is null");
            assertFalse(doc.getBlob().getData().isEmpty(), "PipeDoc blob data is empty");
        }
    }
    
    /**
     * A real implementation of StreamObserver for testing purposes.
     * This class captures all responses and completion status.
     */
    private static class TestStreamObserver<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private Throwable error;
        
        @Override
        public void onNext(T value) {
            values.add(value);
        }
        
        @Override
        public void onError(Throwable t) {
            error = t;
            completionLatch.countDown();
        }
        
        @Override
        public void onCompleted() {
            completionLatch.countDown();
        }
        
        public List<T> getValues() {
            return values;
        }
        
        public Throwable getError() {
            return error;
        }
        
        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completionLatch.await(timeout, unit);
        }
    }
}