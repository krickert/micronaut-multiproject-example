package com.krickert.yappy.modules.connector.test.server;

import com.krickert.search.engine.ConnectorEngineGrpc;
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A test implementation of the ConnectorEngine gRPC service.
 * This service is used for testing connectors without requiring a real engine.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.connector-test.enabled", value = "true", defaultValue = "true")
public class ConnectorTestService extends ConnectorEngineGrpc.ConnectorEngineImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorTestService.class);
    
    private final ConnectorTestHelper testHelper;
    
    @Inject
    public ConnectorTestService(ConnectorTestHelper testHelper) {
        this.testHelper = testHelper;
        LOG.info("ConnectorTestService initialized");
    }
    
    @Override
    public void processConnectorDoc(ConnectorRequest request, StreamObserver<ConnectorResponse> responseObserver) {
        LOG.info("Received processConnectorDoc request from source: {}", request.getSourceIdentifier());
        
        try {
            // Use the helper to process the request and determine the response
            ConnectorResponse response = testHelper.processRequest(request);
            
            // Send the response
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
            LOG.info("Processed connector request successfully. Stream ID: {}, Accepted: {}", 
                    response.getStreamId(), response.getAccepted());
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