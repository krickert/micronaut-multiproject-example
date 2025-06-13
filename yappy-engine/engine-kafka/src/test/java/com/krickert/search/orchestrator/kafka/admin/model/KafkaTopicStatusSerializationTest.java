package com.krickert.search.orchestrator.kafka.admin.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for serializing and deserializing KafkaTopicStatus to/from JSON.
 */
public class KafkaTopicStatusSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    public void testSerializeDeserialize() throws Exception {
        // Create a sample KafkaTopicStatus
        Map<Integer, Long> partitionOffsets = new HashMap<>();
        partitionOffsets.put(0, 100L);
        partitionOffsets.put(1, 200L);
        partitionOffsets.put(2, 300L);

        Map<Integer, Long> consumerGroupOffsets = new HashMap<>();
        consumerGroupOffsets.put(0, 90L);
        consumerGroupOffsets.put(1, 180L);
        consumerGroupOffsets.put(2, 270L);

        Map<Integer, Long> lagPerPartition = new HashMap<>();
        lagPerPartition.put(0, 10L);
        lagPerPartition.put(1, 20L);
        lagPerPartition.put(2, 30L);

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("messages_per_second", 10.0);
        metrics.put("bytes_per_second", 1024.0);
        metrics.put("consumer_lag_rate", 0.5);

        Instant now = Instant.now();

        KafkaTopicStatus status = KafkaTopicStatus.builder()
                .topicName("test-topic")
                .isHealthy(true)
                .healthStatus("OK")
                .lastCheckedTime(now)
                .partitionCount(3)
                .replicationFactor((short) 3)
                .partitionOffsets(partitionOffsets)
                .largestOffset(300L)
                .consumerGroupId("test-consumer-group")
                .consumerGroupOffsets(consumerGroupOffsets)
                .lagPerPartition(lagPerPartition)
                .totalLag(60L)
                .listenerStatus(KafkaTopicStatus.ListenerStatus.RECEIVING)
                .metrics(metrics)
                .build();

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(status);

        // Print the JSON for debugging
        System.out.println("Serialized JSON: " + json);

        // Verify JSON contains expected fields
        assertTrue(json.contains("\"topicName\":\"test-topic\""));
        assertTrue(json.contains("\"isHealthy\":true"));
        assertTrue(json.contains("\"healthStatus\":\"OK\""));
        assertTrue(json.contains("\"partitionCount\":3"));
        assertTrue(json.contains("\"replicationFactor\":3"));
        assertTrue(json.contains("\"largestOffset\":300"));
        assertTrue(json.contains("\"consumerGroupId\":\"test-consumer-group\""));
        assertTrue(json.contains("\"totalLag\":60"));
        assertTrue(json.contains("\"listenerStatus\":\"RECEIVING\""));

        // Deserialize back to KafkaTopicStatus
        KafkaTopicStatus deserialized = objectMapper.readValue(json, KafkaTopicStatus.class);

