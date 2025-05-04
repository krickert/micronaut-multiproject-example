// <llm-snippet-file>pipeline-service-test-utils/pipeline-test-platform/src/main/java/com/krickert/search/test/platform/kafka/AbstractKafkaTest.java</llm-snippet-file>
package com.krickert.search.test.platform.kafka;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Abstract base for Kafka tests. Relies on TestContainerManager for container lifecycle
 * and provides topic management utilities.
 */
public abstract class AbstractKafkaTest implements KafkaTest {
    protected static final Logger log = LoggerFactory.getLogger(AbstractKafkaTest.class);

    // REMOVE: KafkaContainer instance and related constants

    // Get the singleton TestContainerManager instance
    protected static final TestContainerManager containerManager = TestContainerManager.getInstance();

    // REMOVE: Static initializer block that called setupKafkaContainer

    public static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "test-pipeline-input", "test-pipeline-output", "test-PipeStream", "config-updates"
    );

    // REMOVE: setupKafkaContainer method
    // REMOVE: registerKafkaProperties method

    @BeforeEach
    public void setUp() {
        log.debug("Ensuring containers are started via TestContainerManager...");
        // Ensure containers are started (subclass implementation relies on TestContainerManager)
        startContainers();
        log.debug("Creating default Kafka topics...");
        // Create topics using the bootstrap servers from TestContainerManager
        createTopics();
        log.debug("Kafka setup complete for test.");
    }

    @AfterEach
    public void tearDown() {
        log.debug("Deleting default Kafka topics...");
        // Delete topics using the bootstrap servers from TestContainerManager
        deleteTopics();
        // Reset containers (subclass implementation relies on TestContainerManager)
        // This might clear state within containers if implemented, but doesn't stop them here.
        // Stopping happens via JVM shutdown hook in TestContainerManager.
        // resetContainers(); // Optional: depends on what resetContainers does in subclasses
        log.debug("Kafka teardown complete for test.");
    }

    /**
     * Gets the Kafka bootstrap servers from the central TestContainerManager.
     *
     * @return Kafka bootstrap servers string.
     * @throws IllegalStateException if TestContainerManager hasn't populated properties yet.
     */
    protected String getBootstrapServers() {
        String bootstrapServers = containerManager.getProperties().get("kafka.bootstrap.servers");
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            // This might happen if accessed too early, before TestContainerManager finishes init.
            log.error("Kafka bootstrap servers property is missing from TestContainerManager. Forcing manager init.");
            // Attempt to force initialization again, though ideally it should be ready.
            TestContainerManager.getInstance();
            bootstrapServers = containerManager.getProperties().get("kafka.bootstrap.servers");
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalStateException("Kafka bootstrap servers property ('kafka.bootstrap.servers') not found in TestContainerManager properties after retry.");
            }
        }
        log.trace("Retrieved bootstrap servers: {}", bootstrapServers);
        return bootstrapServers;
    }

    // createTopics/deleteTopics unchanged internally, but now use the updated getBootstrapServers()
    @Override
    public void createTopics() {
        createTopics(DEFAULT_TOPICS);
    }

    @Override
    public void deleteTopics() {
        deleteTopics(DEFAULT_TOPICS);
    }

    @Override
    public void createTopics(List<String> topicsToCreate) {
        if (topicsToCreate == null || topicsToCreate.isEmpty()) {
            log.warn("No topics specified for creation.");
            return;
        }
        log.info("Attempting to create Kafka topics: {}", topicsToCreate);
        // Use the centrally managed bootstrap servers
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers()); // Uses updated method
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000"); // Consider making timeouts configurable
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get(20, TimeUnit.SECONDS);
            List<NewTopic> topicsToActuallyCreate = topicsToCreate.stream()
                    .filter(topic -> !existingTopics.contains(topic))
                    // Defaulting to 1 partition, 1 replica - suitable for testing
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .collect(Collectors.toList());

            if (topicsToActuallyCreate.isEmpty()) {
                log.info("All requested topics {} already exist.", topicsToCreate);
                return;
            }

            log.info("Topics to actually create: {}", topicsToActuallyCreate.stream().map(NewTopic::name).collect(Collectors.toList()));
            CreateTopicsResult result = adminClient.createTopics(topicsToActuallyCreate);
            // Wait for topic creation to complete
            result.all().get(60, TimeUnit.SECONDS);
            log.info("Topics created successfully: {}", topicsToActuallyCreate.stream().map(NewTopic::name).collect(Collectors.toList()));

        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                log.warn("One or more topics already existed (likely race condition or previous run): {}", e.getCause().getMessage());
            } else {
                log.error("Error creating topics (ExecutionException): {}", e.getMessage(), e);
                // Re-throw or handle more gracefully depending on requirements
                throw new RuntimeException("Failed to create Kafka topics", e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while creating Kafka topics: {}", e.getMessage(), e);
            throw new RuntimeException("Interrupted during topic creation", e);
        } catch (TimeoutException e) {
            log.error("Timed out while creating Kafka topics: {}", e.getMessage(), e);
            throw new RuntimeException("Timed out during topic creation", e);
        } catch (Exception e) {
            // Catch unexpected errors
            log.error("Unexpected error during topic creation: {}", e.getMessage(), e);
            throw new RuntimeException("Unexpected error during topic creation", e);
        }
    }

    @Override
    public void deleteTopics(List<String> topicsToDelete) {
        if (topicsToDelete == null || topicsToDelete.isEmpty()) {
            log.warn("No topics specified for deletion.");
            return;
        }
        log.info("Attempting to delete Kafka topics: {}", topicsToDelete);
        // Use the centrally managed bootstrap servers
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers()); // Uses updated method
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000");
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Set<String> existingTopics = adminClient.listTopics().names().get(20, TimeUnit.SECONDS);
            List<String> topicsToActuallyDelete = topicsToDelete.stream()
                    .filter(existingTopics::contains)
                    .collect(Collectors.toList());

            if (topicsToActuallyDelete.isEmpty()) {
                log.info("None of the specified topics {} exist for deletion.", topicsToDelete);
                return;
            }

            log.info("Topics to actually delete: {}", topicsToActuallyDelete);
            DeleteTopicsResult result = adminClient.deleteTopics(topicsToActuallyDelete);
            // Wait for topic deletion to complete
            result.all().get(60, TimeUnit.SECONDS);
            log.info("Topics deleted successfully: {}", topicsToActuallyDelete);

        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                log.warn("One or more topics did not exist during deletion attempt: {}", e.getCause().getMessage());
            } else {
                log.error("Error deleting topics (ExecutionException): {}", e.getMessage(), e);
                // Consider if this should halt tests
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while deleting Kafka topics: {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            log.error("Timed out while deleting Kafka topics: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during topic deletion: {}", e.getMessage(), e);
        }
    }


    // --- Abstract methods to be implemented by subclasses ---
    // These subclasses will interact with containerManager as needed

    @Override
    public abstract String getRegistryType(); // e.g., "apicurio" or "glue"

    @Override
    public abstract String getRegistryEndpoint(); // Get endpoint from containerManager properties

    /**
     * Starts necessary containers via TestContainerManager.
     * Implemented by subclasses (e.g., KafkaApicurioTest, KafkaGlueTest).
     * This method should ensure TestContainerManager.getInstance() is called if not already.
     */
    @Override
    public abstract void startContainers();

    /**
     * Checks if the relevant containers (Kafka, Schema Registry, Consul) are running.
     * Implemented by subclasses, likely checking containerManager's container states.
     */
    @Override
    public abstract boolean areContainersRunning();

    /**
     * Resets container state if necessary (e.g., clearing data).
     * Stopping is handled by TestContainerManager's shutdown hook.
     * Implementation depends on specific test needs. Might be a no-op.
     */
    @Override
    public abstract void resetContainers();
}