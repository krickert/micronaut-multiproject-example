package com.krickert.search.pipeline.service;

import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.model.PipeStream;

/**
 * Interface for processing pipeline streams.
 * Implementations of this interface will handle the actual processing of PipeStream objects.
 */
public interface PipelineServiceProcessor {

    /**
     * Process a PipeStream and return a PipeServiceDto.
     * 
     * @param pipeStream The PipeStream to process
     * @return A PipeServiceDto containing both the PipeResponse and the modified PipeDoc
     */
     InternalPipeStepResponse process(PipeStream pipeStream);
}
