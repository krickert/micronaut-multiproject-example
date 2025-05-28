package com.krickert.yappy.engine.core.controller.admin;

import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.yappy.engine.controller.admin.dto.KafkaConsumerActionRequest;
import com.krickert.yappy.engine.controller.admin.dto.KafkaConsumerActionResponse;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.annotation.SerdeImport;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@MicronautTest
@SerdeImport(ConsumerStatus.class)
@SerdeImport(KafkaConsumerActionResponse.class)
class AdminKafkaControllerFirstTest {

    // Serializable version of KafkaConsumerActionRequest for testing
    @Serdeable
    static class SerializableKafkaConsumerActionRequest extends KafkaConsumerActionRequest {
        public SerializableKafkaConsumerActionRequest() {
            super(null, null, null, null);
        }

        public SerializableKafkaConsumerActionRequest(String pipelineName, String stepName, String topic, String groupId) {
            super(pipelineName, stepName, topic, groupId);
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KafkaListenerManager mockKafkaListenerManager;

    @MockBean(KafkaListenerManager.class)
    KafkaListenerManager mockKafkaListenerManager() {
        return Mockito.mock(KafkaListenerManager.class);
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Manager returns statuses")
    void testListActiveKafkaConsumers_returnsStatuses() {
        ConsumerStatus status1 = new ConsumerStatus("id1", "pipelineA", "step1", "topicA", "groupA", false, Instant.now());
        ConsumerStatus status2 = new ConsumerStatus("id2", "pipelineB", "step2", "topicB", "groupB", true, Instant.now().minusSeconds(60));
        Map<String, ConsumerStatus> statusesMap = new HashMap<>();
        statusesMap.put("key1", status1);
        statusesMap.put("key2", status2);

        when(mockKafkaListenerManager.getConsumerStatuses()).thenReturn(statusesMap);

        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        assertEquals(2, responseCollection.size());
        assertTrue(responseCollection.stream().anyMatch(s -> "id1".equals(s.id())));
        assertTrue(responseCollection.stream().anyMatch(s -> "id2".equals(s.id())));
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Manager returns empty map")
    void testListActiveKafkaConsumers_emptyMap() {
        when(mockKafkaListenerManager.getConsumerStatuses()).thenReturn(Collections.emptyMap());

        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        assertTrue(responseCollection.isEmpty());
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Manager returns null")
    void testListActiveKafkaConsumers_nullMap() {
        when(mockKafkaListenerManager.getConsumerStatuses()).thenReturn(null);

        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        assertTrue(responseCollection.isEmpty()); // Controller should handle null and return empty list
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Manager throws exception")
    void testListActiveKafkaConsumers_managerThrowsException() {
        when(mockKafkaListenerManager.getConsumerStatuses()).thenThrow(new RuntimeException("Consul connection failed"));

        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        // The controller catches Exception and returns empty list
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        assertTrue(responseCollection.isEmpty());
    }

    // --- Tests for POST /api/kafka/consumers/pause ---

    @Test
    @DisplayName("POST /consumers/pause - Successful pause")
    void testPauseKafkaConsumer_success() {
        SerializableKafkaConsumerActionRequest actionRequest = new SerializableKafkaConsumerActionRequest("pipelineA", "step1", "topicA", "groupA");

        // KafkaListenerManager.pauseConsumer returns CompletableFuture<Void>
        CompletableFuture<Void> successFuture = CompletableFuture.completedFuture(null);
        when(mockKafkaListenerManager.pauseConsumer("pipelineA", "step1", "topicA", "groupA"))
            .thenReturn(successFuture);

        HttpRequest<SerializableKafkaConsumerActionRequest> request = HttpRequest.POST("/api/kafka/consumers/pause", actionRequest);
        var response = client.toBlocking().exchange(request, KafkaConsumerActionResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
        KafkaConsumerActionResponse body = response.body();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertEquals("Consumer paused successfully.", body.getMessage());
    }

    @Test
    @DisplayName("POST /consumers/pause - Consumer not found / error pausing")
    void testPauseKafkaConsumer_notFoundOrError() {
        SerializableKafkaConsumerActionRequest actionRequest = new SerializableKafkaConsumerActionRequest("pipelineX", "stepX", "topicX", "groupX");

        CompletableFuture<Void> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new IllegalArgumentException("Consumer not found for specified identifiers."));

        when(mockKafkaListenerManager.pauseConsumer("pipelineX", "stepX", "topicX", "groupX"))
            .thenReturn(failedFuture);

        HttpRequest<SerializableKafkaConsumerActionRequest> request = HttpRequest.POST("/api/kafka/consumers/pause", actionRequest);
        var response = client.toBlocking().exchange(request, KafkaConsumerActionResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode()); // Controller handles exception and returns 200 with success=false
        KafkaConsumerActionResponse body = response.body();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertTrue(body.getMessage().contains("Failed to pause consumer: Consumer not found for specified identifiers."));
    }

    @Test
    @DisplayName("POST /consumers/pause - Invalid request (blank topic)")
    void testPauseKafkaConsumer_invalidRequest() {
        SerializableKafkaConsumerActionRequest actionRequest = new SerializableKafkaConsumerActionRequest("pipelineA", "step1", "", "groupA"); // Blank topic

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/kafka/consumers/pause", actionRequest), KafkaConsumerActionResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }
}
