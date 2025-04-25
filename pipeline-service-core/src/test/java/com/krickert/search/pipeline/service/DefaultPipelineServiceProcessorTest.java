package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class DefaultPipelineServiceProcessorTest {

    @Inject
    DefaultPipelineServiceProcessor processor;

    @Test
    void testProcessSuccess() {
        // Create a test PipeStream
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-id")
                .setTitle("Test Document")
                .setBody("This is a test document body")
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                .build();

        PipeStream pipeStream = PipeStream.newBuilder()
                .setRequest(request)
                .build();

        // Process the PipeStream
        PipeResponse response = processor.process(pipeStream);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should be successful");
        assertFalse(response.hasErrorDate(), "Error data should not be present for successful response");

        System.out.println("[DEBUG_LOG] Response: " + response);
    }

    @Test
    void testProcessWithException() {
        // Create a null PipeStream to force an exception
        PipeStream nullStream = null;

        // Process the null PipeStream (should throw an exception internally)
        PipeResponse response = processor.process(nullStream);

        // Verify the response
        assertFalse(response.getSuccess(), "Response should not be successful");
        assertNotNull(response.getErrorDate(), "Error data should not be null for failed response");
        assertTrue(response.getErrorDate().getErrorMessage().contains("Error processing PipeStream"), 
                "Error message should contain the expected text");

        System.out.println("[DEBUG_LOG] Error response: " + response);
    }
}
