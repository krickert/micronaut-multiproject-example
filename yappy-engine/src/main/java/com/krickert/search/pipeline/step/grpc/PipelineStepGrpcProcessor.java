package com.krickert.search.pipeline.step.grpc;

import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.exception.PipeStepProcessingException;
import com.krickert.search.sdk.ProcessResponse;

/**
 * Interface for processing pipeline steps via gRPC.
 */
public interface PipelineStepGrpcProcessor {
    /**
     * Process a step via gRPC.
     * 
     * @param pipeStream The input PipeStream to process
     * @param stepName The name of the step to process
     * @return The response from the gRPC service
     * @throws PipeStepProcessingException If an error occurs during processing
     */
    ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException;
}