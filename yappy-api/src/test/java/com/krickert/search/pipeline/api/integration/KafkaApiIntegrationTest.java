package com.krickert.search.pipeline.api.integration;

import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairRequest;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Kafka API endpoints.
 * Tests topic management functionality.
 */
@MicronautTest
public class KafkaApiIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaApiIntegrationTest.class);

    @Inject
    @Client("/api/v1")
    HttpClient client;

    @Test
    void testCreateTopicPair() {
        // Create request for topic pair
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "test-pipeline",
                "test-step",
                3,          // partitions
                (short) 1,  // replication factor
                604800000L, // 7 days retention
                true        // create DLQ
        );

        // Send request to create topic pair
        HttpRequest<KafkaTopicPairRequest> httpRequest = HttpRequest.POST("/kafka/topics", request);
        HttpResponse<KafkaTopicPairResponse> response = client.toBlocking().exchange(httpRequest, KafkaTopicPairResponse.class);

        // Verify response
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        KafkaTopicPairResponse body = response.body();
        assertTrue(body.success(), "Topic creation should be successful");
        assertEquals("test-pipeline", body.pipelineId());
        assertEquals("test-step", body.stepId());
        assertEquals("yappy.pipeline.test-pipeline.step.test-step.input", body.inputTopicName());
        assertEquals("yappy.pipeline.test-pipeline.step.test-step.dlq", body.dlqTopicName());
        assertEquals(3, body.partitions());
        assertEquals((short) 1, body.replicationFactor());
        assertNotNull(body.createdAt());
        assertNull(body.errorMessage());
        
        LOG.info("Successfully created topic pair: input={}, dlq={}", 
                body.inputTopicName(), body.dlqTopicName());
    }

    @Test
    void testCreateTopicPairWithDefaults() {
        // Create request with minimal parameters (using defaults)
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "default-pipeline",
                "default-step",
                null,  // will default to 3
                null,  // will default to 1
                null,  // will default to 7 days
                null   // will default to true
        );

        // Send request
        HttpRequest<KafkaTopicPairRequest> httpRequest = HttpRequest.POST("/kafka/topics", request);
        HttpResponse<KafkaTopicPairResponse> response = client.toBlocking().exchange(httpRequest, KafkaTopicPairResponse.class);

        // Verify defaults were applied
        assertEquals(HttpStatus.OK, response.getStatus());
        KafkaTopicPairResponse body = response.body();
        assertNotNull(body);
        assertEquals(3, body.partitions(), "Should use default partitions");
        assertEquals((short) 1, body.replicationFactor(), "Should use default replication factor");
        assertNotNull(body.dlqTopicName(), "Should create DLQ by default");
    }

    @Test
    void testCreateTopicPairWithoutDlq() {
        // Create request without DLQ
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "no-dlq-pipeline",
                "no-dlq-step",
                1,          // single partition
                (short) 1,
                null,
                false       // don't create DLQ
        );

        // Send request
        HttpRequest<KafkaTopicPairRequest> httpRequest = HttpRequest.POST("/kafka/topics", request);
        HttpResponse<KafkaTopicPairResponse> response = client.toBlocking().exchange(httpRequest, KafkaTopicPairResponse.class);

        // Verify no DLQ was created
        assertEquals(HttpStatus.OK, response.getStatus());
        KafkaTopicPairResponse body = response.body();
        assertNotNull(body);
        assertNotNull(body.inputTopicName());
        assertNull(body.dlqTopicName(), "DLQ should not be created");
    }

    @Test
    void testGeneratedTopicNames() {
        // Test that topic names follow the correct pattern
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "my-pipeline",
                "my-step",
                1, (short) 1, null, true
        );

        assertEquals("yappy.pipeline.my-pipeline.step.my-step.input", request.inputTopicName());
        assertEquals("yappy.pipeline.my-pipeline.step.my-step.dlq", request.dlqTopicName());
    }
}