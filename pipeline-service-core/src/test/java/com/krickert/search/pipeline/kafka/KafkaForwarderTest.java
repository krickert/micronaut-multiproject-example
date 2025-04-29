package com.krickert.search.pipeline.kafka;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
// Import the simple test processor
import com.krickert.search.test.platform.AbstractPipelineTest;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces; // Import Replaces
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Named; // Import Named
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for verifying Kafka forwarding within the pipeline framework.
 * This test ensures that a message sent to an input topic is processed
 * and forwarded correctly to the designated output topic, using the
 * refactored AbstractPipelineTest structure.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaForwarderTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(KafkaForwarderTest.class);

    // --- Configuration Constants for this Test ---
    private static final String TEST_PIPELINE_NAME = "forwarder-test-pipeline";
    private static final String TEST_SERVICE_NAME = "test-forwarder-service";
    private static final String TEST_INPUT_TOPIC = "test-forwarder-input";
    // Output topic MUST match the one defined in AbstractPipelineTest for the consumer
    private static final String TEST_OUTPUT_TOPIC = AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC;
    // The bean name for the processor implementation used in this test
    private static final String TEST_PROCESSOR_BEAN_NAME = "testPipelineServiceProcessor";


    // Store the document ID to ensure consistency
    private final String testDocId = UUID.randomUUID().toString();

    // Inject KafkaForwarder ONLY if you need to test its methods *directly*
    // For testing the pipeline flow, rely on the framework sending messages.
    // @Inject
    // private KafkaForwarder kafkaForwarder; // Not needed for framework tests

    /**
     * Factory to provide the TestPipelineServiceProcessor bean for this test context.
     * It replaces any other PipelineServiceProcessor bean that might be present.
     */
    @Factory
    static class TestConfigurationFactory {
        @Singleton
        @Named(TEST_PROCESSOR_BEAN_NAME) // Provide the bean with the name expected by the base class
        @Replaces(PipelineServiceProcessor.class) // Indicate this replaces the default processor
        public PipelineServiceProcessor testProcessor() {
            // Use the simple pass-through processor for this forwarding test
            return new TestPipelineServiceProcessor();
        }
    }

    @BeforeEach
    @Override // Ensure overriding the base class setup
    public void setUp() {
        // Call parent setUp method (starts containers, creates topics, resets consumer)
        super.setUp();
        log.info("KafkaForwarderTest setup complete.");
        // No need to reset a custom listener, as it's removed.
    }

    // --- Abstract Method Implementations ---

    @Override
    protected PipeStream getInput() {
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(testDocId)
                .setTitle("Kafka Forwarder Test Doc")
                .setBody("Content to be forwarded via Kafka.")
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                // Add destinations if the processor logic depends on them
                // .addDestinations(Route.newBuilder().setRouteType(RouteType.KAFKA).setDestination(TEST_OUTPUT_TOPIC).build())
                .build();

        return PipeStream.newBuilder()
                .setRequest(request)
                .build();
    }

    @Override
    protected PipeResponse getExpectedOutput() {
        // The TestPipelineServiceProcessor returns a simple success response.
        return PipeResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    @Override
    protected PipeDoc getExpectedPipeDoc() {
        // TestPipelineServiceProcessor adds a 'hello_pipelines' field to custom_data.
        PipeDoc originalDoc = getInput().getRequest().getDoc();
        Struct.Builder customDataBuilder = originalDoc.hasCustomData()
                ? originalDoc.getCustomData().toBuilder()
                : Struct.newBuilder();
        customDataBuilder.putFields("hello_pipelines", Value.newBuilder().setStringValue("hello pipelines!").build());

        return originalDoc.toBuilder()
                .setCustomData(customDataBuilder)
                // Optionally update last_modified if the processor does that
                // .setLastModified(...)
                .build();
    }

    @Override
    protected String getOutputTopic() {
        // Return the fixed output topic name that the base consumer listens to.
        return TEST_OUTPUT_TOPIC;
    }

    /**
     * Provides the necessary configuration properties for this specific test.
     * Configures a simple pipeline where the 'test-forwarder-service'
     * listens on TEST_INPUT_TOPIC and publishes to TEST_OUTPUT_TOPIC.
     */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties()); // Get Kafka/Apicurio props

        // --- Core Test Configuration ---
        properties.put("pipeline.service.name", TEST_SERVICE_NAME); // Identify the service instance
        properties.put("kafka.consumer.dynamic.enabled", "true");   // Enable dynamic consumers

        // --- Define the Pipeline for Dynamic Consumer Manager ---
        String pipelinePrefix = "pipeline.configs." + TEST_PIPELINE_NAME + ".service." + TEST_SERVICE_NAME;
        properties.put(pipelinePrefix + ".kafka-listen-topics", TEST_INPUT_TOPIC);
        properties.put(pipelinePrefix + ".kafka-publish-topics", TEST_OUTPUT_TOPIC); // MUST match getOutputTopic()
        properties.put(pipelinePrefix + ".service-implementation", TEST_PROCESSOR_BEAN_NAME); // Specify the processor bean
        // properties.put(pipelinePrefix + ".grpc-forward-to", "null"); // Define if needed

        log.info("Providing test properties: {}", properties);
        return properties;
    }

    /**
     * Test the Kafka Input -> Kafka Output scenario using the base class implementation.
     * The base class's testKafkaToKafka will:
     * 1. Send getInput() to TEST_INPUT_TOPIC using the producer.
     * 2. Wait for the application's DynamicKafkaConsumerManager to consume the message.
     * 3. The message will be processed by the injected TestPipelineServiceProcessor.
     * 4. The result will be forwarded (based on config) to TEST_OUTPUT_TOPIC.
     * 5. testOutputConsumer (listening on TEST_OUTPUT_TOPIC) receives the message.
     * 6. Assertions in the base class verify the received message.
     */
    @Test
    public void testKafkaInput() throws Exception {
        log.info("Running KafkaForwarderTest -> testKafkaInput (delegating to AbstractPipelineTest.testKafkaToKafka)");
        // Calls the base class test which now handles the end-to-end Kafka flow
        super.testKafkaToKafka();
        // Add specific assertions here ONLY if needed beyond base class checks.
        log.info("KafkaForwarderTest -> testKafkaInput completed successfully");
    }

    /**
     * Test the direct gRPC processor call functionality using the base class implementation.
     * NOTE: This method is NOT overriding a base class method with the same name
     * after the refactoring of AbstractPipelineTest. It delegates to the base class's
     * testGrpcInputDirectProcessorCall for the actual test logic.
     */
    @Test
    public void testGrpcInput() throws Exception {
        log.info("Running KafkaForwarderTest -> testGrpcInput (delegating to AbstractPipelineTest.testGrpcInputDirectProcessorCall)");
        // Calls the base class test for direct processor invocation
        super.testGrpcInputDirectProcessorCall();
        // Add specific assertions here ONLY if needed beyond base class checks.
        log.info("KafkaForwarderTest -> testGrpcInput completed successfully");
    }

}