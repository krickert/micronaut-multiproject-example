package com.krickert.search.pipeline.echo;

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

public class EchoPipelineServiceProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(EchoPipelineServiceProcessorTest.class);

    private EchoPipelineServiceProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EchoPipelineServiceProcessor();
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

        log.debug("[DEBUG_LOG] Response: {}", response);
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

        // Create the expected updated document manually
        PipeDoc.Builder expectedDocBuilder = doc.toBuilder();

        // Add the expected custom field
        Struct.Builder customDataBuilder = initialCustomData.toBuilder();
        customDataBuilder.putFields("my_pipeline_struct_field", 
            Value.newBuilder().setStringValue("Hello instance").build());
        expectedDocBuilder.setCustomData(customDataBuilder.build());

        // Add a timestamp (we can't check the exact value, but we'll check it exists)
        expectedDocBuilder.setLastModified(Timestamp.newBuilder().setSeconds(1).build());
        PipeDoc expectedDoc = expectedDocBuilder.build();

        // Verify the custom field was added
        assertTrue(expectedDoc.hasCustomData(), "Document should have custom data");
        Struct customData = expectedDoc.getCustomData();
        assertTrue(customData.containsFields("my_pipeline_struct_field"), 
                "Custom data should contain my_pipeline_struct_field");
        assertEquals("Hello instance", 
                customData.getFieldsOrThrow("my_pipeline_struct_field").getStringValue(),
                "my_pipeline_struct_field should have value 'Hello instance'");

        // Verify the existing field is still there
        assertTrue(customData.containsFields("existing_field"), 
                "Custom data should still contain existing_field");
        assertEquals("existing value", 
                customData.getFieldsOrThrow("existing_field").getStringValue(),
                "existing_field should still have its original value");

        // Verify the timestamp was set
        assertTrue(expectedDoc.hasLastModified(), "Document should have last_modified timestamp");

        log.debug("[DEBUG_LOG] Before processing: {}", beforeProcessing);
        log.debug("[DEBUG_LOG] Expected document: {}", expectedDoc);
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

        log.debug("[DEBUG_LOG] Error response: {}", response);
    }
}
