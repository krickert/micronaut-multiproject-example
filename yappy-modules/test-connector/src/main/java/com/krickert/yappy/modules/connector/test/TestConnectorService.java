package com.krickert.yappy.modules.connector.test;

import com.krickert.search.engine.ConnectorEngineGrpc;
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test implementation of the ConnectorEngine gRPC service.
 * This service provides sample PipeDocs for testing purposes.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.test-connector.enabled", value = "true", defaultValue = "true")
public class TestConnectorService extends ConnectorEngineGrpc.ConnectorEngineImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TestConnectorService.class);
    
    private final TestConnectorHelper testConnectorHelper;
    
    @Inject
    public TestConnectorService(TestConnectorHelper testConnectorHelper) {
        this.testConnectorHelper = testConnectorHelper;
        LOG.info("TestConnectorService initialized with {} sample PipeDocs", 
                testConnectorHelper.getTotalPipeDocCount());
    }
    
    @Override
    public void processConnectorDoc(ConnectorRequest request, StreamObserver<ConnectorResponse> responseObserver) {
        LOG.info("Received processConnectorDoc request from source: {}", request.getSourceIdentifier());
        
        try {
            // Get the next available PipeDoc
            PipeDoc pipeDoc = testConnectorHelper.getNextPipeDoc();
            
            // If we have a PipeDoc, use it to replace the one in the request
            if (pipeDoc != null) {
                // Create a new request with the sample PipeDoc
                ConnectorRequest newRequest = ConnectorRequest.newBuilder()
                        .setSourceIdentifier(request.getSourceIdentifier())
                        .setDocument(pipeDoc)
                        .putAllInitialContextParams(request.getInitialContextParamsMap())
                        .setSuggestedStreamId(request.hasSuggestedStreamId() ? request.getSuggestedStreamId() : "")
                        .build();
                
                // Process the new request
                ConnectorResponse response = testConnectorHelper.processRequest(newRequest);
                
                // Send the response
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                
                LOG.info("Processed connector request successfully. Stream ID: {}, Accepted: {}", 
                        response.getStreamId(), response.getAccepted());
            } else {
                // If we don't have any more PipeDocs, reset and start over
                testConnectorHelper.resetSentStatus();
                
                // Try again with the reset state
                pipeDoc = testConnectorHelper.getNextPipeDoc();
                
                if (pipeDoc != null) {
                    // Create a new request with the sample PipeDoc
                    ConnectorRequest newRequest = ConnectorRequest.newBuilder()
                            .setSourceIdentifier(request.getSourceIdentifier())
                            .setDocument(pipeDoc)
                            .putAllInitialContextParams(request.getInitialContextParamsMap())
                            .setSuggestedStreamId(request.hasSuggestedStreamId() ? request.getSuggestedStreamId() : "")
                            .build();
                    
                    // Process the new request
                    ConnectorResponse response = testConnectorHelper.processRequest(newRequest);
                    
                    // Send the response
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                    
                    LOG.info("Processed connector request successfully after reset. Stream ID: {}, Accepted: {}", 
                            response.getStreamId(), response.getAccepted());
                } else {
                    // This should never happen, but just in case
                    throw new RuntimeException("No sample PipeDocs available");
                }
            }
        } catch (Exception e) {
            LOG.error("Error processing connector request", e);
            
            // In case of an error, still try to send a response
            ConnectorResponse errorResponse = ConnectorResponse.newBuilder()
                    .setStreamId(request.hasSuggestedStreamId() ? request.getSuggestedStreamId() : "error")
                    .setAccepted(false)
                    .setMessage("Error processing request: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(errorResponse);
            responseObserver.onCompleted();
        }
    }
}