package com.krickert.search.test.kafka;

import com.krickert.search.test.kafka.registry.SchemaRegistryFactory;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for Kafka tests that use a schema registry.
 * This class sets up a Kafka container and provides configuration for tests.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractKafkaTest implements TestPropertyProvider {
    protected static final Logger log = LoggerFactory.getLogger(AbstractKafkaTest.class);

    // Use the vanilla Apache Kafka image
    @Container
    protected static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:latest")
    );

    @Inject
    protected SchemaRegistry schemaRegistry;

    @BeforeAll
    public static void setupKafka() {
        log.info("Setup Kafka container...");
        // Kafka setup is handled by the container
    }

    /**
     * Get the bootstrap servers for Kafka.
     * 
     * @return the bootstrap servers as a string
     */
    protected String getBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    @Override
    public @NonNull Map<String, String> getProperties() {
        // Create a local SchemaRegistry if injection hasn't happened yet
        SchemaRegistry registry = schemaRegistry;
        if (registry == null) {
            // Create a temporary ApplicationContext to get the SchemaRegistry
            ApplicationContext context = ApplicationContext.builder().build();
            try (context) {
                context.start();
                SchemaRegistryFactory factory = context.getBean(SchemaRegistryFactory.class);
                registry = factory.schemaRegistry("moto");
            }
        }

        // Ensure schema registry is started
        registry.start();

        String producerPrefix = "kafka.producers.default.";
        Map<String, String> props = new HashMap<>();

        // Basic Kafka producer configuration (non-AWS specific)
        props.put(producerPrefix + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(producerPrefix + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(producerPrefix + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, registry.getSerializerClass());

        // Basic Kafka consumer configuration (non-AWS specific)
        String consumerPrefix = "kafka.consumers.default.";
        props.put(consumerPrefix + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers());
        props.put(consumerPrefix + ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(consumerPrefix + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(consumerPrefix + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(consumerPrefix + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, registry.getDeserializerClass());

        // Add schema registry properties (including AWS-specific properties)
        props.putAll(registry.getProperties());

        return props;
    }
}
