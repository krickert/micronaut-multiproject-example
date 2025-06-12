package com.krickert.search.orchestrator.kafka.apicurio;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for KafkaApicurioTest.
 * This test verifies that the Apicurio Registry and Kafka Brokers are properly set up via Testcontainers.
 */
@MicronautTest
public class KafkaApicurioTestIT {
    private static final Logger log = LoggerFactory.getLogger(KafkaApicurioTestIT.class);

    private static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "test-pipeline-input",
            "test-processor-input",
            "test-pipeline-output",
            "pipeline-test-output-topic"
    );

    @Inject
    private ApplicationContext applicationContext;

    // Simplify injection - remove default. Testcontainers MUST set this.
    @Value("${apicurio.registry.url}")
    String injectedRegistryUrl;

    // Simplify injection - remove default. Testcontainers MUST set this.
    @Value("${kafka.bootstrap.servers}")
    String injectedBootstrapServersWithPrefix; // Keep original value for comparison

    @Value("${kafka.schema.registry.type}")
    String registryType;

    // Helper to get property directly from context for verification
    private Optional<String> getApicurioUrlFromContext() {
        return applicationContext.getProperty("apicurio.registry.url", String.class);
    }

    // Helper to get property directly from context for verification
    private Optional<String> getBootstrapServersFromContext() {
        return applicationContext.getProperty("kafka.bootstrap.servers", String.class);
    }

    /**
     * Create the required Kafka topics before running the test.
     * This ensures that the topics exist when the test runs.
     */
    @BeforeEach
    void createKafkaTopics() throws ExecutionException, InterruptedException, TimeoutException {
        log.info("Creating Kafka topics for test...");

        // Get the bootstrap servers from the context
        Optional<String> contextKafkaServersOpt = getBootstrapServersFromContext();
        if (!contextKafkaServersOpt.isPresent()) {
            log.warn("Bootstrap servers not found in context, skipping topic creation");
            return;
        }

        String kafkaServersWithPrefix = contextKafkaServersOpt.get();
        String kafkaServers = kafkaServersWithPrefix.replace("PLAINTEXT://", "");
        log.info("Using Kafka bootstrap servers: {}", kafkaServers);

        // Create AdminClient configuration
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            // Check if topics already exist
            Set<String> existingTopics = adminClient.listTopics().names().get(30, TimeUnit.SECONDS);
            log.info("Existing topics: {}", existingTopics);

            // Create list of topics to create (only those that don't already exist)
            List<NewTopic> topicsToCreate = DEFAULT_TOPICS.stream()
                    .filter(topic -> !existingTopics.contains(topic))
                    .map(topic -> new NewTopic(topic, 1, (short) 1)) // 1 partition, 1 replica
                    .collect(Collectors.toList());

            if (topicsToCreate.isEmpty()) {
                log.info("All required topics already exist, skipping topic creation");
                return;
            }

            log.info("Creating topics: {}", topicsToCreate.stream().map(NewTopic::name).collect(Collectors.toList()));

            // Create the topics
            CreateTopicsResult result = adminClient.createTopics(topicsToCreate);

            // Wait for topic creation to complete
            result.all().get(30, TimeUnit.SECONDS);

            // Verify topics were created
            Set<String> updatedTopics = adminClient.listTopics().names().get(30, TimeUnit.SECONDS);
            log.info("Updated topics list: {}", updatedTopics);

            // Verify all required topics exist
            for (String topic : DEFAULT_TOPICS) {
                if (!updatedTopics.contains(topic)) {
                    log.error("Failed to create topic: {}", topic);
                    fail("Failed to create required topic: " + topic);
                }
            }

            log.info("Successfully created all required Kafka topics");
        } catch (Exception e) {
            log.error("Error creating Kafka topics", e);
            throw e;
        }
    }


    @Test
    void testApicurioRegistryAndKafkaSetup() throws ExecutionException, InterruptedException, TimeoutException {
        // 1. Verify registry type from config
        assertEquals("apicurio", registryType, "Kafka registry type should be 'apicurio'");

        // 2. Verify Apicurio URL is set by Testcontainers
        Optional<String> contextApicurioUrlOpt = getApicurioUrlFromContext();
        assertTrue(contextApicurioUrlOpt.isPresent(), "apicurio.registry.url should be present in the context");
        String apicurioEndpoint = contextApicurioUrlOpt.get();
        log.info("Retrieved Apicurio Registry URL from context: {}", apicurioEndpoint);
        log.info("Injected Apicurio Registry URL (@Value): {}", injectedRegistryUrl);
        assertEquals(apicurioEndpoint, injectedRegistryUrl, "@Value injection for Apicurio URL should match context property");
        assertThat(apicurioEndpoint).startsWith("http://");

        // 3. Verify Kafka Bootstrap Servers are set by Testcontainers
        Optional<String> contextKafkaServersOpt = getBootstrapServersFromContext();
        assertTrue(contextKafkaServersOpt.isPresent(), "kafka.bootstrap.servers should be present in the context");
        String kafkaServersWithPrefix = contextKafkaServersOpt.get();
        log.info("Retrieved Kafka Bootstrap Servers from context (with prefix): {}", kafkaServersWithPrefix);
        log.info("Injected Kafka Bootstrap Servers (@Value) (with prefix): {}", injectedBootstrapServersWithPrefix);
        assertEquals(kafkaServersWithPrefix, injectedBootstrapServersWithPrefix, "@Value injection for Kafka Servers should match context property");
        assertThat(kafkaServersWithPrefix)
                .isNotNull()
                .isNotBlank()
                .doesNotContain("${"); // Ensure placeholder is resolved

        // ** Attempt to strip the prefix **
        String kafkaServers = kafkaServersWithPrefix.replace("PLAINTEXT://", "");
        log.info("Stripped Kafka Bootstrap Servers for AdminClient: {}", kafkaServers);


        // 4. Verify Kafka topics can be listed using the stripped servers
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers); // Use stripped value
        // Optional: Increase timeout slightly just in case
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000); // Default is 30000, maybe increase slightly if needed
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000); // Default is 60000

        log.info("Attempting to create AdminClient with bootstrap servers: {}", kafkaServers);
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            log.info("AdminClient created successfully.");
            ListTopicsResult topics = adminClient.listTopics();
            // Add a timeout to the Kafka future get()
            Set<String> topicNames = topics.names().get(30, TimeUnit.SECONDS); // Wait up to 30 seconds
            log.info("Available Kafka topics: {}", topicNames);
            assertThat(topicNames).containsAll(DEFAULT_TOPICS);
        } catch (TimeoutException e) {
            log.error("Timed out waiting for Kafka topics list with servers '{}'", kafkaServers, e);
            fail("Timed out waiting for Kafka topics list using bootstrap servers: " + kafkaServers, e);
        } catch (Exception e) {
            log.error("Failed to create KafkaAdminClient or list topics with servers '{}'", kafkaServers, e);
            // Log the original value with prefix as well for context
            log.error("Original bootstrap server value from context was: {}", kafkaServersWithPrefix);
            fail("Failed to interact with Kafka using bootstrap servers: " + kafkaServers, e);
        }

        // 5. Verify other properties (Example)
        Optional<String> producerApicurioUrl = applicationContext.getProperty("kafka.producers.default.apicurio.registry.url", String.class);
        assertTrue(producerApicurioUrl.isPresent(), "Producer Apicurio URL should be configured");
        assertEquals(apicurioEndpoint, producerApicurioUrl.get(), "Producer Apicurio URL should match the main one");

        log.info("All checks passed for Apicurio Registry and Kafka setup.");
    }
}
