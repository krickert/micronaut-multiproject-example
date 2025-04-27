package com.krickert.search.test.platform.kafka;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetRegistryResponse;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for KafkaGlueTest.
 * This test verifies that the Glue Registry is properly set up and can be used with Kafka.
 */
@MicronautTest(environments = "glue")
public class KafkaGlueTestIT extends KafkaGlueTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaGlueTestIT.class);

    private final TestContainerManager containerManager = TestContainerManager.getInstance();

    // AWS credentials and region for testing
    private static final String AWS_ACCESS_KEY = "test";
    private static final String AWS_SECRET_KEY = "test";
    private static final String AWS_REGION = "us-east-1";
    private static final String REGISTRY_NAME = "test-registry";

    /**
     * Test that the Glue Registry is properly set up and can be used with Kafka.
     */
    @Test
    void testGlueRegistrySetup() throws ExecutionException, InterruptedException {
        // Verify that the registry type is set correctly
        assertThat(containerManager.getRegistryType()).isEqualTo("glue");

        // Verify that the registry endpoint is set
        String endpoint = getRegistryEndpoint();
        assertThat(endpoint).isNotNull();
        log.info("Glue Registry endpoint: {}", endpoint);

        // Verify that the containers are running
        assertThat(areContainersRunning()).isTrue();

        // Verify that the registry exists
        GlueClient glueClient = GlueClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(AWS_REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)
                ))
                .build();

        GetRegistryResponse registry = glueClient.getRegistry(builder -> builder.registryId(id -> id.registryName(REGISTRY_NAME)));
        assertThat(registry).isNotNull();
        assertThat(registry.registryName()).isEqualTo(REGISTRY_NAME);
        log.info("Found registry: {}", registry.registryName());

        // Verify that the topics are created
        Map<String, Object> adminProps = new HashMap<>();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());

        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            ListTopicsResult topics = adminClient.listTopics();
            Set<String> topicNames = topics.names().get();

            log.info("Available topics: {}", topicNames);

            // Verify that all expected topics are created
            for (String topic : TOPICS) {
                assertThat(topicNames).contains(topic);
            }
        }

        // Verify that the properties are set correctly
        Map<String, String> props = getProperties();
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

        // Verify Glue Registry properties
        assertThat(props).containsKey(producerPrefix + "aws.region");
        assertThat(props).containsKey(producerPrefix + "registry.name");
        assertThat(props).containsKey(consumerPrefix + "aws.region");
        assertThat(props).containsKey(consumerPrefix + "registry.name");

        log.info("All tests passed for Glue Registry setup");
    }
}
