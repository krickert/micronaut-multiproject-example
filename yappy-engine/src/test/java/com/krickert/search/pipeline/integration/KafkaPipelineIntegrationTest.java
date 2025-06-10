package com.krickert.search.pipeline.integration;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.admin.PipelineKafkaTopicService;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies Kafka pipeline functionality:
 * 1. Creates pipeline topics
 * 2. Sends PipeStream messages to Kafka
 * 3. Verifies messages can be consumed from Kafka
 * 
 * This test focuses on the Kafka infrastructure without requiring
 * actual pipeline processing or gRPC services.
 */
@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")

// Producer configuration
@Property(name = "kafka.producers.pipestream-forwarder.key.serializer", value = "org.apache.kafka.common.serialization.UUIDSerializer")
@Property(name = "kafka.producers.pipestream-forwarder.value.serializer", value = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.AUTO_REGISTER_ARTIFACT, value = "true")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
class KafkaPipelineIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaPipelineIntegrationTest.class);
    
    private static final String TEST_PIPELINE_NAME = "kafka-test-pipeline";
    private static final String TEST_STEP_NAME = "kafka-test-step";
    
    @Inject
    KafkaForwarder kafkaForwarder;
    
    @Inject
    PipelineKafkaTopicService pipelineKafkaTopicService;
    
    @Inject
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Inject
    @Property(name = "apicurio.registry.url")
    String apicurioRegistryUrl;
    
    private KafkaConsumer<UUID, PipeStream> testConsumer;
    private String outputTopic;
    private String errorTopic;

    @BeforeEach
    void setUp() {
        // Create topics for the test pipeline step
        pipelineKafkaTopicService.createAllTopics(TEST_PIPELINE_NAME, TEST_STEP_NAME);
        
        // Get topic names
        outputTopic = pipelineKafkaTopicService.generateTopicName(TEST_PIPELINE_NAME, TEST_STEP_NAME, PipelineKafkaTopicService.TopicType.OUTPUT);
        errorTopic = pipelineKafkaTopicService.generateTopicName(TEST_PIPELINE_NAME, TEST_STEP_NAME, PipelineKafkaTopicService.TopicType.ERROR);
        
        // Wait for topics to be created
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> pipelineKafkaTopicService.listTopicsForStep(TEST_PIPELINE_NAME, TEST_STEP_NAME).size() == 4);
        
        // Create a test consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        consumerProps.put(SerdeConfig.REGISTRY_URL, apicurioRegistryUrl);
        consumerProps.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        consumerProps.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        
        testConsumer = new KafkaConsumer<>(consumerProps);
        
        LOG.info("Test setup complete. Topics created: output={}, error={}", outputTopic, errorTopic);
    }
    
    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
        LOG.info("Test teardown complete");
    }
    
    @Test
    @DisplayName("Should forward PipeStream to Kafka output topic and consume it")
    void testForwardToOutputTopic() throws Exception {
        // Subscribe to output topic
        testConsumer.subscribe(Collections.singletonList(outputTopic));
        
        // Create test PipeStream
        String streamId = "test-stream-" + UUID.randomUUID();
        PipeStream testStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                    .setId("doc-1")
                    .setTitle("Test Document")
                    .build())
                .setCurrentPipelineName(TEST_PIPELINE_NAME)
                .setTargetStepName(TEST_STEP_NAME)
                .setCurrentHopNumber(1)
                .build();
        
        LOG.info("Sending PipeStream {} to output topic {}", streamId, outputTopic);
        
        // Send to Kafka
        kafkaForwarder.forwardToKafka(testStream, outputTopic).get(10, TimeUnit.SECONDS);
        
        // Consume and verify
        PipeStream received = null;
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        if (record.value().getStreamId().equals(streamId)) {
                            return true;
                        }
                    }
                    return false;
                });
        
        // Poll once more to get the message
        ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
        for (ConsumerRecord<UUID, PipeStream> record : records) {
            if (record.value().getStreamId().equals(streamId)) {
                received = record.value();
                break;
            }
        }
        
        assertNotNull(received, "Should have received the PipeStream from Kafka");
        assertEquals(streamId, received.getStreamId());
        assertEquals("doc-1", received.getDocument().getId());
        assertEquals("Test Document", received.getDocument().getTitle());
        assertEquals(TEST_PIPELINE_NAME, received.getCurrentPipelineName());
        assertEquals(TEST_STEP_NAME, received.getTargetStepName());
        
        LOG.info("Successfully sent and received PipeStream through Kafka");
    }
    
    @Test
    @DisplayName("Should forward PipeStream to Kafka error topic")
    void testForwardToErrorTopic() throws Exception {
        // Subscribe to error topic
        testConsumer.subscribe(Collections.singletonList(errorTopic));
        
        // Create test PipeStream with error information
        String streamId = "error-stream-" + UUID.randomUUID();
        PipeStream errorStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                    .setId("error-doc-1")
                    .setTitle("Document with Error")
                    .build())
                .setCurrentPipelineName(TEST_PIPELINE_NAME)
                .setTargetStepName(TEST_STEP_NAME)
                .setCurrentHopNumber(1)
                .build();
        
        LOG.info("Sending error PipeStream {} to error topic {}", streamId, errorTopic);
        
        // Send to error topic
        kafkaForwarder.forwardToErrorTopic(errorStream, errorTopic).get(10, TimeUnit.SECONDS);
        
        // Consume and verify
        PipeStream received = null;
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        if (record.value().getStreamId().equals(streamId)) {
                            return true;
                        }
                    }
                    return false;
                });
        
        // Poll once more to get the message
        ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
        for (ConsumerRecord<UUID, PipeStream> record : records) {
            if (record.value().getStreamId().equals(streamId)) {
                received = record.value();
                break;
            }
        }
        
        assertNotNull(received, "Should have received the error PipeStream from Kafka");
        assertEquals(streamId, received.getStreamId());
        assertEquals("error-doc-1", received.getDocument().getId());
        
        LOG.info("Successfully sent and received error PipeStream through Kafka");
    }
    
    @Test
    @DisplayName("Should handle multiple PipeStreams in parallel")
    void testMultiplePipeStreams() throws Exception {
        // Subscribe to output topic
        testConsumer.subscribe(Collections.singletonList(outputTopic));
        
        int numStreams = 10;
        List<String> streamIds = new ArrayList<>();
        
        // Send multiple PipeStreams
        for (int i = 0; i < numStreams; i++) {
            String streamId = "multi-stream-" + i;
            streamIds.add(streamId);
            
            PipeStream stream = PipeStream.newBuilder()
                    .setStreamId(streamId)
                    .setDocument(PipeDoc.newBuilder()
                        .setId("doc-" + i)
                        .setTitle("Document " + i)
                        .build())
                    .setCurrentPipelineName(TEST_PIPELINE_NAME)
                    .setTargetStepName(TEST_STEP_NAME)
                    .setCurrentHopNumber(i)
                    .build();
            
            kafkaForwarder.forwardToKafka(stream, outputTopic).get(10, TimeUnit.SECONDS);
        }
        
        LOG.info("Sent {} PipeStreams to Kafka", numStreams);
        
        // Collect received messages
        Set<String> receivedStreamIds = new HashSet<>();
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        receivedStreamIds.add(record.value().getStreamId());
                    }
                    return receivedStreamIds.size() == numStreams;
                });
        
        assertEquals(numStreams, receivedStreamIds.size());
        assertTrue(receivedStreamIds.containsAll(streamIds));
        
        LOG.info("Successfully sent and received {} PipeStreams through Kafka", numStreams);
    }
}