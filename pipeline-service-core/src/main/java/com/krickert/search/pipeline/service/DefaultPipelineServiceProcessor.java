package com.krickert.search.pipeline.service;

import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Default implementation of the PipelineServiceProcessor interface.
 * This implementation simply passes through the PipeStream without modification.
 * Real implementations would process the PipeStream in some way.
 */
@Singleton
@Slf4j
public class DefaultPipelineServiceProcessor implements PipelineServiceProcessor {

    @Override
    public PipeResponse process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with DefaultPipelineServiceProcessor");

        // Check for null input
        if (pipeStream == null) {
            log.error("Received null PipeStream");

            // Return an error response for null input
            ErrorData errorData = ErrorData.newBuilder()
                .setErrorMessage("Error processing PipeStream: Input was null")
                .build();

            return PipeResponse.newBuilder()
                .setSuccess(false)
                .setErrorDate(errorData)
                .build();
        }

        try {
            // In a real implementation, this would do actual processing
            // For now, we just return a success response with no error data
            return PipeResponse.newBuilder()
                .setSuccess(true)
                // Explicitly not setting error data for successful responses
                .build();
        } catch (Exception e) {
            log.error("Error processing PipeStream: {}", e.getMessage(), e);

            // Return an error response
            ErrorData errorData = ErrorData.newBuilder()
                .setErrorMessage("Error processing PipeStream: " + e.getMessage())
                .build();

            return PipeResponse.newBuilder()
                .setSuccess(false)
                .setErrorDate(errorData)
                .build();
        }
    }
}
