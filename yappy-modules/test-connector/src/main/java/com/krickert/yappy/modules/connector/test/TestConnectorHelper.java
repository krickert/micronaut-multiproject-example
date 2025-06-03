package com.krickert.yappy.modules.connector.test;

import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for the test connector.
 * This class provides methods for loading sample PipeDocs and processing connector requests.
 */
@Singleton
public class TestConnectorHelper {
    private static final Logger LOG = LoggerFactory.getLogger(TestConnectorHelper.class);
    
    // Collection of sample PipeDocs
    private final Collection<PipeDoc> samplePipeDocs;
    
    // Map to track which PipeDocs have been sent
    private final Map<String, Boolean> sentPipeDocs = new ConcurrentHashMap<>();
    
    // Counter for tracking request sequence
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    
    /**
     * Constructor that loads the sample PipeDocs.
     */
    public TestConnectorHelper() {
        LOG.info("Initializing TestConnectorHelper");
        this.samplePipeDocs = ProtobufTestDataHelper.loadSamplePipeDocs();
        LOG.info("Loaded {} sample PipeDocs", samplePipeDocs.size());
        
        // Initialize the sentPipeDocs map
        for (PipeDoc doc : samplePipeDocs) {
            sentPipeDocs.put(doc.getId(), false);
        }
    }
    
    /**
     * Process a connector request and return an appropriate response.
     * If this is the first request, it will return the first sample PipeDoc.
     * Subsequent requests will return the next sample PipeDoc until all have been sent.
     *
     * @param request The connector request to process
     * @return A connector response with the appropriate stream ID and acceptance status
     */
    public ConnectorResponse processRequest(ConnectorRequest request) {
        String sourceIdentifier = request.getSourceIdentifier();
        LOG.info("Processing connector request from source: {}", sourceIdentifier);
        
        // Generate a stream ID (use suggested one if provided, otherwise generate a new one)
        String streamId = request.hasSuggestedStreamId() && !request.getSuggestedStreamId().isEmpty() 
                ? request.getSuggestedStreamId() 
                : UUID.randomUUID().toString();
        
        // Always accept the request
        ConnectorResponse.Builder responseBuilder = ConnectorResponse.newBuilder()
                .setStreamId(streamId)
                .setAccepted(true)
                .setMessage("Ingestion accepted for stream ID [" + streamId + "], targeting configured pipeline.");
        
        LOG.info("Request accepted for stream ID: {}", streamId);
        
        return responseBuilder.build();
    }
    
    /**
     * Get the next available PipeDoc that hasn't been sent yet.
     * If all PipeDocs have been sent, it returns null.
     *
     * @return The next available PipeDoc, or null if all have been sent
     */
    public PipeDoc getNextPipeDoc() {
        // Increment the request counter
        int currentRequest = requestCounter.incrementAndGet();
        
        // Find the first PipeDoc that hasn't been sent yet
        Optional<PipeDoc> nextDoc = samplePipeDocs.stream()
                .filter(doc -> !sentPipeDocs.getOrDefault(doc.getId(), true))
                .findFirst();
        
        if (nextDoc.isPresent()) {
            PipeDoc doc = nextDoc.get();
            // Mark this PipeDoc as sent
            sentPipeDocs.put(doc.getId(), true);
            LOG.info("Sending PipeDoc with ID: {} (Request #{})", doc.getId(), currentRequest);
            return doc;
        } else {
            LOG.info("No more PipeDocs to send (Request #{})", currentRequest);
            return null;
        }
    }
    
    /**
     * Reset the sent status of all PipeDocs, allowing them to be sent again.
     */
    public void resetSentStatus() {
        LOG.info("Resetting sent status for all PipeDocs");
        for (String docId : sentPipeDocs.keySet()) {
            sentPipeDocs.put(docId, false);
        }
        requestCounter.set(0);
    }
    
    /**
     * Get the total number of sample PipeDocs.
     *
     * @return The total number of sample PipeDocs
     */
    public int getTotalPipeDocCount() {
        return samplePipeDocs.size();
    }
    
    /**
     * Get the number of PipeDocs that have been sent.
     *
     * @return The number of PipeDocs that have been sent
     */
    public int getSentPipeDocCount() {
        return (int) sentPipeDocs.values().stream().filter(Boolean::booleanValue).count();
    }
}