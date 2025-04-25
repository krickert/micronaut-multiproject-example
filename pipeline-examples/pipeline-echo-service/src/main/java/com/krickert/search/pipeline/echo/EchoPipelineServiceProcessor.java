package com.krickert.search.pipeline.echo;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Echo implementation of the PipelineServiceProcessor interface.
 * This implementation adds "Hello instance" to a struct field called "my_pipeline_struct_field"
 * and updates the "last_modified" timestamp of the PipeDoc to the current time.
 */
@Singleton
@Slf4j
public class EchoPipelineServiceProcessor implements PipelineServiceProcessor {

    @Override
    public PipeResponse process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with EchoPipelineServiceProcessor");

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

            // Add "Hello instance" to "my_pipeline_struct_field"
            customDataBuilder.putFields("my_pipeline_struct_field", 
                Value.newBuilder().setStringValue("Hello instance").build());

            // Set the updated custom_data back to the doc
            docBuilder.setCustomData(customDataBuilder.build());

            // Build the updated PipeDoc
            PipeDoc updatedDoc = docBuilder.build();

            // Create a new PipeStream with the updated doc
            PipeStream updatedStream = pipeStream.toBuilder()
                .setRequest(pipeStream.getRequest().toBuilder()
                    .setDoc(updatedDoc)
                    .build())
                .build();

            log.info("Successfully processed document with ID: {}", updatedDoc.getId());
            log.debug("Updated last_modified timestamp to: {}", 
                Instant.ofEpochSecond(currentTime.getSeconds(), currentTime.getNanos()));

            // Return a success response with the updated PipeDoc
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
