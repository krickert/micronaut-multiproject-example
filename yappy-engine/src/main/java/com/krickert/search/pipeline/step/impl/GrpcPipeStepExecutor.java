package com.krickert.search.pipeline.step.impl;

import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import com.krickert.search.sdk.ProcessResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Implementation of PipeStepExecutor that executes steps via gRPC.
 */
public class GrpcPipeStepExecutor implements PipeStepExecutor {
    private final PipelineStepGrpcProcessor grpcProcessor;
    private final String stepName;
    private final StepType stepType;

    public GrpcPipeStepExecutor(PipelineStepGrpcProcessor grpcProcessor, 
                               String stepName, 
                               StepType stepType) {
        this.grpcProcessor = grpcProcessor;
        this.stepName = stepName;
        this.stepType = stepType;
    }

    @Override
    public PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException {
        try {
            ProcessResponse response = grpcProcessor.processStep(pipeStream, stepName);
            // Transform response back to PipeStream
            return transformResponseToPipeStream(pipeStream, response);
        } catch (Exception e) {
            throw new PipeStepExecutionException("Error executing gRPC step: " + stepName, e);
        }
    }

    @Override
    public String getStepName() {
        return stepName;
    }

    @Override
    public StepType getStepType() {
        return stepType;
    }

    private PipeStream transformResponseToPipeStream(PipeStream original, ProcessResponse response) {
        // Implementation to transform ProcessResponse back to PipeStream
        // This would update the document, add logs, etc.
        PipeStream.Builder builder = original.toBuilder();
        
        // Update the document if provided in the response
        if (response.hasOutputDoc()) {
            builder.setDocument(response.getOutputDoc());
        }
        
        // Add logs from the processor
        for (String log : response.getProcessorLogsList()) {
            // In a real implementation, we would add these logs to the history
            // For now, we'll just log them
            System.out.println("Processor log: " + log);
        }
        
        return builder.build();
    }
}