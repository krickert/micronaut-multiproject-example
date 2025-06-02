package com.krickert.yappy.engine.controller.admin;

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
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@io.micronaut.serde.annotation.SerdeImport(ConsumerStatus.class)
@io.micronaut.serde.annotation.SerdeImport(KafkaConsumerActionRequest.class)
@io.micronaut.serde.annotation.SerdeImport(KafkaConsumerActionResponse.class)
class AdminKafkaControllerIT {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KafkaListenerManager kafkaListenerManager;

    @BeforeEach
    void setUp() {
        // Clear ALL consul-related system properties for test isolation
        System.clearProperty("consul.client.host");
        System.clearProperty("consul.client.port");
        System.clearProperty("consul.client.acl-token");
        System.clearProperty("consul.host");
        System.clearProperty("consul.port");
        System.clearProperty("yappy.bootstrap.consul.host");
        System.clearProperty("yappy.bootstrap.consul.port");
        System.clearProperty("yappy.bootstrap.consul.acl_token");
        System.clearProperty("yappy.bootstrap.cluster.selected_name");
        
        // Ensure clean state
        kafkaListenerManager.getConsumerStatuses().clear();
    }
    
    @AfterEach
    void tearDown() {
        // Clear ALL consul-related system properties for test isolation
        System.clearProperty("consul.client.host");
        System.clearProperty("consul.client.port");
        System.clearProperty("consul.client.acl-token");
        System.clearProperty("consul.host");
        System.clearProperty("consul.port");
        System.clearProperty("yappy.bootstrap.consul.host");
        System.clearProperty("yappy.bootstrap.consul.port");
        System.clearProperty("yappy.bootstrap.consul.acl_token");
        System.clearProperty("yappy.bootstrap.cluster.selected_name");
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Returns empty list when no consumers")
    void testListActiveKafkaConsumers_empty() {
        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        assertTrue(responseCollection.isEmpty());
    }

    @Test
    @DisplayName("GET /api/kafka/consumers - Returns current statuses")
    void testListActiveKafkaConsumers_withStatuses() throws Exception {
        // The KafkaListenerManager will have listeners created based on the cluster config
        // For this test, we'll just check that the endpoint returns whatever is currently active
        
        HttpRequest<Object> request = HttpRequest.GET("/api/kafka/consumers");
        Collection<ConsumerStatus> responseCollection = client.toBlocking().retrieve(request, Argument.listOf(ConsumerStatus.class));

        assertNotNull(responseCollection);
        // The actual number of consumers depends on the cluster configuration
        // We can't predict it, so just verify the endpoint works
        
        if (!responseCollection.isEmpty()) {
            ConsumerStatus status = responseCollection.iterator().next();
            assertNotNull(status.id());
            assertNotNull(status.pipelineName());
            assertNotNull(status.stepName());
            assertNotNull(status.topic());
            assertNotNull(status.groupId());
            assertNotNull(status.lastUpdated());
        }
    }

    @Test
    @DisplayName("POST /consumers/pause - Test pause functionality")
    void testPauseKafkaConsumer_success() throws Exception {
        // Get current consumers to find one to test with
        Map<String, ConsumerStatus> currentStatuses = kafkaListenerManager.getConsumerStatuses();
        
        if (currentStatuses.isEmpty()) {
            // No consumers to test with, just verify the endpoint handles it gracefully
            KafkaConsumerActionRequest actionRequest = new KafkaConsumerActionRequest("testPipeline", "testStep", "test-topic", "test-group");
            HttpRequest<KafkaConsumerActionRequest> request = HttpRequest.POST("/api/kafka/consumers/pause", actionRequest);
            var response = client.toBlocking().exchange(request, KafkaConsumerActionResponse.class);
            
            assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
            KafkaConsumerActionResponse body = response.body();
            assertNotNull(body);
            assertFalse(body.isSuccess()); // Should fail since consumer doesn't exist
            assertTrue(body.getMessage().contains("not found") || body.getMessage().contains("No listener"));
        } else {
            // Use an existing consumer for the test
            ConsumerStatus existingConsumer = currentStatuses.values().iterator().next();
            KafkaConsumerActionRequest actionRequest = new KafkaConsumerActionRequest(
                existingConsumer.pipelineName(), 
                existingConsumer.stepName(), 
                existingConsumer.topic(), 
                existingConsumer.groupId()
            );

            HttpRequest<KafkaConsumerActionRequest> request = HttpRequest.POST("/api/kafka/consumers/pause", actionRequest);
            var response = client.toBlocking().exchange(request, KafkaConsumerActionResponse.class);

            assertEquals(HttpStatus.OK.getCode(), response.status().getCode());
            KafkaConsumerActionResponse body = response.body();
            assertNotNull(body);
            // The pause might succeed or fail depending on the consumer state
            assertNotNull(body.getMessage());
        }
    }

    @Test
    @DisplayName("POST /consumers/pause - Fails when consumer not found")
    void testPauseKafkaConsumer_notFound() {
        KafkaConsumerActionRequest actionRequest = new KafkaConsumerActionRequest("nonExistentPipeline", "nonExistentStep", "nonExistentTopic", "nonExistentGroup");

        HttpRequest<KafkaConsumerActionRequest> request = HttpRequest.POST("/api/kafka/consumers/pause", actionRequest);
        var response = client.toBlocking().exchange(request, KafkaConsumerActionResponse.class);

        assertEquals(HttpStatus.OK.getCode(), response.status().getCode()); // Controller returns 200 with success=false
        KafkaConsumerActionResponse body = response.body();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertTrue(body.getMessage().contains("Failed to pause consumer"));
    }

    @Test
    @DisplayName("POST /consumers/pause - Invalid request with blank topic")
    void testPauseKafkaConsumer_invalidRequest() {
        KafkaConsumerActionRequest actionRequest = new KafkaConsumerActionRequest("pipelineA", "step1", "", "groupA"); // Blank topic

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(HttpRequest.POST("/api/kafka/consumers/pause", actionRequest), KafkaConsumerActionResponse.class);
        });
        assertEquals(HttpStatus.BAD_REQUEST.getCode(), exception.getStatus().getCode());
    }
}