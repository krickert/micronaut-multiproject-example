package com.krickert.search.pipeline.echo;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.test.kafka.AbstractKafkaTest;
import com.krickert.search.test.kafka.AbstractPipelineServiceProcessorTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class EchoPipelineServiceProcessorTest extends AbstractKafkaTest {

    private static final Logger log = LoggerFactory.getLogger(EchoPipelineServiceProcessorTest.class);

    @Inject
    private EchoPipelineServiceProcessor processor;

    @Test
    void testEchoServiceProcessor() {
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

        // Print the PipeStream before processing
        System.out.println("[DEBUG_LOG] PipeStream before processing: " + pipeStream);

        // Process the PipeStream - now returns PipeServiceDto
        PipeServiceDto serviceDto = processor.process(pipeStream);
        PipeResponse response = serviceDto.getResponse();
        PipeDoc processedDoc = serviceDto.getPipeDoc();

        // Print the response and processed document
        System.out.println("[DEBUG_LOG] Response: " + response);
        System.out.println("[DEBUG_LOG] Processed document: " + processedDoc);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should be successful");

        // Verify the processed document is not null
        assertNotNull(processedDoc, "Processed document should not be null");

        // Create the expected document manually for verification
        Struct expectedCustomData = Struct.newBuilder()
                .putFields("existing_field", Value.newBuilder().setStringValue("existing value").build())
                .putFields("my_pipeline_struct_field", Value.newBuilder().setStringValue("Hello instance").build())
                .build();

        // Print the expected custom data
        System.out.println("[DEBUG_LOG] Expected custom data: " + expectedCustomData);

        // Print the processed document and its custom data
        System.out.println("[DEBUG_LOG] Processed document: " + processedDoc);
        if (processedDoc.hasCustomData()) {
            System.out.println("[DEBUG_LOG] Custom data: " + processedDoc.getCustomData());
            System.out.println("[DEBUG_LOG] Custom data fields: " + processedDoc.getCustomData().getFieldsMap().keySet());
        } else {
            System.out.println("[DEBUG_LOG] No custom data in processed document");
        }

        // Verify the custom field was added to the processed document
        assertTrue(processedDoc.hasCustomData(), "Document should have custom data");
        Struct customData = processedDoc.getCustomData();
        assertTrue(customData.containsFields("my_pipeline_struct_field"),
                "Custom data should contain my_pipeline_struct_field");
        assertEquals("Hello instance",
                customData.getFieldsOrThrow("my_pipeline_struct_field").getStringValue(),
                "my_pipeline_struct_field should have value 'Hello instance'");

        // Verify the original field is still there in the processed document
        assertTrue(customData.containsFields("existing_field"),
                "Custom data should still contain existing_field");
        assertEquals("existing value",
                customData.getFieldsOrThrow("existing_field").getStringValue(),
                "existing_field should have value 'existing value'");
    }
}
