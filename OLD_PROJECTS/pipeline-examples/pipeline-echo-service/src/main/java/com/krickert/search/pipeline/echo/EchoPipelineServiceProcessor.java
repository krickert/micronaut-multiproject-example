package com.krickert.search.pipeline.echo;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipeServiceDto;
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
    public PipeServiceDto process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with EchoPipelineServiceProcessor");
        PipeServiceDto serviceDto = new PipeServiceDto();

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

            log.info("Successfully processed document with ID: {}", updatedDoc.getId());
            log.debug("Updated last_modified timestamp to: {}", 
                Instant.ofEpochSecond(currentTime.getSeconds(), currentTime.getNanos()));

            // Create a success response
            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(true)
                .build();

            // Set both the response and updated doc in the DTO
            serviceDto.setResponse(response);
            serviceDto.setPipeDoc(updatedDoc);

            return serviceDto;
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

            serviceDto.setResponse(response);
            return serviceDto;
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
