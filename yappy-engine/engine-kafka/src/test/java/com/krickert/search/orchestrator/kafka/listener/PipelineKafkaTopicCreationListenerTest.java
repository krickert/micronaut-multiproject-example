package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.orchestrator.kafka.admin.PipelineKafkaTopicService;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test"})
class PipelineKafkaTopicCreationListenerTest {

    // Trigger test resource startup
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;

    @Property(name = "consul.client.host")
    String consulHost;

    @Property(name = "consul.client.port")
    int consulPort;

    @Property(name = "app.config.cluster-name")
    String clusterName = "test-cluster";

    @Inject
    ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;

    @Inject
    PipelineKafkaTopicCreationListener listener;

    @Inject
    PipelineKafkaTopicService topicService;

    @BeforeEach
    void setUp() {
        listener.clearCreatedTopicsCache();
    }

    @Test
    void testTopicsCreatedForPipelineWithKafkaInputs() {
        // Create a pipeline configuration with Kafka inputs
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .consumerGroupId("test-group")
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("test-step")
                .stepType(StepType.PIPELINE)
                .description("Test step with Kafka input")
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-module", null))
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

        // Wait for async processing and verify topics were created
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertEquals(1, listener.getCreatedTopicsCount());
                    
                    // Verify actual Kafka topics exist
                    List<String> topics = topicService.listTopicsForStep("test-pipeline", "test-step");
                    assertFalse(topics.isEmpty(), "Topics should have been created for the pipeline step");
                    
                    // Verify expected topic names (only INPUT and DEAD_LETTER are created)
                    assertTrue(topics.contains("pipeline.test-pipeline.step.test-step.input"));
                    assertTrue(topics.contains("pipeline.test-pipeline.step.test-step.dead-letter"));
                });
    }

    @Test
    void testTopicsCreatedForPipelineWithKafkaPublish() {
        // Create a pipeline configuration with Kafka transport outputs
        KafkaTransportConfig kafkaTransport = new KafkaTransportConfig("output-topic", Map.of());
        
        PipelineStepConfig.OutputTarget kafkaOutput = new PipelineStepConfig.OutputTarget(
                "next-step", 
                TransportType.KAFKA, 
                null, // no gRPC transport
                kafkaTransport
        );

        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                "test-module", null
        );

        PipelineStepConfig step = new PipelineStepConfig(
                "publish-step",
                StepType.PIPELINE,
                "Test step with Kafka output",
                null, // no schema
                null, // no custom config
                List.of(), // no Kafka inputs  
                Map.of("kafka-output", kafkaOutput), // Kafka outputs
                0, 1000L, 30000L, 2.0, null, // retry config
                processorInfo
        );

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

        // Wait for async processing and verify topics were created
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertEquals(1, listener.getCreatedTopicsCount());
                    
                    // Verify actual Kafka topics exist
                    List<String> topics = topicService.listTopicsForStep("publish-pipeline", "publish-step");
                    assertFalse(topics.isEmpty(), "Topics should have been created for the pipeline step");
                    
                    // Verify expected topic names (only INPUT and DEAD_LETTER are created)
                    assertTrue(topics.contains("pipeline.publish-pipeline.step.publish-step.input"));
                    assertTrue(topics.contains("pipeline.publish-pipeline.step.publish-step.dead-letter"));
                });
    }

    @Test
    void testNoDuplicateTopicCreation() {
        // Create a simple pipeline configuration
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic"))
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("cached-step")
                .stepType(StepType.PIPELINE)
                .description("Test cached step with Kafka input")
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-module", null))
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
        eventPublisher.publishEvent(event);

        // Wait for async processing and verify topics were created only once
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    // Should only have one topic key in cache despite two events
                    assertEquals(1, listener.getCreatedTopicsCount());
                    
                    // Verify actual Kafka topics exist
                    List<String> topics = topicService.listTopicsForStep("cached-pipeline", "cached-step");
                    assertFalse(topics.isEmpty(), "Topics should have been created for the pipeline step");
                    assertEquals(2, topics.size(), "Should have 2 topic types (input, dead-letter)");
                });
    }

    @Test
    void testMultiplePipelineSteps() {
        // Create a pipeline with multiple steps
        KafkaInputDefinition kafkaInput1 = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic-1"))
                .build();

        PipelineStepConfig step1 = PipelineStepConfig.builder()
                .stepName("step-1")
                .stepType(StepType.PIPELINE)
                .description("Test step 1 with Kafka input")
                .processorInfo(new PipelineStepConfig.ProcessorInfo("module-1", null))
                .kafkaInputs(List.of(kafkaInput1))
                .build();

        KafkaInputDefinition kafkaInput2 = KafkaInputDefinition.builder()
                .listenTopics(List.of("input-topic-2"))
                .build();

        PipelineStepConfig step2 = PipelineStepConfig.builder()
                .stepName("step-2")
                .stepType(StepType.PIPELINE)
                .description("Test step 2 with Kafka input")
                .processorInfo(new PipelineStepConfig.ProcessorInfo("module-2", null))
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

        // Wait for async processing and verify topics were created for both steps
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertEquals(2, listener.getCreatedTopicsCount());
                    
                    // Verify actual Kafka topics exist for step-1
                    List<String> step1Topics = topicService.listTopicsForStep("multi-step-pipeline", "step-1");
                    assertFalse(step1Topics.isEmpty(), "Topics should have been created for step-1");
                    assertTrue(step1Topics.contains("pipeline.multi-step-pipeline.step.step-1.input"));
                    assertTrue(step1Topics.contains("pipeline.multi-step-pipeline.step.step-1.dead-letter"));
                    
                    // Verify actual Kafka topics exist for step-2
                    List<String> step2Topics = topicService.listTopicsForStep("multi-step-pipeline", "step-2");
                    assertFalse(step2Topics.isEmpty(), "Topics should have been created for step-2");
                    assertTrue(step2Topics.contains("pipeline.multi-step-pipeline.step.step-2.input"));
                    assertTrue(step2Topics.contains("pipeline.multi-step-pipeline.step.step-2.dead-letter"));
                });
    }

    @Test
    void testIgnoresDifferentCluster() {
        // Create a pipeline configuration for a different cluster
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("different-cluster")
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("different-cluster", clusterConfig);

        // Publish the event
        eventPublisher.publishEvent(event);

        // Wait a bit to ensure processing would have happened if it was going to
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify that no topics were created
        assertEquals(0, listener.getCreatedTopicsCount());
    }

    @Test
    void testHandlesDeletionEvent() {
        // First create some topics by sending a regular config event
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(List.of("deletion-test-topic"))
                .build();

        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName("deletion-test-step")
                .stepType(StepType.PIPELINE)
                .description("Test step for deletion")
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-module", null))
                .kafkaInputs(List.of(kafkaInput))
                .build();

        PipelineConfig pipeline = PipelineConfig.builder()
                .name("deletion-test-pipeline")
                .pipelineSteps(Map.of("deletion-test-step", step))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("deletion-test-pipeline", pipeline))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .build();

        PipelineClusterConfigChangeEvent event = new PipelineClusterConfigChangeEvent("test-cluster", clusterConfig);
        
        // Create the topics first
        eventPublisher.publishEvent(event);
        
        // Wait for topics to be created
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertEquals(1, listener.getCreatedTopicsCount());
                });

        // Now send a deletion event
        PipelineClusterConfigChangeEvent deletionEvent = new PipelineClusterConfigChangeEvent("test-cluster", null, true);
        eventPublisher.publishEvent(deletionEvent);

        // Wait for async processing and verify cache was cleared
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertEquals(0, listener.getCreatedTopicsCount());
                });
    }

    // Note: testHandlesTopicCreationFailure removed - it required mocking failures which goes against
    // our integration testing principle. Real failure testing would require complex scenarios like
    // shutting down Kafka mid-test, which is beyond the scope of this specific test.
    // Failure handling is better tested at the KafkaAdminService level with controlled conditions.
}