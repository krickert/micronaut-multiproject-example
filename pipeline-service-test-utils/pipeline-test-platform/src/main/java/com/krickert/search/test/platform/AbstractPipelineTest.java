
package com.krickert.search.test.platform;

// Core model/pipeline classes
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.InternalServiceConfig;
import com.krickert.search.pipeline.config.PipelineConfigService;
import com.krickert.search.pipeline.kafka.KafkaForwarderClient;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;

// Test framework classes (Helpers and Central Manager)
import com.krickert.search.test.platform.consul.ConsulTestHelper; // Use the helper
import com.krickert.search.test.platform.kafka.TestContainerManager; // Central property store

// Micronaut / Testing / JUnit / Logging / Utilities
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.NewTopic; // For creating topics
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration; // For AdminClient timeout
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors; // For topic creation/deletion

import static com.krickert.search.model.ProtobufUtils.createKey;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for pipeline tests. Integrates Kafka, Apicurio, and Consul
 * via the central TestContainerManager. Provides properties via TestContainerManager
 * by implementing TestPropertyProvider. Uses injected helpers for interactions.
 */
@MicronautTest(environments = {"apicurio", "consul"}, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractPipelineTest.class);

    // --- Constants ---
    // Ensure this topic is consistently used by the test output consumer
    protected static final String PIPELINE_TEST_OUTPUT_TOPIC = "pipeline-test-output-topic";
    // Default Consul path prefix (can be overridden if needed)
    protected static final String CONSUL_CONFIG_PATH_PREFIX = "config/"; // Adjusted default

    // --- Central Container Manager ---
    // Get the singleton instance - this triggers initialization and container startup if needed.
    protected static final TestContainerManager containerManager = TestContainerManager.getInstance();

    // --- Injected Helpers & Clients ---
    @Inject protected KafkaAdminClient kafkaAdminClient; // For topic management
    @Inject protected ConsulTestHelper consulTestHelper; // For KV store interaction

    // --- Injected Application Beans ---
    @Inject protected InternalServiceConfig internalServiceConfig;
    @Inject protected PipelineConfigService pipelineConfigService;
    // Assuming a specific named processor is used in tests
    @Inject @Named("testPipelineServiceProcessor") protected PipelineServiceProcessor pipelineServiceProcessor;
    @Inject protected KafkaForwarderClient producer;
    @Inject protected PipeStreamOutputConsumer testOutputConsumer; // The Kafka listener bean

    // Default Kafka topic settings (can be overridden by specific tests if necessary)
    private static final int DEFAULT_PARTITIONS = 1;
    private static final short DEFAULT_REPLICATION_FACTOR = 1;
    // Timeout for admin operations
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(15);

    @BeforeAll
    public static void startAllServices() {
        LOG.info("Ensuring external services (Kafka, Apicurio, Consul) are started via TestContainerManager...");
        // Trigger initialization and wait briefly if necessary (manager handles actual start)
        TestContainerManager manager = TestContainerManager.getInstance();
        assertNotNull(manager, "TestContainerManager instance should not be null");

        // Verify essential containers are running after initialization attempt
        // Define the essential services for pipeline tests
        Set<String> essentialServices = Set.of("kafka", "apicurio", "consul");
        assertTrue(manager.areEssentialContainersRunning(essentialServices.toArray(String[]::new)),
                "One or more essential containers (Kafka, Apicurio, Consul) failed to start or register properties.");

        LOG.info("External services startup checked via TestContainerManager.");
    }
    // --- Test Lifecycle ---
    @BeforeEach
    public void setUp() {
        LOG.info("Executing @BeforeEach in AbstractPipelineTest");
        // Verify containers are running before proceeding
        assertTrue(containerManager.areEssentialContainersRunning("kafka", "apicurio", "consul"),
                "Kafka, Apicurio, or Consul not running at start of test method");

        // Reset the output consumer state
        testOutputConsumer.reset();

        // Create necessary Kafka topics using KafkaAdminClient
        createTestTopics();

        // Load test-specific configuration into Consul using ConsulTestHelper
        // This should happen *after* topics exist if config depends on them,
        // and *after* containers are confirmed running.
        loadConfigIntoConsul();
        LOG.info("Finished @BeforeEach in AbstractPipelineTest");
    }

    @AfterEach
    public void tearDown() {
        LOG.info("Executing @AfterEach in AbstractPipelineTest");
        // Clean up Consul configuration first
        cleanupConsulConfig();

        // Delete Kafka topics created for the test
        deleteTestTopics();
        LOG.info("Finished @AfterEach in AbstractPipelineTest");
    }

    // --- Abstract Methods --- (Unchanged)
    protected abstract PipeStream getInput();
    protected abstract PipeResponse getExpectedOutput();
    protected abstract PipeDoc getExpectedPipeDoc();
    protected abstract String getInputTopic(); // Topic the SUT listens on
    // Ensure this returns PIPELINE_TEST_OUTPUT_TOPIC if testKafkaToKafka is used
    protected abstract String getOutputTopic();
    // Properties to be loaded into Consul for the specific test
    protected abstract Properties getConfigToLoad();

    // --- Helper Methods ---

    /** Creates the fixed output topic and the dynamic input topic for the test. */
    protected void createTestTopics() {
        List<String> topicNames = new ArrayList<>();
        topicNames.add(PIPELINE_TEST_OUTPUT_TOPIC); // Fixed output topic

        String inputTopic = getInputTopic();
        if (inputTopic != null && !inputTopic.isBlank()) {
            topicNames.add(inputTopic);
        } else {
            LOG.warn("getInputTopic() returned null or blank, only creating output topic.");
        }

        LOG.info("Creating Kafka topics: {}", topicNames);
        List<NewTopic> newTopics = topicNames.stream()
                .distinct() // Ensure uniqueness
                .map(name -> new NewTopic(name, DEFAULT_PARTITIONS, DEFAULT_REPLICATION_FACTOR))
                .collect(Collectors.toList());

        if (!newTopics.isEmpty()) {
            try {
                kafkaAdminClient.createTopics(newTopics)
                        .all() // Get the future for all topic creations
                        .get(ADMIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); // Wait for completion
                LOG.info("Successfully created Kafka topics: {}", topicNames);
            } catch (Exception e) {
                // Log detailed error, including existing topics if possible
                LOG.error("Failed to create Kafka topics: {}. Error: {}", topicNames, e.getMessage(), e);
                // Attempt to list topics to see what exists
                try {
                    Set<String> existingTopics = kafkaAdminClient.listTopics().names().get(ADMIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    LOG.info("Existing topics: {}", existingTopics);
                } catch (Exception listEx) {
                    LOG.error("Failed to list topics after creation failure.", listEx);
                }
                // Rethrow or handle as appropriate for test stability
                fail("Failed to create necessary Kafka topics: " + topicNames, e);
            }
        } else {
            LOG.info("No topics requested for creation.");
        }
    }

    /** Deletes the fixed output topic and the dynamic input topic. */
    protected void deleteTestTopics() {
        List<String> topicNames = new ArrayList<>();
        topicNames.add(PIPELINE_TEST_OUTPUT_TOPIC);

        String inputTopic = getInputTopic();
        if (inputTopic != null && !inputTopic.isBlank()) {
            topicNames.add(inputTopic);
        }

        List<String> distinctTopicNames = topicNames.stream().distinct().collect(Collectors.toList());

        if (!distinctTopicNames.isEmpty()) {
            LOG.info("Deleting Kafka topics: {}", distinctTopicNames);
            try {
                kafkaAdminClient.deleteTopics(distinctTopicNames)
                        .all()
                        .get(ADMIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                LOG.info("Successfully deleted Kafka topics: {}", distinctTopicNames);
            } catch (Exception e) {
                // Log error but don't fail the teardown if possible
                LOG.warn("Failed to delete Kafka topics: {}. Error: {}", distinctTopicNames, e.getMessage(), e);
                // Attempt to list topics to see what remains
                try {
                    Set<String> existingTopics = kafkaAdminClient.listTopics().names().get(ADMIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    LOG.warn("Remaining topics after deletion attempt: {}", existingTopics);
                } catch (Exception listEx) {
                    LOG.error("Failed to list topics after deletion failure.", listEx);
                }
            }
        } else {
            LOG.info("No topics requested for deletion.");
        }
    }

    /** Loads configuration into Consul using the injected ConsulTestHelper. */
    protected void loadConfigIntoConsul() {
        Properties propsToLoad = getConfigToLoad();
        assertNotNull(propsToLoad, "getConfigToLoad() must return non-null Properties for Consul loading.");

        if (propsToLoad.isEmpty()) {
            LOG.warn("No config properties returned by getConfigToLoad() to load into Consul.");
            return;
        }

        LOG.info("Loading {} properties into Consul under prefix '{}'", propsToLoad.size(), CONSUL_CONFIG_PATH_PREFIX);
        try {
            boolean success = consulTestHelper.putProperties(propsToLoad, CONSUL_CONFIG_PATH_PREFIX);
            assertTrue(success, "Failed to load one or more config properties into Consul KV via ConsulTestHelper.");
            LOG.info("Successfully loaded configuration into Consul via ConsulTestHelper.");
        } catch (Exception e) {
            LOG.error("Error loading configuration into Consul using ConsulTestHelper.", e);
            fail("Failed to load configuration into Consul: " + e.getMessage(), e);
        }
    }

    /** Cleans up configuration from Consul using the injected ConsulTestHelper. */
    protected void cleanupConsulConfig() {
        Properties propsToLoad = getConfigToLoad(); // Need the keys to know what *might* have been loaded
        if (propsToLoad == null || propsToLoad.isEmpty()) {
            LOG.info("No properties defined by getConfigToLoad(), skipping Consul cleanup.");
            return;
        }

        // We generally clean the prefix recursively, assuming test isolation
        LOG.info("Attempting to clean up Consul KV store under prefix '{}' recursively.", CONSUL_CONFIG_PATH_PREFIX);
        try {
            // Use a method that deletes recursively if available, or delete individual keys
            // Assuming ConsulTestHelper has a recursive delete or we delete the base prefix
            boolean deleted = consulTestHelper.deleteKvRecursive(CONSUL_CONFIG_PATH_PREFIX);
            if(deleted) {
                LOG.info("Consul cleanup successful for prefix '{}'.", CONSUL_CONFIG_PATH_PREFIX);
            } else {
                // Attempt to delete individual keys as fallback or if recursive fails subtly
                LOG.warn("Recursive delete for prefix '{}' reported failure or was not fully effective. Attempting individual key deletion.", CONSUL_CONFIG_PATH_PREFIX);
                final boolean[] allDeleted = {true};
                propsToLoad.forEach((key, value) -> {
                    String fullKey = CONSUL_CONFIG_PATH_PREFIX + key;
                    boolean success = consulTestHelper.deleteKv(fullKey); // Assumes deleteKv exists
                    if (!success) {
                        // May have already been deleted recursively, log as debug
                        LOG.debug("Individual key '{}' not found or failed to delete during cleanup (might be expected).", fullKey);
                        // Optionally set allDeleted[0] = false; depending on desired strictness
                    }
                });
                LOG.info("Individual key cleanup attempted for Consul prefix '{}'.", CONSUL_CONFIG_PATH_PREFIX);
            }
        } catch (Exception e) {
            LOG.warn("Error during Consul cleanup for prefix '{}'. Might leave stale data. Error: {}", CONSUL_CONFIG_PATH_PREFIX, e.getMessage(), e);
            // Don't fail the teardown
        }
    }

    /**
     * Implements TestPropertyProvider by fetching combined properties
     * from the central TestContainerManager. Concrete tests can override this
     * to add specific application-level properties AFTER calling super.getProperties().
     */
    @Override
    @NonNull
    public Map<String, String> getProperties() {
        LOG.info("AbstractPipelineTest getProperties() fetching base properties from TestContainerManager");
        // Get ALL registered container properties (Kafka, Registry, Consul)
        Map<String, String> properties = new HashMap<>(TestContainerManager.getInstance().getProperties());

        // Ensure dynamic consumers/producers are generally enabled for tests unless overridden
        properties.putIfAbsent("kafka.consumer.dynamic.enabled", "true");
        properties.putIfAbsent("kafka.producer.dynamic.enabled", "true");

        // Log keys for brevity, especially in CI/CD logs
        LOG.debug("Providing base properties to Micronaut context: {}", properties.keySet());
        return properties;
        // REMINDER: Concrete test classes MUST override this method if they need to provide
        // application-specific properties (like pipeline.service.name). They should call
        // super.getProperties() and then add their own properties to the returned map.
        // Example in concrete test:
        // @Override @NonNull public Map<String, String> getProperties() {
        //     Map<String, String> props = super.getProperties();
        //     props.put("pipeline.service.name", "my-test-service");
        //     return props;
        // }
    }


    // --- Test Methods --- (Largely Unchanged logic, verify topic names)
    @Test
    @Requires(property = "kafka.consumer.dynamic.enabled", value = "true")
    public void testKafkaToKafka() throws Exception {
        PipeStream input = getInput();
        assertNotNull(input, "getInput() must not return null");
        PipeDoc originalDoc = input.getRequest().getDoc();
        assertNotNull(originalDoc, "Input PipeStream must contain a PipeDoc");

        // Ensure service name is configured via getProperties() in the concrete test
        String serviceName = internalServiceConfig.getPipelineServiceName();
        assertNotNull(serviceName, "pipeline.service.name must be configured via getProperties() in the concrete test class");

        String inputTopic = getInputTopic();
        String outputTopic = getOutputTopic(); // This MUST be PIPELINE_TEST_OUTPUT_TOPIC
        assertNotNull(inputTopic, "Input topic must be defined by concrete test's getInputTopic()");
        assertNotNull(outputTopic, "Output topic must be defined by concrete test's getOutputTopic()");

        // **Crucial Assertion**: The test consumer listens on a *fixed* topic.
        assertEquals(PIPELINE_TEST_OUTPUT_TOPIC, testOutputConsumer.getListeningTopic(),
                "Test consumer is listening on the wrong topic. Expected fixed topic: " + PIPELINE_TEST_OUTPUT_TOPIC);
        // **Crucial Assertion**: The expected output topic *must* match the consumer's topic.
        assertEquals(PIPELINE_TEST_OUTPUT_TOPIC, outputTopic,
                "getOutputTopic() must return AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC for testKafkaToKafka to work correctly.");

        LOG.info("Testing Kafka -> Kafka for Service '{}': Sending to '{}', expecting on '{}'", serviceName, inputTopic, outputTopic);
        producer.send(inputTopic, createKey(input), input);
        LOG.info("Sent message ID {} to input topic: {}", originalDoc.getId(), inputTopic);

        // Wait for the message on the fixed output topic
        PipeStream processedMessage = testOutputConsumer.getNextMessage(20); // Increased timeout slightly

        LOG.info("Received message from output topic {}: {}", outputTopic, (processedMessage != null ? "Yes, ID " + processedMessage.getRequest().getDoc().getId() : "No (Timeout)"));
        assertNotNull(processedMessage, "Processed message should not be null (timed out waiting on " + outputTopic + ")");

        assertTrue(processedMessage.hasRequest(), "Processed message should have a request");
        PipeDoc processedDoc = processedMessage.getRequest().getDoc();
        assertNotNull(processedDoc, "Processed message request should have a document");

        // Compare with expected state
        PipeDoc expectedDoc = getExpectedPipeDoc(); // Can be null if only response matters
        PipeResponse expectedResponse = getExpectedOutput();
        assertNotNull(expectedResponse, "getExpectedOutput() must not return null");

        // Compare document state if expectedDoc is provided
        if (expectedDoc != null) {
            assertEquals(expectedDoc.getId(), processedDoc.getId(), "Document ID mismatch");
            // Add more detailed document comparisons if needed
            assertEquals(expectedDoc, processedDoc, "Processed document content mismatch");
        }

        // Compare response state
        assertTrue(processedMessage.getPipeRepliesCount() > 0, "Should have at least one PipeResponse in PipeStream");
        PipeResponse actualResponse = processedMessage.getPipeReplies(processedMessage.getPipeRepliesCount() - 1); // Get the last response
        assertEquals(expectedResponse.getSuccess(), actualResponse.getSuccess(), "Response success flag mismatch");
        // Add more detailed response comparisons if needed
//        assertEquals(expectedResponse.getMessage(), actualResponse.getMessage(), "Response message mismatch");
//        assertEquals(expectedResponse.getErrorCode(), actualResponse.getErrorCode(), "Response error code mismatch");

        LOG.info("testKafkaToKafka completed successfully.");
    }

    @Test
    public void testGrpcInputDirectProcessorCall() {
        LOG.info("Testing gRPC input via direct processor call for service implementation: {}", pipelineServiceProcessor.getClass().getName());
        PipeStream input = getInput();
        assertNotNull(input, "getInput() must not return null");

        PipeServiceDto result = pipelineServiceProcessor.process(input);
        assertNotNull(result, "Direct processor call should return a non-null DTO");

        PipeDoc processedDoc = result.getPipeDoc(); // Can be null if processor doesn't modify/return doc
        PipeResponse response = result.getResponse();
        assertNotNull(response, "Response from processor should not be null");

        PipeDoc expectedDoc = getExpectedPipeDoc(); // Can be null
        PipeResponse expectedResponse = getExpectedOutput();
        assertNotNull(expectedResponse, "getExpectedOutput() must not return null");

        // Compare response state
        assertEquals(expectedResponse.getSuccess(), response.getSuccess(), "Response success flag mismatch (direct call)");
//        assertEquals(expectedResponse.getMessage(), response.getMessage(), "Response message mismatch (direct call)");
//        assertEquals(expectedResponse.getErrorCode(), response.getErrorCode(), "Response error code mismatch (direct call)");

        // Compare document state if both expected and actual are non-null
        if (expectedDoc != null && processedDoc != null) {
            assertEquals(expectedDoc.getId(), processedDoc.getId(), "Document ID mismatch (direct call)");
            assertEquals(expectedDoc, processedDoc, "Processed document content mismatch (direct call)");
        } else if (expectedDoc != null) {
            fail("Expected document was not null, but processed document was null (direct call)");
        } else {
            // If expectedDoc is null, processedDoc can be null or non-null depending on processor logic
            // No assertion needed here unless specific behavior is expected.
            LOG.debug("Expected document was null, processed document is: {}", processedDoc == null ? "null" : "non-null");
        }

        LOG.info("Direct processor call test completed successfully.");
    }

    // --- Placeholders --- (Marked as Disabled for clarity)
    @Test @Disabled("Test testKafkaToGrpc not implemented yet.")
    public void testKafkaToGrpc() { /* TODO */ }
    @Test @Disabled("Test testGrpcToKafka not implemented yet.")
    public void testGrpcToKafka() throws Exception { /* TODO */ }
    @Test @Disabled("Test testGrpcToGrpc not implemented yet.")
    public void testGrpcToGrpc() { /* TODO */ }


    // --- Inner Class: Test Output Consumer --- (Unchanged - Listens on fixed topic)
    @Singleton
    @KafkaListener(groupId = "pipeline-test-output-consumer-group") // Unique group ID for the test consumer
    @Requires(property = "kafka.consumer.dynamic.enabled", value = "true") // Only active when dynamic consumers are enabled
    public static class PipeStreamOutputConsumer {
        private static final Logger LOG_CONSUMER = LoggerFactory.getLogger(PipeStreamOutputConsumer.class);
        private final List<PipeStream> receivedMessages = new CopyOnWriteArrayList<>();
        // Use CompletableFuture for async waiting
        private CompletableFuture<PipeStream> nextMessageFuture = new CompletableFuture<>();
        // This consumer *always* listens on the fixed output topic.
        private final String listeningTopic = AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC;

        public PipeStreamOutputConsumer() {
            LOG_CONSUMER.info("PipeStreamOutputConsumer initialized, listening on fixed topic: {}", listeningTopic);
        }

        @Topic(AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC) // Annotation binds to the fixed topic
        public void receive(PipeStream message) {
            // Defensive null check
            if (message == null || !message.hasRequest() || message.getRequest().getDoc() == null) {
                LOG_CONSUMER.warn("Received potentially invalid null or incomplete message on topic '{}'. Ignoring.", listeningTopic);
                return;
            }
            LOG_CONSUMER.info("Received message on fixed output topic '{}': ID {}", listeningTopic, message.getRequest().getDoc().getId());
            synchronized (receivedMessages) {
                receivedMessages.add(message);
                // Complete the current future and immediately create a new one for the next wait
                CompletableFuture<PipeStream> futureToComplete = nextMessageFuture;
                nextMessageFuture = new CompletableFuture<>();
                futureToComplete.complete(message); // Complete the future associated with the previous getNextMessage call

                // Sanity check log if messages arrive faster than getNextMessage is called
                if (receivedMessages.size() > 1 && !futureToComplete.isDone() ) {
                    // This scenario should be less common with the immediate future replacement
                    LOG_CONSUMER.warn("Consumer potentially received messages faster than test processed them. Current count: {}", receivedMessages.size());
                }
            }
        }

        /**
         * Waits for the next message received by this consumer.
         * Uses CompletableFuture for efficient waiting.
         *
         * @param timeoutSeconds Max time to wait.
         * @return The received PipeStream, or null if timed out.
         * @throws Exception If waiting is interrupted or encounters an execution error.
         */
        public PipeStream getNextMessage(long timeoutSeconds) throws Exception {
            LOG_CONSUMER.debug("Waiting for next message on topic '{}' (timeout: {}s).", listeningTopic, timeoutSeconds);
            CompletableFuture<PipeStream> futureToWaitFor;
            synchronized (receivedMessages) {
                // Get the *current* future to wait on
                futureToWaitFor = this.nextMessageFuture;
            }
            try {
                // Wait on the future captured above
                PipeStream message = futureToWaitFor.get(timeoutSeconds, TimeUnit.SECONDS);
                LOG_CONSUMER.debug("Message received or future completed for topic '{}'.", listeningTopic);
                return message;
            } catch (TimeoutException e) {
                LOG_CONSUMER.warn("Timeout waiting for message on output topic '{}' after {} seconds.", listeningTopic, timeoutSeconds);
                // Ensure the future we waited on is cancelled if it didn't complete
                // No need to replace nextMessageFuture here, receive() handles that.
                futureToWaitFor.cancel(false); // Cancel if timed out
                return null; // Return null on timeout
            } catch (Exception e) {
                LOG_CONSUMER.error("Error waiting for message on output topic '{}'", listeningTopic, e);
                futureToWaitFor.cancel(false); // Cancel on error too
                throw e; // Rethrow other exceptions
            }
        }

        /** Returns an immutable list of all messages received so far. */
        public List<PipeStream> getReceivedMessages() {
            synchronized (receivedMessages) {
                return List.copyOf(receivedMessages);
            }
        }

        /** Returns the fixed topic this consumer listens on. */
        public String getListeningTopic() {
            return listeningTopic;
        }

        /** Resets the consumer state for a new test run. */
        public void reset() {
            synchronized (receivedMessages) {
                LOG_CONSUMER.debug("Resetting PipeStreamOutputConsumer for topic '{}'. Clearing {} messages.", listeningTopic, receivedMessages.size());
                receivedMessages.clear();
                // Cancel any pending future and create a fresh one
                if (!nextMessageFuture.isDone()) {
                    nextMessageFuture.cancel(false);
                }
                nextMessageFuture = new CompletableFuture<>();
            }
        }
    }
}