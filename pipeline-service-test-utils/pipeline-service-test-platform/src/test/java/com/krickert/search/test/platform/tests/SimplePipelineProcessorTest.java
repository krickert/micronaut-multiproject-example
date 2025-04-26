package com.krickert.search.test.platform.tests;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.PipelineConfigManager;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import com.krickert.search.test.consul.ConsulContainer;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.env.Environment;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test implementation of PipelineProcessorTest that uses SimplePipelineProcessor.
 * This test demonstrates how to create a concrete implementation of PipelineProcessorTest.
 */
@MicronautTest(environments = {"test"})
public class SimplePipelineProcessorTest extends PipelineProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(SimplePipelineProcessorTest.class);
    private static final String TEST_FIELD_NAME = "test_field";
    private static final String TEST_FIELD_VALUE = "test_value";

    @Inject
    private SimplePipelineProcessor processor;


    @Override
    protected PipelineServiceProcessor getProcessor() {
        return processor;
    }

    @Override
    protected PipeStream expectedResult() {
        // Create a test document with the expected custom field
        Struct customData = Struct.newBuilder()
                .putFields(TEST_FIELD_NAME, Value.newBuilder().setStringValue(TEST_FIELD_VALUE).build())
                .build();

        PipeDoc doc = getTestDocument().toBuilder()
                .setCustomData(customData)
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                .build();

        return PipeStream.newBuilder()
                .setRequest(request)
                .build();
    }

    @Override
    protected void assertResult(PipeStream actual, PipeStream expected) {
        // Call the parent implementation first
        super.assertResult(actual, expected);

        // Additional assertions specific to this test
        PipeDoc actualDoc = actual.getRequest().getDoc();
        PipeDoc expectedDoc = expected.getRequest().getDoc();

        // Verify the custom field was added
        assertTrue(actualDoc.hasCustomData(), "Document should have custom data");
        Struct customData = actualDoc.getCustomData();
        assertTrue(customData.containsFields(TEST_FIELD_NAME), 
                "Custom data should contain " + TEST_FIELD_NAME);
        assertEquals(TEST_FIELD_VALUE, 
                customData.getFieldsOrThrow(TEST_FIELD_NAME).getStringValue(),
                TEST_FIELD_NAME + " should have value '" + TEST_FIELD_VALUE + "'");

        // Verify the document has a last_modified timestamp
        assertTrue(actualDoc.hasLastModified(), "Document should have last_modified timestamp");

        log.debug("Test passed with actual result: {}", actual);
    }

    /**
     * Additional test to verify the processor handles null input correctly.
     */
    @Test
    void testProcessorWithNullInput() {
        // Process a null PipeStream
        var response = processor.process(null);

        // Verify the response
        assertEquals(false, response.getSuccess(), "Response should not be successful");
        assertTrue(response.hasErrorDate(), "Response should have error data");
        assertTrue(response.getErrorDate().getErrorMessage().contains("Input was null"), 
                "Error message should mention null input");

        log.debug("Null input test passed with response: {}", response);
    }
}
