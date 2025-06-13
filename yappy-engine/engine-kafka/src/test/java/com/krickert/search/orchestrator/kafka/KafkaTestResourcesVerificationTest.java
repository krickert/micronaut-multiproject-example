package com.krickert.search.orchestrator.kafka;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@MicronautTest
@Property(name = "micronaut.test-resources.enabled", value = "true")
@Property(name = "micronaut.test-resources.shared-server", value = "true")
class KafkaTestResourcesVerificationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaTestResourcesVerificationTest.class);
    
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Test
    void testKafkaTestResourcesProvided() {
        LOG.info("Kafka test resources verification:");
        LOG.info("Kafka bootstrap servers: {}", kafkaBootstrapServers);
        
        assertNotNull(kafkaBootstrapServers, "Kafka bootstrap servers should be provided by test resources");
        assertFalse(kafkaBootstrapServers.isEmpty(), "Kafka bootstrap servers should not be empty");
    }
}