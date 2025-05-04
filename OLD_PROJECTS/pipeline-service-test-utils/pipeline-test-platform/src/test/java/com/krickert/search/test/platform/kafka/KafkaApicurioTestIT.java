package com.krickert.search.test.platform.kafka;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for KafkaApicurioTest.
 * This test verifies that the Apicurio Registry is properly set up and can be used with Kafka.
 */
@MicronautTest(environments = "apicurio")
public class KafkaApicurioTestIT extends KafkaApicurioTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaApicurioTestIT.class);

    private final TestContainerManager containerManager = TestContainerManager.getInstance();

    /**
     * Test that the Apicurio Registry is properly set up and can be used with Kafka.
     */
    @Test
    void testApicurioRegistrySetup() throws ExecutionException, InterruptedException {
        // Verify that the registry type is set correctly
        assertThat(containerManager.getRegistryType()).isEqualTo("apicurio");

        // Verify that the registry endpoint is set
        String endpoint = getRegistryEndpoint();
        assertThat(endpoint).isNotNull();
        assertThat(endpoint).contains("/apis/registry/v3");
        log.info("Apicurio Registry endpoint: {}", endpoint);

        // Verify that the containers are running
        assertThat(areContainersRunning()).isTrue();

        // Verify that the topics are created
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            ListTopicsResult topics = adminClient.listTopics();
            Set<String> topicNames = topics.names().get();

            log.info("Available topics: {}", topicNames);

            // Verify that all expected topics are created
            for (String topic : DEFAULT_TOPICS) {
                assertThat(topicNames).contains(topic);
            }
        }

        // Verify that the properties are set correctly
        Map<String, String> props = containerManager.getProperties();
        assertThat(props).isNotEmpty();

        // Verify Kafka properties
        assertThat(props).containsKey("kafka.bootstrap.servers");

        // Verify producer properties
        String producerPrefix = "kafka.producers.default.";
        assertThat(props).containsKey(producerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(producerPrefix + "key.serializer");
        assertThat(props).containsKey(producerPrefix + "value.serializer");

        // Verify consumer properties
        String consumerPrefix = "kafka.consumers.default.";
        assertThat(props).containsKey(consumerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(consumerPrefix + "key.deserializer");
        assertThat(props).containsKey(consumerPrefix + "value.deserializer");

        // Verify Apicurio Registry properties
        assertThat(props).containsKey(producerPrefix + "apicurio.registry.url");
        assertThat(props).containsKey(consumerPrefix + "apicurio.registry.url");

        log.info("All tests passed for Apicurio Registry setup");
    }
}
