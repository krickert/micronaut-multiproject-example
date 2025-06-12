package com.krickert.search.orchestrator.kafka;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.orchestrator.kafka.producer.KafkaForwarder;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
// Explicitly configure the 'pipestream-forwarder' producer for Apicurio Protobuf
@Property(name = "kafka.producers.pipestream-forwarder.key.serializer", value = "org.apache.kafka.common.serialization.UUIDSerializer")
@Property(name = "kafka.producers.pipestream-forwarder.value.serializer", value = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.AUTO_REGISTER_ARTIFACT, value = "true")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
class KafkaForwarderIT {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaForwarderIT.class);
    private static final String TEST_TOPIC = "forwarder-test-topic";
    private static final String TEST_ERROR_TOPIC = "error-forwarder-test-topic";

    //TODO:
    @Inject
    KafkaForwarder kafkaForwarder;

    @Inject
    @Property(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    @Property(name = "apicurio.registry.url")
    String apicurioRegistryUrl;

    private KafkaConsumer<UUID, PipeStream> consumer;

    @BeforeEach
    void setUp() {
        // Create a consumer to verify messages are forwarded correctly
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "forwarder-test-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(SerdeConfig.REGISTRY_URL, apicurioRegistryUrl);
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");

        consumer = new KafkaConsumer<>(props);
        // Subscribe to both the regular topic and the error topic
        consumer.subscribe(List.of(TEST_TOPIC, TEST_ERROR_TOPIC));

        // Poll once to join the group
        consumer.poll(Duration.ofMillis(100));
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    @DisplayName("Should forward a PipeStream to Kafka")
    void testForwardToKafka() {
        // Create a test PipeStream
        String streamId = "test-stream-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-1").setTitle("Hello Kafka Forwarder").build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to topic: {}", streamId, TEST_TOPIC);
        // Forward the message to Kafka using Reactor
        kafkaForwarder.forwardToKafka(testPipeStream, TEST_TOPIC)
                .block(Duration.ofSeconds(10));

        LOG.info("Message sent to Kafka. Waiting to receive it...");

        // Poll for the message
        ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofSeconds(10));

        // Verify the message was received
        assertNotNull(records, "Records should not be null");
        assertEquals(1, records.count(), "Should receive exactly one record");

        // Get the received message
        ConsumerRecord<UUID, PipeStream> record = records.iterator().next();
        PipeStream receivedStream = record.value();

        // Verify the message content
        assertNotNull(receivedStream, "Received PipeStream should not be null");
        assertEquals(streamId, receivedStream.getStreamId(), "Stream ID should match");
        assertEquals("test-doc-1", receivedStream.getDocument().getId(), "Document ID should match");
        assertEquals("Hello Kafka Forwarder", receivedStream.getDocument().getTitle(), "Document title should match");
        assertEquals("test-pipeline", receivedStream.getCurrentPipelineName(), "Pipeline name should match");
        assertEquals("test-step", receivedStream.getTargetStepName(), "Step name should match");
        assertEquals(1, receivedStream.getCurrentHopNumber(), "Hop number should match");

        LOG.info("PipeStream (streamId: {}) successfully sent to Kafka and received.", streamId);
    }

    @Test
    @DisplayName("Should forward a PipeStream to error topic")
    void testForwardToErrorTopic() {
        // Create a test PipeStream
        String streamId = "error-stream-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("error-doc-1").setTitle("Hello Error Topic").build())
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to error topic: {}", streamId, TEST_ERROR_TOPIC);
        // Forward the message to the error topic using Reactor
        kafkaForwarder.forwardToErrorTopic(testPipeStream, TEST_TOPIC)
                .block(Duration.ofSeconds(10));

        LOG.info("Message sent to error topic. Waiting to receive it...");

        // Poll for the message
        ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofSeconds(10));

        // Verify the message was received
        assertNotNull(records, "Records should not be null");
        assertEquals(1, records.count(), "Should receive exactly one record");

        // Get the received message
        ConsumerRecord<UUID, PipeStream> record = records.iterator().next();
        PipeStream receivedStream = record.value();

        // Verify the message content
        assertNotNull(receivedStream, "Received PipeStream should not be null");
        assertEquals(streamId, receivedStream.getStreamId(), "Stream ID should match");
        assertEquals("error-doc-1", receivedStream.getDocument().getId(), "Document ID should match");
        assertEquals("Hello Error Topic", receivedStream.getDocument().getTitle(), "Document title should match");
        assertEquals("test-pipeline", receivedStream.getCurrentPipelineName(), "Pipeline name should match");
        assertEquals("test-step", receivedStream.getTargetStepName(), "Step name should match");
        assertEquals(1, receivedStream.getCurrentHopNumber(), "Hop number should match");

        LOG.info("PipeStream (streamId: {}) successfully sent to error topic and received.", streamId);
    }
}
