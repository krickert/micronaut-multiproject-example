package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.Route;
import com.krickert.search.model.RouteType;
import com.krickert.search.pipeline.kafka.admin.CleanupPolicy;
import com.krickert.search.pipeline.kafka.admin.KafkaAdminService;
import com.krickert.search.pipeline.kafka.admin.TopicOpts;
import com.krickert.search.pipeline.protobuf.PipeDocExample;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for KafkaForwarder.
 * This test verifies that the KafkaForwarder can forward a PipeStream to a Kafka topic
 * and that the message can be received by a Kafka listener.
 */
@MicronautTest(environments = "apicurio-test")
@Property(name = "test.topic", value = "kafka-forwarder-test-topic")
public class KafkaForwarderIT {
    private static final Logger log = LoggerFactory.getLogger(KafkaForwarderIT.class);

    @Value("${test.topic}")
    private String testTopic;

    @Inject
    private KafkaForwarder kafkaForwarder;

    @Inject
    private KafkaAdminService kafkaAdminService;

    @Inject
    private TestKafkaListener testKafkaListener;

    /**
     * Create the test topic before running the test.
     */
    @BeforeEach
    void createTestTopic() {
        log.info("Creating test topic: {}", testTopic);
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        // Check if the topic exists first
        if (kafkaAdminService.doesTopicExist(testTopic)) {
            log.info("Topic {} already exists, recreating it", testTopic);
            kafkaAdminService.recreateTopic(opts, testTopic);
        } else {
            log.info("Topic {} does not exist, creating it", testTopic);
            kafkaAdminService.createTopic(opts, testTopic);
        }
    }

    /**
     * Delete the test topic after the test.
     */
    @AfterEach
    void deleteTestTopic() {
        log.info("Deleting test topic: {}", testTopic);
        kafkaAdminService.deleteTopic(testTopic);
    }

    @Test
    void testForwardToKafka() {
        // Create a PipeStream using PipeDocExample
        PipeStream pipeStream = PipeDocExample.createFullPipeStream();

        // Create a Route for the test topic
        Route route = Route.newBuilder()
                .setRouteType(RouteType.KAFKA)
                .setDestination(testTopic)
                .build();

        log.info("Forwarding PipeStream to topic: {}", testTopic);

        // Forward the PipeStream to the test topic
        kafkaForwarder.forwardToKafka(pipeStream, route);

        // Wait for the message to be received by the listener
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> testKafkaListener.isMessageReceived());

        // Verify that the message was received
        assertTrue(testKafkaListener.isMessageReceived(), "Message should have been received");

        // Verify that the received message is the same as the sent message
        PipeStream receivedPipeStream = testKafkaListener.getReceivedMessages().get(0);
        assertEquals(pipeStream.getStreamId(), receivedPipeStream.getStreamId(), "Stream ID should match");
        assertEquals(pipeStream.getPipelineName(), receivedPipeStream.getPipelineName(), "Pipeline name should match");
    }

    /**
     * Test Kafka listener to receive messages from the test topic.
     */
    @Singleton
    @KafkaListener(groupId = "kafka-forwarder-test-group")
    public static class TestKafkaListener {
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private final AtomicBoolean messageReceived = new AtomicBoolean(false);

        @Topic("${test.topic}")
        public void receive(ConsumerRecord<UUID, PipeStream> record) {
            log.info("Received message from topic: {}", record.topic());
            receivedMessages.add(record.value());
            messageReceived.set(true);
        }

        public boolean isMessageReceived() {
            return messageReceived.get();
        }

        public List<PipeStream> getReceivedMessages() {
            return receivedMessages;
        }
    }
}
