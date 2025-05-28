package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminKafkaControllerTest {

    @Test
    void testListActiveKafkaConsumersWithConsumersPresent() {
        // Mock dependencies
        KafkaListenerManager kafkaListenerManager = mock(KafkaListenerManager.class);
        AdminKafkaController controller = new AdminKafkaController(kafkaListenerManager);

        // Prepare test data
        ConsumerStatus consumerStatus = new ConsumerStatus(
                "consumer-1",
                "pipeline-1",
                "step-1",
                "test-topic",
                "test-group",
                false,
                Instant.now()
        );
        Map<String, ConsumerStatus> consumerStatusesMap = Map.of("consumer-1", consumerStatus);
        when(kafkaListenerManager.getConsumerStatuses()).thenReturn(consumerStatusesMap);

        // Call method under test
        Collection<ConsumerStatus> result = controller.listActiveKafkaConsumers();

        // Assertions
        assertEquals(1, result.size());
        assertEquals(consumerStatus, result.iterator().next());
    }

    @Test
    void testListActiveKafkaConsumersWithNoConsumers() {
        // Mock dependencies
        KafkaListenerManager kafkaListenerManager = mock(KafkaListenerManager.class);
        AdminKafkaController controller = new AdminKafkaController(kafkaListenerManager);

        // Prepare test data
        when(kafkaListenerManager.getConsumerStatuses()).thenReturn(Collections.emptyMap());

        // Call method under test
        Collection<ConsumerStatus> result = controller.listActiveKafkaConsumers();

        // Assertions
        assertTrue(result.isEmpty());
    }

    @Test
    void testListActiveKafkaConsumersWithNullStatuses() {
        // Mock dependencies
        KafkaListenerManager kafkaListenerManager = mock(KafkaListenerManager.class);
        AdminKafkaController controller = new AdminKafkaController(kafkaListenerManager);

        // Prepare test data
        when(kafkaListenerManager.getConsumerStatuses()).thenReturn(null);

        // Call method under test
        Collection<ConsumerStatus> result = controller.listActiveKafkaConsumers();

        // Assertions
        assertTrue(result.isEmpty());
    }

    @Test
    void testListActiveKafkaConsumersOnException() {
        // Mock dependencies
        KafkaListenerManager kafkaListenerManager = mock(KafkaListenerManager.class);
        AdminKafkaController controller = new AdminKafkaController(kafkaListenerManager);

        // Prepare test data
        when(kafkaListenerManager.getConsumerStatuses()).thenThrow(new RuntimeException("Test exception"));

        // Call method under test
        Collection<ConsumerStatus> result = controller.listActiveKafkaConsumers();

        // Assertions
        assertTrue(result.isEmpty());
    }
}