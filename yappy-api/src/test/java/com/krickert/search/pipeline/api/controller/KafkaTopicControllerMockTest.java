package com.krickert.search.pipeline.api.controller;

import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import com.krickert.search.orchestrator.kafka.admin.KafkaTopicStatusService;
import com.krickert.search.orchestrator.kafka.admin.TopicOpts;
import com.krickert.search.orchestrator.kafka.admin.CleanupPolicy;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairRequest;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KafkaTopicController with mocked dependencies.
 */
@MicronautTest
public class KafkaTopicControllerMockTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicControllerMockTest.class);

    @Inject
    @Client("/")
    HttpClient client;

    @MockBean(KafkaAdminService.class)
    KafkaAdminService mockKafkaAdminService() {
        return mock(KafkaAdminService.class);
    }

    @MockBean(KafkaTopicStatusService.class)
    KafkaTopicStatusService mockTopicStatusService() {
        return mock(KafkaTopicStatusService.class);
    }

    @Inject
    KafkaAdminService kafkaAdminService;

    @Inject
    KafkaTopicStatusService topicStatusService;

    @Test
    void testCreateTopicPairWithMocks() {
        // Setup mocks
        when(kafkaAdminService.doesTopicExist(anyString())).thenReturn(false);
        
        // Create request
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "test-pipeline",
                "test-step",
                3,
                (short) 1,
                604800000L,
                true
        );

        // Send request
        HttpRequest<KafkaTopicPairRequest> httpRequest = HttpRequest.POST("/api/v1/kafka/topics", request);
        HttpResponse<KafkaTopicPairResponse> response = client.toBlocking().exchange(httpRequest, KafkaTopicPairResponse.class);

        // Verify response
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        
        KafkaTopicPairResponse body = response.body();
        assertTrue(body.success());
        assertEquals("test-pipeline", body.pipelineId());
        assertEquals("test-step", body.stepId());

        // Verify interactions
        ArgumentCaptor<TopicOpts> topicOptsCaptor = ArgumentCaptor.forClass(TopicOpts.class);
        ArgumentCaptor<String> topicNameCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(kafkaAdminService, times(2)).createTopic(topicOptsCaptor.capture(), topicNameCaptor.capture());
        
        List<String> capturedTopicNames = topicNameCaptor.getAllValues();
        assertTrue(capturedTopicNames.contains("yappy.pipeline.test-pipeline.step.test-step.input"));
        assertTrue(capturedTopicNames.contains("yappy.pipeline.test-pipeline.step.test-step.dlq"));
        
        TopicOpts capturedOpts = topicOptsCaptor.getValue();
        assertEquals(3, capturedOpts.partitions());
        assertEquals((short) 1, capturedOpts.replicationFactor());
        assertEquals(List.of(CleanupPolicy.DELETE), capturedOpts.cleanupPolicies());
        assertEquals(Optional.of(604800000L), capturedOpts.retentionMs());
        
        LOG.info("Test passed with mocked services");
    }

    @Test
    void testCreateTopicPairAlreadyExists() {
        // Setup mocks - topic already exists
        when(kafkaAdminService.doesTopicExist("yappy.pipeline.existing.step.step1.input")).thenReturn(true);
        
        // Create request
        KafkaTopicPairRequest request = new KafkaTopicPairRequest(
                "existing",
                "step1",
                1,
                (short) 1,
                null,
                true
        );

        // Send request
        HttpRequest<KafkaTopicPairRequest> httpRequest = HttpRequest.POST("/api/v1/kafka/topics", request);
        HttpResponse<KafkaTopicPairResponse> response = client.toBlocking().exchange(httpRequest, KafkaTopicPairResponse.class);

        // Should get 409 Conflict
        assertEquals(HttpStatus.CONFLICT, response.getStatus());
        assertNotNull(response.body());
        assertFalse(response.body().success());
        assertNotNull(response.body().errorMessage());
        assertTrue(response.body().errorMessage().contains("already exists"));
    }
}