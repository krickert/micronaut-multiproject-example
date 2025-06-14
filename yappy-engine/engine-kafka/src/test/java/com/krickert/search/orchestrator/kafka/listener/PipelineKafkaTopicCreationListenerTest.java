package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.orchestrator.kafka.admin.PipelineKafkaTopicService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class PipelineKafkaTopicCreationListenerTest {

    @Inject
    ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;

    @Inject
    PipelineKafkaTopicCreationListener listener;

    @MockBean(PipelineKafkaTopicService.class)
    PipelineKafkaTopicService mockTopicService() {
        return mock(PipelineKafkaTopicService.class);
    }

    @Inject
    PipelineKafkaTopicService topicService;

    @BeforeEach
    void setUp() {
        reset(topicService);
        listener.clearCreatedTopicsCache();
        // Mock successful topic creation by default
        when(topicService.createAllTopicsAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void testTopicsCreatedForPipelineWithKafkaInputs() throws InterruptedException {
        // Create a pipeline configuration with Kafka inputs
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .consumerGroupId("test-group")
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("test-step")
                .processingModuleName("test-module")
                .kafkaInputs(List.of(kafkaInput))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("test-pipeline")
                .pipelineSteps(Map.of("test-step", step))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("test-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait for async processing
        Thread.sleep(500);

        // Verify that createAllTopicsAsync was called for the pipeline step
        verify(topicService, times(1)).createAllTopicsAsync("test-pipeline", "test-step");
        assertEquals(1, listener.getCreatedTopicsCount());
    }

    @Test
    void testTopicsCreatedForPipelineWithKafkaPublish() throws InterruptedException {
        // Create a pipeline configuration with Kafka publish topics
        KafkaPublishTopic publishTopic = KafkaPublishTopic.builder()
                .topic("output-topic")
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("publish-step")
                .processingModuleName("test-module")
                .kafkaPublishTopics(List.of(publishTopic))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("publish-pipeline")
                .pipelineSteps(Map.of("publish-step", step))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("publish-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait for async processing
        Thread.sleep(500);

        // Verify that createAllTopicsAsync was called for the pipeline step
        verify(topicService, times(1)).createAllTopicsAsync("publish-pipeline", "publish-step");
        assertEquals(1, listener.getCreatedTopicsCount());
    }

    @Test
    void testNoDuplicateTopicCreation() throws InterruptedException {
        // Create a simple pipeline configuration
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("cached-step")
                .processingModuleName("test-module")
                .kafkaInputs(List.of(kafkaInput))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("cached-pipeline")
                .pipelineSteps(Map.of("cached-step", step))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("cached-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);

        // Publish the event twice
        eventPublisher.publishEvent(event);
        Thread.sleep(500);
        eventPublisher.publishEvent(event);
        Thread.sleep(500);

        // Verify that createAllTopicsAsync was called only once despite two events
        verify(topicService, times(1)).createAllTopicsAsync("cached-pipeline", "cached-step");
        assertEquals(1, listener.getCreatedTopicsCount());
    }

    @Test
    void testMultiplePipelineSteps() throws InterruptedException {
        // Create a pipeline with multiple steps
        KafkaInputDefinition kafkaInput1 = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic-1"))
                .build();

        PipelineStepConfig step1 = PipelineStepConfig.builder()
                .stepName("step-1")
                .processingModuleName("module-1")
                .kafkaInputs(List.of(kafkaInput1))
                .build();

        KafkaInputDefinition kafkaInput2 = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic-2"))
                .build();

        PipelineStepConfig step2 = PipelineStepConfig.builder()
                .stepName("step-2")
                .processingModuleName("module-2")
                .kafkaInputs(List.of(kafkaInput2))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("multi-step-pipeline")
                .pipelineSteps(Map.of("step-1", step1, "step-2", step2))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("multi-step-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait for async processing
        Thread.sleep(500);

        // Verify that createAllTopicsAsync was called for both steps
        ArgumentCaptor<String> pipelineCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stepCaptor = ArgumentCaptor.forClass(String.class);
        verify(topicService, times(2)).createAllTopicsAsync(pipelineCaptor.capture(), stepCaptor.capture());

        List<String> capturedPipelines = pipelineCaptor.getAllValues();
        List<String> capturedSteps = stepCaptor.getAllValues();

        assertTrue(capturedPipelines.contains("multi-step-pipeline"));
        assertTrue(capturedSteps.contains("step-1"));
        assertTrue(capturedSteps.contains("step-2"));
        assertEquals(2, listener.getCreatedTopicsCount());
    }

    @Test
    void testIgnoresDifferentCluster() throws InterruptedException {
        // Create a pipeline configuration for a different cluster
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("different-cluster")
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("different-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait for async processing
        Thread.sleep(500);

        // Verify that no topics were created
        verify(topicService, never()).createAllTopicsAsync(anyString(), anyString());
        assertEquals(0, listener.getCreatedTopicsCount());
    }

    @Test
    void testHandlesDeletionEvent() throws InterruptedException {
        // First create some topics
        testTopicsCreatedForPipelineWithKafkaInputs();
        assertEquals(1, listener.getCreatedTopicsCount());

        // Now send a deletion event
        PipelineClusterConfigChangeEvent deletionEvent = new PipelineClusterConfigChangeEvent("test-cluster", null, true);
        eventPublisher.publishEvent(deletionEvent);

        // Wait for async processing
        Thread.sleep(500);

        // Verify cache was cleared
        assertEquals(0, listener.getCreatedTopicsCount());
    }

    @Test
    void testHandlesTopicCreationFailure() throws InterruptedException {
        // Mock topic creation failure
        when(topicService.createAllTopicsAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Topic creation failed")));

        // Create a pipeline configuration
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("failing-step")
                .processingModuleName("test-module")
                .kafkaInputs(List.of(kafkaInput))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("failing-pipeline")
                .pipelineSteps(Map.of("failing-step", step))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("failing-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait for async processing
        Thread.sleep(500);

        // Verify that createAllTopicsAsync was called but cache wasn't updated due to failure
        verify(topicService, times(1)).createAllTopicsAsync("failing-pipeline", "failing-step");
        assertEquals(0, listener.getCreatedTopicsCount());
    }
}