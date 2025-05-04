package com.krickert.search.pipeline.service;

import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the PipelineServiceProcessor interface.
 * This implementation simply passes through the PipeStream without modification.
 * Real implementations would process the PipeStream in some way.
 */
@Singleton
@Named("testPipelineServiceProcessor")
public class TestPipelineServiceProcessor implements PipelineServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(TestPipelineServiceProcessor.class);

    @Override
    public PipeServiceDto process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with TestPipelineServiceProcessor");

        PipeServiceDto result = new PipeServiceDto();

        // Check for null input
        if (pipeStream == null) {
            log.error("Received null PipeStream");

            // Return an error response for null input
            ErrorData errorData = ErrorData.newBuilder()
                .setErrorMessage("Error processing PipeStream: Input was null")
                .build();

            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(false)
                .setErrorDate(errorData)
                .build();

            result.setResponse(response);
            return result;
        }

        try {
            // In a real implementation, this would do actual processing
            // For now, we just return a success response with no error data
            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(true)
                // Explicitly not setting error data for successful responses
                .build();

            // Set the response and the original PipeDoc from the request
            result.setResponse(response);
            //noinspection ConstantValue
            if (pipeStream.getRequest() != null) {
                result.setPipeDoc(pipeStream.getRequest().getDoc());
            }

            return result;
        } catch (Exception e) {
            log.error("Error processing PipeStream: {}", e.getMessage(), e);

            // Return an error response
            ErrorData errorData = ErrorData.newBuilder()
                .setErrorMessage("Error processing PipeStream: " + e.getMessage())
                .build();

            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(false)
                .setErrorDate(errorData)
                .build();

            result.setResponse(response);
            return result;
        }
    }
}
