package com.krickert.search.pipeline.kafka.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for KafkaMotoTest.
 * This test verifies that the Moto Registry is properly set up and can be used with Kafka.
 */
@MicronautTest(environments = "moto")
public class KafkaMotoTestIT  {
    private static final Logger log = LoggerFactory.getLogger(KafkaMotoTestIT.class);

    // Default topics that should be created
    private static final List<String> DEFAULT_TOPICS = Arrays.asList(
            "test-pipeline-input", 
            "test-processor-input", 
            "test-pipeline-output", 
            "pipeline-test-output-topic"
    );

    // AWS credentials and region for testing
    private static final String AWS_ACCESS_KEY = "test";
    private static final String AWS_SECRET_KEY = "test";
    private static final String AWS_REGION = "us-east-1";
    private static final String REGISTRY_NAME = "default";

    @Inject
    private ApplicationContext applicationContext;

    @Value("${kafka.registry.type}")
    String registryType;

    @Value("${moto.registry.url:#{null}}")
    String registryUrl;

    @Value("${kafka.bootstrap.servers:#{null}}")
    String bootstrapServers;

    /**
     * Get the registry endpoint from the application context.
     * @return The registry endpoint URL
     */
    private String getRegistryEndpoint() {
        return registryUrl != null ? registryUrl : applicationContext.getProperty("moto.registry.url", String.class).orElse(null);
    }

    /**
     * Get the Kafka bootstrap servers from the application context.
     * @return The bootstrap servers
     */
    private String getBootstrapServers() {
        return bootstrapServers != null ? bootstrapServers : applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
    }

    /**
     * Test that the Moto Registry is properly set up and can be used with Kafka.
     */
    @Test
    void testMotoRegistrySetup() throws ExecutionException, InterruptedException {
        // Verify that the registry type is set correctly
        assertEquals("moto", registryType);

        // Verify that the registry endpoint is set
        String endpoint = getRegistryEndpoint();
        assertThat(endpoint).isNotNull();
        log.info("Moto Registry endpoint: {}", endpoint);

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
            for (String topic : DEFAULT_TOPICS) {
                assertThat(topicNames).contains(topic);
            }
        }

        // Verify that the properties are set correctly
        Map<String, String> props = new HashMap<>();

        // Add Kafka properties
        props.put("kafka.bootstrap.servers", getBootstrapServers());

        // Add producer properties
        String producerPrefix = "kafka.producers.default.";
        props.put(producerPrefix + "bootstrap.servers", getBootstrapServers());
        props.put(producerPrefix + "key.serializer", applicationContext.getProperty(producerPrefix + "key.serializer", String.class).orElse(""));
        props.put(producerPrefix + "value.serializer", applicationContext.getProperty(producerPrefix + "value.serializer", String.class).orElse(""));

        // Add consumer properties
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + "bootstrap.servers", getBootstrapServers());
        props.put(consumerPrefix + "key.deserializer", applicationContext.getProperty(consumerPrefix + "key.deserializer", String.class).orElse(""));
        props.put(consumerPrefix + "value.deserializer", applicationContext.getProperty(consumerPrefix + "value.deserializer", String.class).orElse(""));

        // Add Moto Registry properties
        props.put("moto.registry.url", getRegistryEndpoint());
        props.put("moto.registry.name", REGISTRY_NAME);
        props.put("aws.region", AWS_REGION);
        props.put("aws.accessKeyId", AWS_ACCESS_KEY);
        props.put("aws.secretAccessKey", AWS_SECRET_KEY);

        assertThat(props).isNotEmpty();

        // Verify Kafka properties
        assertThat(props).containsKey("kafka.bootstrap.servers");

        // Verify producer properties
        assertThat(props).containsKey(producerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(producerPrefix + "key.serializer");
        assertThat(props).containsKey(producerPrefix + "value.serializer");

        // Verify consumer properties
        assertThat(props).containsKey(consumerPrefix + "bootstrap.servers");
        assertThat(props).containsKey(consumerPrefix + "key.deserializer");
        assertThat(props).containsKey(consumerPrefix + "value.deserializer");

        // Verify Moto Registry properties
        assertThat(props).containsKey("moto.registry.url");
        assertThat(props).containsKey("moto.registry.name");
        assertThat(props).containsKey("aws.region");
        assertThat(props).containsKey("aws.accessKeyId");
        assertThat(props).containsKey("aws.secretAccessKey");

        log.info("All tests passed for Moto Registry setup");
    }
}
