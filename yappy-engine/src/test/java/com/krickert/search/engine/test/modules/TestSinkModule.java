package com.krickert.search.engine.test.modules;

import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test sink module that collects and validates received messages.
 * This is used in integration tests to verify the pipeline processing.
 */
public class TestSinkModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(TestSinkModule.class);
    
    // Storage for received messages
    private final List<PipeStream> receivedStreams = new ArrayList<>();
    private final List<ProcessRequest> receivedRequests = new ArrayList<>();
    private final Map<String, Object> validationResults = new ConcurrentHashMap<>();
    
    // For synchronization in tests
    private CountDownLatch processLatch;
    private int expectedMessageCount = 1;
    
    // Configuration
    private boolean shouldSucceed = true;
    private String errorMessage = null;
    private long processingDelayMs = 0;
    
    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        LOG.info("TestSink received process request for stream: {}", 
            request.getMetadata().getStreamId());
        
        try {
            // Store the request
            synchronized (receivedRequests) {
                receivedRequests.add(request);
            }
            
            // Extract and store the stream
            PipeStream stream = PipeStream.newBuilder()
                .setStreamId(request.getMetadata().getStreamId())
                .setDocument(request.getDocument())
                .setCurrentPipelineName(request.getMetadata().getPipelineName())
                .setCurrentHopNumber(request.getMetadata().getCurrentHopNumber())
                .addAllHistory(request.getMetadata().getHistoryList())
                .putAllContextParams(request.getMetadata().getContextParamsMap())
                .build();
            
            synchronized (receivedStreams) {
                receivedStreams.add(stream);
            }
            
            // Simulate processing delay if configured
            if (processingDelayMs > 0) {
                Thread.sleep(processingDelayMs);
            }
            
            // Validate the message if validation is configured
            validateMessage(stream);
            
            // Build response
            ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
            
            if (shouldSucceed) {
                // Success response
                responseBuilder.setSuccess(true);
                
                // Optionally modify the document (add a marker that it went through the sink)
                PipeDoc outputDoc = request.getDocument().toBuilder()
                    .addKeywords("processed-by-test-sink")
                    .build();
                
                responseBuilder.setOutputDoc(outputDoc);
                responseBuilder.addProcessorLogs("TestSink successfully processed document");
                
                LOG.info("TestSink successfully processed stream: {}", stream.getStreamId());
            } else {
                // Failure response
                responseBuilder.setSuccess(false);
                
                Struct errorDetails = Struct.newBuilder()
                    .putFields("error", Value.newBuilder()
                        .setStringValue(errorMessage != null ? errorMessage : "Test sink configured to fail")
                        .build())
                    .build();
                
                responseBuilder.setErrorDetails(errorDetails);
                responseBuilder.addProcessorLogs("TestSink failed to process document: " + errorMessage);
                
                LOG.error("TestSink failed to process stream: {}", stream.getStreamId());
            }
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
            
            // Signal that a message was processed
            if (processLatch != null) {
                processLatch.countDown();
            }
            
        } catch (Exception e) {
            LOG.error("Error in TestSink processing", e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
        ServiceRegistrationData registration = ServiceRegistrationData.newBuilder()
            .setModuleName("TestSinkModule")
            .setJsonConfigSchema("{\"type\": \"object\", \"properties\": {" +
                "\"validation\": {\"type\": \"object\"}," +
                "\"shouldSucceed\": {\"type\": \"boolean\"}," +
                "\"errorMessage\": {\"type\": \"string\"}," +
                "\"processingDelayMs\": {\"type\": \"integer\"}" +
                "}}")
            .build();
        
        responseObserver.onNext(registration);
        responseObserver.onCompleted();
    }
    
    private void validateMessage(PipeStream stream) {
        // Perform any validation logic here
        // Store results in validationResults map for test assertions
        
        // Example validations:
        validationResults.put("hasDocument", stream.hasDocument());
        validationResults.put("documentId", stream.getDocument().getId());
        validationResults.put("hopNumber", stream.getCurrentHopNumber());
        validationResults.put("pipelineName", stream.getCurrentPipelineName());
        
        // Check for required fields
        if (!stream.hasDocument()) {
            validationResults.put("error", "No document in stream");
        }
        
        if (stream.getDocument().getId().isEmpty()) {
            validationResults.put("error", "Document has no ID");
        }
    }
    
    // Test helper methods
    
    public void reset() {
        receivedStreams.clear();
        receivedRequests.clear();
        validationResults.clear();
        processLatch = new CountDownLatch(expectedMessageCount);
        shouldSucceed = true;
        errorMessage = null;
        processingDelayMs = 0;
    }
    
    public void setExpectedMessageCount(int count) {
        this.expectedMessageCount = count;
        this.processLatch = new CountDownLatch(count);
    }
    
    public boolean awaitMessages(long timeout, TimeUnit unit) throws InterruptedException {
        return processLatch != null && processLatch.await(timeout, unit);
    }
    
    public List<PipeStream> getReceivedStreams() {
        synchronized (receivedStreams) {
            return new ArrayList<>(receivedStreams);
        }
    }
    
    public List<ProcessRequest> getReceivedRequests() {
        synchronized (receivedRequests) {
            return new ArrayList<>(receivedRequests);
        }
    }
    
    public Map<String, Object> getValidationResults() {
        return new ConcurrentHashMap<>(validationResults);
    }
    
    public void setShouldSucceed(boolean shouldSucceed) {
        this.shouldSucceed = shouldSucceed;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    public void setProcessingDelayMs(long processingDelayMs) {
        this.processingDelayMs = processingDelayMs;
    }
    
    public int getProcessedCount() {
        return receivedStreams.size();
    }
}