package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@MicronautTest
public class TestPipelineServiceProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(TestPipelineServiceProcessorTest.class);

    @Inject
    TestPipelineServiceProcessor processor;

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
        PipeServiceDto result = processor.process(pipeStream);

        // Extract the response from the result
        PipeResponse response = result.getResponse();

        // Verify the response
        assertTrue(response.getSuccess(), "Response should be successful");
        assertFalse(response.hasErrorDate(), "Error data should not be present for successful response");

        // Verify the PipeDoc was set correctly
        assertNotNull(result.getPipeDoc(), "PipeDoc should not be null");
        assertEquals("test-id", result.getPipeDoc().getId(), "PipeDoc ID should match");

        System.out.println("[DEBUG_LOG] Response: " + response);
    }

    @Test
    void testProcessWithException() {
        // Create a null PipeStream to force an exception
        PipeStream nullStream = null;

        // Process the null PipeStream (should throw an exception internally)
        PipeServiceDto result = processor.process(nullStream);

        // Extract the response from the result
        PipeResponse response = result.getResponse();

        // Verify the response
        assertFalse(response.getSuccess(), "Response should not be successful");
        assertNotNull(response.getErrorDate(), "Error data should not be null for failed response");
        assertTrue(response.getErrorDate().getErrorMessage().contains("Error processing PipeStream"), 
                "Error message should contain the expected text");

        // Verify the PipeDoc is null for error cases
        assertNull(result.getPipeDoc(), "PipeDoc should be null for error cases");

        log.debug("[DEBUG_LOG] Error response: [{}]", response);
    }
}
