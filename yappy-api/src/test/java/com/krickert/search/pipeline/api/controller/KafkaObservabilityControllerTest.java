package com.krickert.search.pipeline.api.controller;

import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import com.krickert.search.orchestrator.kafka.admin.KafkaTopicStatusService;
import com.krickert.search.orchestrator.kafka.admin.model.KafkaTopicStatus;
import com.krickert.search.pipeline.api.dto.kafka.KafkaHealthResponse;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicStatusResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@MicronautTest
class KafkaObservabilityControllerTest {

    @Inject
    KafkaObservabilityController controller;
    
    @Inject
    KafkaTopicStatusService topicStatusService;
    
    @Inject
    KafkaAdminService adminService;

    @MockBean(KafkaTopicStatusService.class)
    KafkaTopicStatusService mockTopicStatusService() {
        return Mockito.mock(KafkaTopicStatusService.class);
    }

    @MockBean(KafkaAdminService.class)
    KafkaAdminService mockAdminService() {
        return Mockito.mock(KafkaAdminService.class);
    }

    @Test
    void testGetTopicStatus() {
        // Given
        String topicName = "test-topic";
        KafkaTopicStatus mockStatus = KafkaTopicStatus.builder()
            .topicName(topicName)
            .isHealthy(true)
            .healthStatus("HEALTHY")
            .lastCheckedTime(Instant.now())
            .partitionCount(3)
            .replicationFactor((short) 2)
            .partitionOffsets(Map.of(0, 100L, 1, 200L, 2, 150L))
            .largestOffset(200L)
            .consumerGroupId("test-group")
            .consumerGroupOffsets(Map.of(0, 90L, 1, 190L, 2, 140L))
            .lagPerPartition(Map.of(0, 10L, 1, 10L, 2, 10L))
            .totalLag(30L)
            .listenerStatus(KafkaTopicStatus.ListenerStatus.RECEIVING)
            .metrics(Map.of("messages.per.sec", 100.0))
            .build();

        when(topicStatusService.getTopicStatus(topicName)).thenReturn(mockStatus);

        // When
        HttpResponse<KafkaTopicStatusResponse> response = controller.getTopicStatus(topicName);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        KafkaTopicStatusResponse body = response.body();
        assertEquals(topicName, body.topicName());
        assertEquals("HEALTHY", body.healthStatus());
        assertEquals(3, body.partitionCount());
        assertEquals(2, body.replicationFactor());
        assertEquals(200L, body.largestOffset());
        assertEquals(30L, body.totalLag());
        assertEquals("RECEIVING", body.listenerStatus());
    }

    @Test
    void testGetKafkaHealth() {
        // Given
        when(adminService.getAvailableBrokerCount()).thenReturn(3);
        when(adminService.listTopics()).thenReturn(Set.of("topic1", "topic2", "topic3"));
        when(adminService.listConsumerGroupsAsync())
            .thenReturn(CompletableFuture.completedFuture(Set.of("group1", "group2")));

        // When
        HttpResponse<KafkaHealthResponse> response = controller.getKafkaHealth();

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        KafkaHealthResponse body = response.body();
        assertEquals("HEALTHY", body.status());
        assertEquals(3, body.availableBrokers());
        assertEquals(3L, body.totalTopics());
        assertEquals(2L, body.totalConsumerGroups());
        assertTrue(body.issues().isEmpty());
    }

    @Test
    void testGetKafkaHealthUnhealthy() {
        // Given
        when(adminService.getAvailableBrokerCount()).thenReturn(0);
        when(adminService.listTopics()).thenThrow(new RuntimeException("Connection failed"));

        // When
        HttpResponse<KafkaHealthResponse> response = controller.getKafkaHealth();

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus());
        assertNotNull(response.body());
        KafkaHealthResponse body = response.body();
        assertEquals("UNHEALTHY", body.status());
        assertEquals(0, body.availableBrokers());
        assertFalse(body.issues().isEmpty());
    }
}