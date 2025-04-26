package com.krickert.search.test.platform.tests;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Simple implementation of the PipelineServiceProcessor interface for testing.
 * This implementation adds a test field to the PipeDoc's custom data and updates the last_modified timestamp.
 */
@Singleton
public class SimplePipelineProcessor implements PipelineServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimplePipelineProcessor.class);
    private static final String TEST_FIELD_NAME = "test_field";
    private static final String TEST_FIELD_VALUE = "test_value";

    @Override
    public PipeResponse process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with SimplePipelineProcessor");

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
            // Get the PipeDoc from the request
            PipeDoc originalDoc = pipeStream.getRequest().getDoc();

            // Create a builder from the original doc
            PipeDoc.Builder docBuilder = originalDoc.toBuilder();

            // Update the last_modified timestamp to current time
            Timestamp currentTime = getCurrentTimestamp();
            docBuilder.setLastModified(currentTime);

            // Get or create the custom_data Struct
            Struct.Builder customDataBuilder;
            if (originalDoc.hasCustomData()) {
                customDataBuilder = originalDoc.getCustomData().toBuilder();
            } else {
                customDataBuilder = Struct.newBuilder();
            }

            // Add test field to custom data
            customDataBuilder.putFields(TEST_FIELD_NAME, 
                Value.newBuilder().setStringValue(TEST_FIELD_VALUE).build());

            // Set the updated custom_data back to the doc
            docBuilder.setCustomData(customDataBuilder.build());

            // Build the updated PipeDoc
            PipeDoc updatedDoc = docBuilder.build();

            // Update the original PipeStream with the updated doc
            pipeStream = pipeStream.toBuilder()
                .setRequest(pipeStream.getRequest().toBuilder()
                    .setDoc(updatedDoc)
                    .build())
                .build();

            log.info("Successfully processed document with ID: {}", updatedDoc.getId());
            log.debug("Updated last_modified timestamp to: {}", 
                Instant.ofEpochSecond(currentTime.getSeconds(), currentTime.getNanos()));

            // Return a success response
            return PipeResponse.newBuilder()
                .setSuccess(true)
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

    /**
     * Get the current timestamp as a Protobuf Timestamp
     * 
     * @return Current time as Protobuf Timestamp
     */
    private Timestamp getCurrentTimestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build();
    }
}
