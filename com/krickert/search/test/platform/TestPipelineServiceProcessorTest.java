package com.krickert.search.test.platform;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.test.platform.proto.PipeDocExample;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TestPipelineServiceProcessor using the integrated platform with Consul config.
 */
@MicronautTest(environments = {"apicurio", "consul"}, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestPipelineServiceProcessorTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(TestPipelineServiceProcessorTest.class);

    // --- Constants ---
    private static final String HELLO_PIPELINES_KEY = "hello_pipelines";
    private static final String HELLO_PIPELINES_VALUE = "hello pipelines!";
    private static final String TEST_PIPELINE_NAME = "test-processor-pipeline";
    private static final String TEST_SERVICE_NAME = "test-processor-service";
    private static final String TEST_INPUT_TOPIC = "test-processor-input";
    private static final String TEST_OUTPUT_TOPIC = AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC;
    private static final String TEST_PROCESSOR_BEAN_NAME = "testPipelineServiceProcessor";

    // --- Test Lifecycle ---
    @BeforeEach
    @Override
    public void setUp() {
        LOG.info("Executing @BeforeEach in TestPipelineServiceProcessorTest");
        // DO NOT call loadConfigIntoConsul() directly - it's called by super.setUp()
        // Instead, ensure getConfigToLoad() returns the correct properties
        super.setUp();
        LOG.info("Finished @BeforeEach in TestPipelineServiceProcessorTest");
    }

    // --- Abstract Method Implementations ---
    @Override protected PipeStream getInput() { return PipeDocExample.createFullPipeStream(); }
    @Override protected PipeResponse getExpectedOutput() { return PipeResponse.newBuilder().setSuccess(true).build(); }
    @Override protected PipeDoc getExpectedPipeDoc() {
        PipeDoc originalDoc = getInput().getRequest().getDoc();
        Struct.Builder customDataBuilder = originalDoc.hasCustomData() ? originalDoc.getCustomData().toBuilder() : Struct.newBuilder();
        customDataBuilder.putFields(HELLO_PIPELINES_KEY, Value.newBuilder().setStringValue(HELLO_PIPELINES_VALUE).build());
        return originalDoc.toBuilder().setCustomData(customDataBuilder.build()).build();
    }
    @Override protected String getInputTopic() { return TEST_INPUT_TOPIC; }
    @Override protected String getOutputTopic() { return TEST_OUTPUT_TOPIC; }

    /** Defines the properties to load into Consul for this test. */
    @Override
    protected Properties getConfigToLoad() {
        Properties props = new Properties();
        // Keys are relative to the prefix defined in AbstractPipelineTest.CONSUL_CONFIG_PATH_PREFIX
        String keyPrefix = "pipeline.configs." + TEST_PIPELINE_NAME + ".service." + TEST_SERVICE_NAME;
        props.setProperty(keyPrefix + ".kafka-listen-topics", getInputTopic());
        props.setProperty(keyPrefix + ".kafka-publish-topics", getOutputTopic());
        props.setProperty(keyPrefix + ".service-implementation", TEST_PROCESSOR_BEAN_NAME);
        LOG.info("Configured test properties for Consul with {} entries", props.size());
        return props;
    }

    /** Provides only the necessary application-level properties for Micronaut. */
    @Override
    @NonNull
    public Map<String, String> getProperties() {
        // Start with ALL container properties provided by the base class
        Map<String, String> properties = new HashMap<>(super.getProperties());
        // Add ONLY properties needed to configure THIS application context
        properties.put("pipeline.service.name", TEST_SERVICE_NAME); // Crucial for InternalServiceConfig
        properties.put("kafka.consumer.dynamic.enabled", "true"); // Ensure dynamic consumer is enabled
        LOG.info("Providing Micronaut test properties for TestPipelineServiceProcessorTest: {}", properties.keySet());
        return properties;
    }

    // --- Test Methods --- (Unchanged)
    @Test
    public void testKafkaInput() throws Exception {
        LOG.info("Running TestPipelineServiceProcessorTest -> testKafkaInput");
        super.testKafkaToKafka(); // Executes the base test logic
        LOG.info("Base testKafkaToKafka completed. Performing specific assertions...");
        List<PipeStream> receivedMessages = testOutputConsumer.getReceivedMessages();
        assertFalse(receivedMessages.isEmpty(), "Output consumer should have received a message");
        PipeStream processedMessage = receivedMessages.get(receivedMessages.size() - 1);
        PipeDoc processedDoc = processedMessage.getRequest().getDoc();
        assertTrue(processedDoc.hasCustomData(), "Processed document should have custom data");
        Struct customData = processedDoc.getCustomData();
        assertTrue(customData.containsFields(HELLO_PIPELINES_KEY), "Custom data should contain the '" + HELLO_PIPELINES_KEY + "' field");
        Value helloValue = customData.getFieldsOrThrow(HELLO_PIPELINES_KEY);
        assertEquals(HELLO_PIPELINES_VALUE, helloValue.getStringValue(), "The value of '" + HELLO_PIPELINES_KEY + "' should be '" + HELLO_PIPELINES_VALUE + "'");
        LOG.info("TestPipelineServiceProcessorTest -> testKafkaInput completed successfully");
    }

    @Test
    public void testGrpcInput() throws Exception {
        LOG.info("Running TestPipelineServiceProcessorTest -> testGrpcInput (delegating to AbstractPipelineTest.testGrpcInputDirectProcessorCall)");
        super.testGrpcInputDirectProcessorCall();
        LOG.info("TestPipelineServiceProcessorTest -> testGrpcInput completed successfully");
    }
}