        // Verify deserialized object matches original
        assertEquals(status.topicName(), deserialized.topicName());
        assertEquals(status.isHealthy(), deserialized.isHealthy());
        assertEquals(status.healthStatus(), deserialized.healthStatus());
        assertEquals(status.lastCheckedTime(), deserialized.lastCheckedTime());
        assertEquals(status.partitionCount(), deserialized.partitionCount());
        assertEquals(status.replicationFactor(), deserialized.replicationFactor());
        assertEquals(status.partitionOffsets(), deserialized.partitionOffsets());
        assertEquals(status.largestOffset(), deserialized.largestOffset());
        assertEquals(status.consumerGroupId(), deserialized.consumerGroupId());
        assertEquals(status.consumerGroupOffsets(), deserialized.consumerGroupOffsets());
        assertEquals(status.lagPerPartition(), deserialized.lagPerPartition());
        assertEquals(status.totalLag(), deserialized.totalLag());
        assertEquals(status.listenerStatus(), deserialized.listenerStatus());
        assertEquals(status.metrics(), deserialized.metrics());
    }

    @Test
    public void testDeserializeFromJson() throws Exception {
        // Sample JSON representing a KafkaTopicStatus
        String json = """
        {
            "topicName": "test-topic",
            "isHealthy": true,
            "healthStatus": "OK",
            "lastCheckedTime": "2023-06-01T12:00:00Z",
            "partitionCount": 3,
            "replicationFactor": 3,
            "partitionOffsets": {
                "0": 100,
                "1": 200,
                "2": 300
            },
            "largestOffset": 300,
            "consumerGroupId": "test-consumer-group",
            "consumerGroupOffsets": {
                "0": 90,
                "1": 180,
                "2": 270
            },
            "lagPerPartition": {
                "0": 10,
                "1": 20,
                "2": 30
            },
            "totalLag": 60,
            "listenerStatus": "RECEIVING",
            "metrics": {
                "messages_per_second": 10.0,
                "bytes_per_second": 1024.0,
                "consumer_lag_rate": 0.5
            }
        }
        """;

        // Deserialize from JSON
        KafkaTopicStatus status = objectMapper.readValue(json, KafkaTopicStatus.class);

        // Verify deserialized object has expected values
        assertEquals("test-topic", status.topicName());
        assertTrue(status.isHealthy());
        assertEquals("OK", status.healthStatus());
        assertEquals(Instant.parse("2023-06-01T12:00:00Z"), status.lastCheckedTime());
        assertEquals(3, status.partitionCount());
        assertEquals((short) 3, status.replicationFactor());
        assertEquals(3, status.partitionOffsets().size());
        assertEquals(100L, status.partitionOffsets().get(0));
        assertEquals(200L, status.partitionOffsets().get(1));
        assertEquals(300L, status.partitionOffsets().get(2));
        assertEquals(300L, status.largestOffset());
        assertEquals("test-consumer-group", status.consumerGroupId());
        assertEquals(3, status.consumerGroupOffsets().size());
        assertEquals(90L, status.consumerGroupOffsets().get(0));
        assertEquals(180L, status.consumerGroupOffsets().get(1));
        assertEquals(270L, status.consumerGroupOffsets().get(2));
        assertEquals(3, status.lagPerPartition().size());
        assertEquals(10L, status.lagPerPartition().get(0));
        assertEquals(20L, status.lagPerPartition().get(1));
        assertEquals(30L, status.lagPerPartition().get(2));
        assertEquals(60L, status.totalLag());
        assertEquals(KafkaTopicStatus.ListenerStatus.RECEIVING, status.listenerStatus());
        assertEquals(3, status.metrics().size());
        assertEquals(10.0, status.metrics().get("messages_per_second"));
        assertEquals(1024.0, status.metrics().get("bytes_per_second"));
        assertEquals(0.5, status.metrics().get("consumer_lag_rate"));
    }

    @Test
    public void testNullFields() throws Exception {
        // Create a minimal KafkaTopicStatus with some null fields
        KafkaTopicStatus status = KafkaTopicStatus.builder()
                .topicName("test-topic")
                .isHealthy(true)
                .healthStatus("OK")
                .lastCheckedTime(Instant.now())
                .partitionCount(3)
                .replicationFactor((short) 3)
                .build();

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(status);

        // Print the JSON for debugging
        System.out.println("Minimal JSON: " + json);

        // Verify JSON contains expected fields and doesn't contain null fields
        assertTrue(json.contains("\"topicName\":\"test-topic\""));
        assertTrue(json.contains("\"isHealthy\":true"));
        assertTrue(json.contains("\"healthStatus\":\"OK\""));
        assertTrue(json.contains("\"partitionCount\":3"));
        assertTrue(json.contains("\"replicationFactor\":3"));

        // Deserialize back to KafkaTopicStatus
        KafkaTopicStatus deserialized = objectMapper.readValue(json, KafkaTopicStatus.class);

        // Verify deserialized object matches original
        assertEquals(status.topicName(), deserialized.topicName());
        assertEquals(status.isHealthy(), deserialized.isHealthy());
        assertEquals(status.healthStatus(), deserialized.healthStatus());
        assertEquals(status.lastCheckedTime(), deserialized.lastCheckedTime());
        assertEquals(status.partitionCount(), deserialized.partitionCount());
        assertEquals(status.replicationFactor(), deserialized.replicationFactor());

        // Verify null fields are still null
        assertNull(deserialized.partitionOffsets());
        assertEquals(0L, deserialized.largestOffset());
        assertNull(deserialized.consumerGroupId());
        assertNull(deserialized.consumerGroupOffsets());
        assertNull(deserialized.lagPerPartition());
        assertEquals(0L, deserialized.totalLag());
        // The listenerStatus field has a default value of UNKNOWN in the Builder
        assertEquals(KafkaTopicStatus.ListenerStatus.UNKNOWN, deserialized.listenerStatus());
        assertNull(deserialized.metrics());
    }
}
