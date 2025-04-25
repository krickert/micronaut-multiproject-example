package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeResponse;

/**
 * Interface for processing pipeline streams.
 * Implementations of this interface will handle the actual processing of PipeStream objects.
 */
public interface PipelineServiceProcessor {
    
    /**
     * Process a PipeStream and return a PipeResponse.
     * 
     * @param pipeStream The PipeStream to process
     * @return A PipeResponse indicating the result of processing
     */
    PipeResponse process(PipeStream pipeStream);
}