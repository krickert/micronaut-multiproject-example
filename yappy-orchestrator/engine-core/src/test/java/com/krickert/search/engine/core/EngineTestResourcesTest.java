package com.krickert.search.engine.core;

import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Advanced integration test demonstrating Micronaut test resources usage
 * with Kafka and Consul test containers.
 */
@MicronautTest
@Property(name = "spec.name", value = "EngineTestResourcesTest")
class EngineTestResourcesTest {

    @Inject
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;

    @Inject
    @Property(name = "consul.client.host")
    String consulHost;

    @Inject
    @Property(name = "consul.client.port")
    Integer consulPort;

    @Inject
    TestKafkaProducer kafkaProducer;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testKafkaConnectivity() throws Exception {
        // Create Kafka admin client to verify connectivity
        Properties props = new Properties();
        props.put("bootstrap.servers", kafkaBootstrapServers);
        
        try (AdminClient adminClient = AdminClient.create(props)) {
            // List topics to verify Kafka is accessible
            ListTopicsResult topics = adminClient.listTopics();
            Set<String> topicNames = topics.names().get(10, TimeUnit.SECONDS);
            
            assertThat(topicNames).isNotNull();
            System.out.println("Connected to Kafka at: " + kafkaBootstrapServers);
            System.out.println("Available topics: " + topicNames);
        }
    }

    @Test
    void testKafkaProducerClient() {
        // Test that we can send a message using Kafka client
        String testMessage = "Test message from engine-core";
        kafkaProducer.sendMessage("test-key", testMessage);
        
        // In a real test, you would have a consumer to verify the message
        System.out.println("Sent test message to Kafka: " + testMessage);
    }

    @Test
    void testConsulEndpointConfiguration() {
        // Verify Consul endpoint is properly configured
        assertThat(consulHost)
                .as("Consul host should be configured")
                .isNotNull()
                .isNotEmpty();
        
        assertThat(consulPort)
                .as("Consul port should be configured")
                .isNotNull()
                .isPositive();
        
        String consulEndpoint = String.format("http://%s:%d", consulHost, consulPort);
        System.out.println("Consul endpoint configured at: " + consulEndpoint);
        
        // In a real test, you might want to verify Consul connectivity
        // by making an HTTP request to the health endpoint
    }

    @Test
    void testTestResourcesIntegration() {
        // This test verifies that multiple test resources can work together
        assertThat(kafkaBootstrapServers)
                .as("Kafka should be provided by test resources")
                .isNotNull()
                .contains("localhost");
        
        assertThat(consulHost)
                .as("Consul should be provided by test resources")
                .isNotNull()
                .isNotEmpty();
        
        // Log all test resource properties for debugging
        System.out.println("Test Resources Configuration:");
        System.out.println("  Kafka Bootstrap Servers: " + kafkaBootstrapServers);
        System.out.println("  Consul Host: " + consulHost);
        System.out.println("  Consul Port: " + consulPort);
    }

    /**
     * Test Kafka producer client for integration tests
     */
    @KafkaClient
    @Requires(property = "spec.name", value = "EngineTestResourcesTest")
    public interface TestKafkaProducer {
        @Topic("test-topic")
        void sendMessage(@KafkaKey String key, String message);
    }
}