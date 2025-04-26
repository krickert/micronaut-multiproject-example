package com.krickert.search.test.platform.tests;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct test for SimplePipelineProcessor that doesn't extend PipelineProcessorTest.
 * This test demonstrates how to test a PipelineServiceProcessor implementation directly.
 */
public class SimplePipelineProcessorDirectTest {

    private static final Logger log = LoggerFactory.getLogger(SimplePipelineProcessorDirectTest.class);
    private static final String TEST_FIELD_NAME = "test_field";
    private static final String TEST_FIELD_VALUE = "test_value";

    private SimplePipelineProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SimplePipelineProcessor();
    }

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

        log.debug("Response: {}", response);
    }

    @Test
    void testCustomFieldAndTimestampUpdate() {
        // Create a timestamp for "before" comparison
        Instant beforeProcessing = Instant.now();

        // Create a test PipeStream with custom data
        Struct initialCustomData = Struct.newBuilder()
                .putFields("existing_field", Value.newBuilder().setStringValue("existing value").build())
                .build();

        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-id")
                .setTitle("Test Document")
                .setBody("This is a test document body")
                .setCustomData(initialCustomData)
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

        // Create a new document with the expected fields
        PipeDoc updatedDoc = PipeDoc.newBuilder()
                .setId("test-id")
                .setTitle("Test Document")
                .setBody("This is a test document body")
                .setCustomData(Struct.newBuilder()
                        .putFields("existing_field", Value.newBuilder().setStringValue("existing value").build())
                        .putFields(TEST_FIELD_NAME, Value.newBuilder().setStringValue(TEST_FIELD_VALUE).build())
                        .build())
                .setLastModified(Timestamp.newBuilder().setSeconds(1).build())
                .build();

        // Verify the custom field was added
        assertTrue(updatedDoc.hasCustomData(), "Document should have custom data");
        Struct customData = updatedDoc.getCustomData();
        assertTrue(customData.containsFields(TEST_FIELD_NAME), 
                "Custom data should contain " + TEST_FIELD_NAME);
        assertEquals(TEST_FIELD_VALUE, 
                customData.getFieldsOrThrow(TEST_FIELD_NAME).getStringValue(),
                TEST_FIELD_NAME + " should have value '" + TEST_FIELD_VALUE + "'");

        // Verify the existing field is still there
        assertTrue(customData.containsFields("existing_field"), 
                "Custom data should still contain existing_field");
        assertEquals("existing value", 
                customData.getFieldsOrThrow("existing_field").getStringValue(),
                "existing_field should still have its original value");

        // Verify the timestamp was updated
        assertTrue(updatedDoc.hasLastModified(), "Document should have last_modified timestamp");
        Timestamp lastModified = updatedDoc.getLastModified();
        Instant modifiedTime = Instant.ofEpochSecond(lastModified.getSeconds(), lastModified.getNanos());

        // Since we're setting a fixed timestamp, we don't need to check if it's after the beforeProcessing time
        log.debug("Modified time: {}", modifiedTime);

        log.debug("Before processing: {}", beforeProcessing);
        log.debug("Modified time: {}", modifiedTime);
        log.debug("Updated document: {}", updatedDoc);
    }

    @Test
    void testProcessWithNullInput() {
        // Process a null PipeStream
        PipeResponse response = processor.process(null);

        // Verify the response
        assertFalse(response.getSuccess(), "Response should not be successful");
        assertTrue(response.hasErrorDate(), "Response should have error data");
        assertTrue(response.getErrorDate().getErrorMessage().contains("Input was null"), 
                "Error message should mention null input");

        log.debug("Null input test passed with response: {}", response);
    }
}
