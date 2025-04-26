package com.krickert.search.pipeline.echo;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.PipelineConfigManager;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.test.consul.ConsulContainer;
// Removed Kafka imports to use real objects without Kafka
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test"}, propertySources = "classpath:application-test.properties")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulEchoPipelineServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ConsulEchoPipelineServiceTest.class);

    @Inject
    private EchoPipelineServiceProcessor processor;

    @Inject
    private PipelineConfigManager pipelineConfigManager;

    @Inject
    private ConsulContainer consulContainer;

    @Inject
    private Environment environment;

    @BeforeEach
    void setUp() {
        // Ensure Consul container is running
        assertTrue(consulContainer.isRunning(), "Consul container must be running for this test");

        // Clear any existing pipeline configurations
        pipelineConfigManager.setPipelines(new HashMap<>());

        // Create a pipeline configuration and add it to the manager
        PipelineConfig echoPipeline = new PipelineConfig("echo-pipeline");
        Map<String, ServiceConfiguration> services = new HashMap<>();

        // Create echo service
        ServiceConfiguration echoService = new ServiceConfiguration("echo-service");
        echoService.setKafkaListenTopics(List.of("echo-input"));
        echoService.setKafkaPublishTopics(List.of("echo-output"));
        services.put("echo-service", echoService);

        // Set services on pipeline
        echoPipeline.setService(services);

        // Add pipeline to manager
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("echo-pipeline", echoPipeline);
        pipelineConfigManager.setPipelines(pipelines);

        // Add properties to the environment with the "pipeline.configs" prefix
        environment.addPropertySource(PropertySource.of("test", Map.of(
            "pipeline.configs.echo-pipeline.service.echo-service.kafka-listen-topics", "echo-input",
            "pipeline.configs.echo-pipeline.service.echo-service.kafka-publish-topics", "echo-output"
        )));
    }

    @Test
    void testConsulContainerIsRunning() {
        assertTrue(consulContainer.isRunning(), "Consul container should be running");
    }

    @Test
    void testEchoServiceWithConsul() {
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

        // Log the PipeStream before processing
        log.debug("[DEBUG_LOG] PipeStream before processing: {}", pipeStream);

        // Process the PipeStream - now returns PipeServiceDto
        PipeServiceDto serviceDto = processor.process(pipeStream);
        PipeResponse response = serviceDto.getResponse();
        PipeDoc processedDoc = serviceDto.getPipeDoc();

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

        System.out.println("[DEBUG_LOG] Expected custom data: " + expectedCustomData);

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

        // Verify the timestamp was updated
        assertTrue(processedDoc.hasLastModified(), "Document should have last_modified timestamp");
        Timestamp lastModified = processedDoc.getLastModified();
        Instant modifiedTime = Instant.ofEpochSecond(lastModified.getSeconds(), lastModified.getNanos());

        // The modified time should be after our "before" time
        assertFalse(modifiedTime.isBefore(beforeProcessing),
                "Last modified timestamp should be after the time before processing");

        log.debug("[DEBUG_LOG] Before processing: {}", beforeProcessing);
        log.debug("[DEBUG_LOG] Modified time: {}", modifiedTime);
        log.debug("[DEBUG_LOG] Processed document: {}", processedDoc);

        // Verify that the pipeline configuration exists in the manager
        Map<String, PipelineConfig> pipelines = pipelineConfigManager.getPipelines();
        assertNotNull(pipelines, "Pipelines map should not be null");
        assertTrue(pipelines.containsKey("echo-pipeline"), "Pipelines map should contain echo-pipeline");

        PipelineConfig echoPipeline = pipelines.get("echo-pipeline");
        assertNotNull(echoPipeline, "Echo pipeline should not be null");

        Map<String, ServiceConfiguration> services = echoPipeline.getService();
        assertNotNull(services, "Services map should not be null");
        assertTrue(services.containsKey("echo-service"), "Services map should contain echo-service");

        ServiceConfiguration echoService = services.get("echo-service");
        assertNotNull(echoService, "Echo service should not be null");
        assertEquals("echo-service", echoService.getName(), "Service name should be echo-service");

        List<String> listenTopics = echoService.getKafkaListenTopics();
        assertNotNull(listenTopics, "Kafka listen topics should not be null");
        assertEquals(1, listenTopics.size(), "Should have 1 kafka listen topic");
        assertEquals("echo-input", listenTopics.get(0), "Kafka listen topic should be echo-input");

        List<String> publishTopics = echoService.getKafkaPublishTopics();
        assertNotNull(publishTopics, "Kafka publish topics should not be null");
        assertEquals(1, publishTopics.size(), "Should have 1 kafka publish topic");
        assertEquals("echo-output", publishTopics.get(0), "Kafka publish topic should be echo-output");
    }
}
