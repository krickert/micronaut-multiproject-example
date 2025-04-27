package com.krickert.search.test.platform;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test implementation of the PipelineServiceProcessor interface.
 * This implementation adds "hello pipelines!" to the custom data of the PipeDoc.
 */
@Singleton
public class TestPipelineServiceProcessor implements PipelineServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(TestPipelineServiceProcessor.class);
    private static final String HELLO_PIPELINES_KEY = "hello_pipelines";
    private static final String HELLO_PIPELINES_VALUE = "hello pipelines!";

    @Override
    public PipeServiceDto process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with TestPipelineServiceProcessor");

        PipeServiceDto result = new PipeServiceDto();

        // Check for null input
        if (pipeStream == null) {
            log.error("Received null PipeStream");

            // Return an error response for null input
            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(false)
                .build();

            result.setResponse(response);
            return result;
        }

        try {
            // Create a success response
            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(true)
                .build();

            result.setResponse(response);

            // Get the original PipeDoc from the request
            if (pipeStream.getRequest() != null && pipeStream.getRequest().hasDoc()) {
                PipeDoc originalDoc = pipeStream.getRequest().getDoc();

                // Add "hello pipelines!" to the custom data
                Struct.Builder customDataBuilder;

                if (originalDoc.hasCustomData()) {
                    // Start with existing custom data
                    customDataBuilder = originalDoc.getCustomData().toBuilder();
                } else {
                    // Create new custom data if none exists
                    customDataBuilder = Struct.newBuilder();
                }

                // Add our custom field
                customDataBuilder.putFields(
                    HELLO_PIPELINES_KEY, 
                    Value.newBuilder().setStringValue(HELLO_PIPELINES_VALUE).build()
                );

                // Create a new PipeDoc with the updated custom data
                PipeDoc modifiedDoc = originalDoc.toBuilder()
                    .setCustomData(customDataBuilder.build())
                    .build();

                result.setPipeDoc(modifiedDoc);
            } else {
                log.warn("PipeStream request or doc is null, cannot modify custom data");
                // If there's no doc, just return the original
                if (pipeStream.getRequest() != null) {
                    result.setPipeDoc(pipeStream.getRequest().getDoc());
                }
            }

            return result;
        } catch (Exception e) {
            log.error("Error processing PipeStream: {}", e.getMessage(), e);

            // Return an error response
            PipeResponse response = PipeResponse.newBuilder()
                .setSuccess(false)
                .build();

            result.setResponse(response);
            return result;
        }
    }
}
