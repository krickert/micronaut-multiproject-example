package com.krickert.yappy.modules.connector.test.server;

import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * Helper class for the connector test server.
 * This class provides methods for simulating different test scenarios.
 */
@Singleton
public class ConnectorTestHelper {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectorTestHelper.class);
    
    // Key in initial_context_params that determines the test result
    public static final String RESULT_RESPONSE_KEY = "result_response";
    
    // Values for RESULT_RESPONSE_KEY
    public static final String SUCCESS_VALUE = "success";
    public static final String FAIL_VALUE = "fail";
    
    /**
     * Process a connector request and return an appropriate response based on the initial_context_params.
     * If initial_context_params contains "result_response" with value "success", the request is accepted.
     * If initial_context_params contains "result_response" with value "fail", the request is rejected.
     * If initial_context_params doesn't contain "result_response", the request is accepted by default.
     *
     * @param request The connector request to process
     * @return A connector response indicating success or failure
     */
    public ConnectorResponse processRequest(ConnectorRequest request) {
        String sourceIdentifier = request.getSourceIdentifier();
        LOG.info("Processing connector request from source: {}", sourceIdentifier);
        
        // Generate a stream ID (use suggested one if provided, otherwise generate a new one)
        String streamId = request.hasSuggestedStreamId() && !request.getSuggestedStreamId().isEmpty() 
                ? request.getSuggestedStreamId() 
                : UUID.randomUUID().toString();
        
        // Check if the request should succeed or fail based on initial_context_params
        boolean shouldSucceed = shouldRequestSucceed(request);
        
        ConnectorResponse.Builder responseBuilder = ConnectorResponse.newBuilder()
                .setStreamId(streamId)
                .setAccepted(shouldSucceed);
        
        if (shouldSucceed) {
            LOG.info("Request accepted for stream ID: {}", streamId);
            responseBuilder.setMessage("Ingestion accepted for stream ID [" + streamId + "], targeting configured pipeline.");
        } else {
            LOG.info("Request rejected for stream ID: {}", streamId);
            responseBuilder.setMessage("Ingestion rejected for stream ID [" + streamId + "] due to test configuration.");
        }
        
        return responseBuilder.build();
    }
    
    /**
     * Determine if a request should succeed based on the initial_context_params.
     * 
     * @param request The connector request to check
     * @return true if the request should succeed, false otherwise
     */
    private boolean shouldRequestSucceed(ConnectorRequest request) {
        // If initial_context_params contains "result_response" with value "fail", the request should fail
        if (request.getInitialContextParamsMap().containsKey(RESULT_RESPONSE_KEY)) {
            String resultResponse = request.getInitialContextParamsMap().get(RESULT_RESPONSE_KEY);
            LOG.info("Found result_response in initial_context_params: {}", resultResponse);
            
            if (FAIL_VALUE.equalsIgnoreCase(resultResponse)) {
                return false;
            } else if (SUCCESS_VALUE.equalsIgnoreCase(resultResponse)) {
                return true;
            } else {
                LOG.warn("Unknown value for result_response: {}. Defaulting to success.", resultResponse);
            }
        }
        
        // Default to success if no result_response is specified or if it has an unknown value
        return true;
    }
}