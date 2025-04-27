package com.krickert.search.test.platform;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.test.platform.proto.PipeDocExample;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for the TestPipelineServiceProcessor.
 * Tests both gRPC and Kafka input methods.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestPipelineServiceProcessorTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(TestPipelineServiceProcessorTest.class);
    private static final String HELLO_PIPELINES_KEY = "hello_pipelines";
    private static final String HELLO_PIPELINES_VALUE = "hello pipelines!";

    /**
     * Get the input PipeStream for testing.
     * 
     * @return the input PipeStream
     */
    protected PipeStream getInput() {
        // Use the example PipeStream from PipeDocExample
        return PipeDocExample.createFullPipeStream();
    }

    /**
     * Get the expected output PipeResponse after processing.
     * 
     * @return the expected PipeResponse
     */
    protected PipeResponse getExpectedOutput() {
        // We expect a success response
        return PipeResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    /**
     * Get the expected output PipeDoc after processing.
     * 
     * @return the expected PipeDoc
     */
    protected PipeDoc getExpectedPipeDoc() {
        // Get the original PipeDoc from the input
        PipeDoc originalDoc = getInput().getRequest().getDoc();

        // Create a new custom data with the "hello pipelines!" field
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
        return originalDoc.toBuilder()
            .setCustomData(customDataBuilder.build())
            .build();
    }

    /**
     * Override testGrpcInput to add specific assertions for this test case.
     */
    @Override
    @Test
    public void testGrpcInput() {
        // Call the parent test method
        super.testGrpcInput();

        // Get the input and process it again for additional assertions
        PipeStream input = getInput();
        PipeDoc processedDoc = pipelineServiceProcessor.process(input).getPipeDoc();

        // Verify that the custom data contains "hello pipelines!"
        assertTrue(processedDoc.hasCustomData(), "Processed document should have custom data");
        Struct customData = processedDoc.getCustomData();
        assertTrue(customData.containsFields(HELLO_PIPELINES_KEY), 
                "Custom data should contain the 'hello_pipelines' field");
        Value helloValue = customData.getFieldsOrThrow(HELLO_PIPELINES_KEY);
        assertEquals(HELLO_PIPELINES_VALUE, helloValue.getStringValue(), 
                "The value of 'hello_pipelines' should be 'hello pipelines!'");
    }

    /**
     * Override testKafkaInput to add specific assertions for this test case.
     */
    @Override
    @Test
    public void testKafkaInput() throws Exception {
        // Call the parent test method
        super.testKafkaInput();

        // Get the processed message for additional assertions
        PipeStream processedMessage = consumer.getReceivedMessages().get(0);
        PipeDoc processedDoc = processedMessage.getRequest().getDoc();

        // Verify that the custom data contains "hello pipelines!"
        assertTrue(processedDoc.hasCustomData(), "Processed document should have custom data");
        Struct customData = processedDoc.getCustomData();
        assertTrue(customData.containsFields(HELLO_PIPELINES_KEY), 
                "Custom data should contain the 'hello_pipelines' field");
        Value helloValue = customData.getFieldsOrThrow(HELLO_PIPELINES_KEY);
        assertEquals(HELLO_PIPELINES_VALUE, helloValue.getStringValue(), 
                "The value of 'hello_pipelines' should be 'hello pipelines!'");
    }

}
