package com.krickert.search.pipeline.test.dummy;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the DummyPipeStepProcessor.
 * This verifies that our dummy processor works correctly for self-contained engine testing.
 */
@MicronautTest(environments = {"test"})
public class DummyPipeStepProcessorTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(DummyPipeStepProcessorTest.class);
    
    @Inject
    DummyPipeStepProcessor dummyProcessor; // Direct injection for unit testing
    
    private String testStreamId;
    
    @BeforeEach
    void setUp() {
        testStreamId = "test-stream-" + UUID.randomUUID();
        if (dummyProcessor != null) {
            dummyProcessor.resetProcessCount();
        }
    }
    
    @Test
    void testDummyProcessorEchoBehavior() {
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setTitle("Test Document")
                .setBody("This is a test document body.")
                .build();
        
        // Create request with echo behavior (default)
        ProcessRequest request = createRequest(testDoc, null);
        
        // Process using a test observer
        TestProcessResponseObserver responseObserver = new TestProcessResponseObserver();
        dummyProcessor.processData(request, responseObserver);
        
        // Verify response
        assertFalse(responseObserver.hasError(), "Should not have error");
        assertTrue(responseObserver.hasResponse(), "Should have response");
        
        ProcessResponse response = responseObserver.getResponse();
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Should have output document");
        
        PipeDoc outputDoc = response.getOutputDoc();
        assertEquals(testDoc.getId(), outputDoc.getId(), "Document ID should be preserved");
        assertEquals(testDoc.getTitle(), outputDoc.getTitle(), "Title should be preserved");
        assertEquals(testDoc.getBody(), outputDoc.getBody(), "Body should be unchanged in echo mode");
        
        // Check metadata was added to custom_data
        assertTrue(outputDoc.hasCustomData(), "Should have custom data");
        Struct customData = outputDoc.getCustomData();
        assertTrue(customData.containsFields("dummy_processed"), "Should have processing flag");
        assertEquals("true", customData.getFieldsOrThrow("dummy_processed").getStringValue());
        assertEquals("echo", customData.getFieldsOrThrow("dummy_behavior").getStringValue());
        assertEquals(1.0, customData.getFieldsOrThrow("dummy_process_count").getNumberValue());
        
        // Check logs
        assertTrue(response.getProcessorLogsList().size() >= 2, "Should have at least 2 log entries");
        assertTrue(response.getProcessorLogs(0).contains("Processed document test-doc-1"));
    }
    
    @Test
    void testDummyProcessorAppendBehavior() {
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("test-doc-2")
                .setBody("Original body")
                .build();
        
        // Create config for append behavior
        Struct config = Struct.newBuilder()
                .putFields("behavior", Value.newBuilder().setStringValue("append").build())
                .build();
        
        ProcessRequest request = createRequest(testDoc, config);
        
        TestProcessResponseObserver responseObserver = new TestProcessResponseObserver();
        dummyProcessor.processData(request, responseObserver);
        
        ProcessResponse response = responseObserver.getResponse();
        assertTrue(response.getSuccess());
        
        String outputBody = response.getOutputDoc().getBody();
        assertTrue(outputBody.startsWith("Original body"), "Should start with original");
        assertTrue(outputBody.contains("[Processed by DummyPipeStepProcessor]"), "Should have appended text");
    }
    
    @Test
    void testDummyProcessorSimulatedError() {
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId("test-doc-3")
                .build();
        
        // Create config to simulate error
        Struct config = Struct.newBuilder()
                .putFields("simulate_error", Value.newBuilder().setBoolValue(true).build())
                .build();
        
        ProcessRequest request = createRequest(testDoc, config);
        
        TestProcessResponseObserver responseObserver = new TestProcessResponseObserver();
        dummyProcessor.processData(request, responseObserver);
        
        assertTrue(responseObserver.hasError(), "Should have error");
        assertFalse(responseObserver.hasResponse(), "Should not have response when error");
        assertTrue(responseObserver.getError().getMessage().contains("Simulated error"));
    }
    
    @Test
    void testProcessCount() {
        assertEquals(0, dummyProcessor.getProcessCount(), "Should start at 0");
        
        PipeDoc testDoc = PipeDoc.newBuilder().setId("test-doc").build();
        ProcessRequest request = createRequest(testDoc, null);
        
        // Process multiple times
        for (int i = 1; i <= 3; i++) {
            TestProcessResponseObserver responseObserver = new TestProcessResponseObserver();
            dummyProcessor.processData(request, responseObserver);
            assertEquals(i, dummyProcessor.getProcessCount(), "Count should increment");
        }
        
        dummyProcessor.resetProcessCount();
        assertEquals(0, dummyProcessor.getProcessCount(), "Should reset to 0");
    }
    
    private ProcessRequest createRequest(PipeDoc document, Struct customConfig) {
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("dummy-step")
                .setStreamId(testStreamId)
                .setCurrentHopNumber(1)
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
    
    /**
     * Simple test observer for capturing responses
     */
    private static class TestProcessResponseObserver implements StreamObserver<ProcessResponse> {
        private ProcessResponse response;
        private Throwable error;
        private boolean completed = false;
        
        @Override
        public void onNext(ProcessResponse value) {
            this.response = value;
        }
        
        @Override
        public void onError(Throwable t) {
            this.error = t;
        }
        
        @Override
        public void onCompleted() {
            this.completed = true;
        }
        
        public boolean hasResponse() {
            return response != null;
        }
        
        public ProcessResponse getResponse() {
            return response;
        }
        
        public boolean hasError() {
            return error != null;
        }
        
        public Throwable getError() {
            return error;
        }
        
        public boolean isCompleted() {
            return completed;
        }
    }
}