package com.krickert.search.pipeline.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerState;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStateManager;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = DynamicListenerIntegrationTestIT.TEST_CLUSTER_NAME)

// Explicitly configure the 'pipestream-forwarder' producer for Apicurio Protobuf
@Property(name = "kafka.producers.pipestream-forwarder.key.serializer", value = "org.apache.kafka.common.serialization.UUIDSerializer")
@Property(name = "kafka.producers.pipestream-forwarder.value.serializer", value = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.AUTO_REGISTER_ARTIFACT, value = "true")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")

@Property(name = "kafka.consumers.default." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.consumers.default." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, value = "com.krickert.search.model.PipeStream")
@Property(name = "kafka.consumers.default." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
@Property(name = "kafka.consumers.default.bootstrap.servers", value = "${kafka.bootstrap.servers}") // Ensure this is present for default consumer config path
class DynamicListenerIntegrationTestIT {

    static final String TEST_CLUSTER_NAME = "dynamicListenerTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(DynamicListenerIntegrationTestIT.class);
    private static final String TEST_PIPELINE_NAME = "dynamic-listener-test-pipeline";
    private static final String TEST_STEP_NAME = "dynamic-listener-test-step";

    // Use different topic and group names for each test to avoid interference between test runs
    private String testInputTopic;
    private String testConsumerGroupId;

    @Inject
    KafkaForwarder kafkaForwarder;

    @Inject
    KafkaListenerManager kafkaListenerManager;

    @Inject
    ConsumerStateManager consumerStateManager;

    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DynamicConfigurationManager dynamicConfigurationManager;

    @Inject
    PipeStreamEngine mockPipeStreamEngine; // Injected mock

    @MockBean(PipeStreamEngine.class)
    PipeStreamEngine mockPipeStreamEngineFactoryMethod() {
        return Mockito.mock(PipeStreamEngine.class);
    }

    @BeforeEach
    void setUp() {
        // Generate unique topic and group names for this test run to avoid interference between tests
        // This is critical for Kafka integration tests to ensure messages from one test don't affect another
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        testInputTopic = "dynamic-listener-test-input-" + uniqueId;
        testConsumerGroupId = "dynamic-listener-test-group-" + uniqueId;
        LOG.info("Using unique topic: {} and group: {}", testInputTopic, testConsumerGroupId);

        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();

        PipelineStepConfig.ProcessorInfo dummyProcessorInfo = new PipelineStepConfig.ProcessorInfo("dummy-service", null);
        PipelineStepConfig kafkaInputStep = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(dummyProcessorInfo)
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of(testInputTopic))
                                .consumerGroupId(testConsumerGroupId)
                                .kafkaConsumerProperties(Collections.emptyMap()) // Ensure non-null
                                .build()
                ))
                .outputs(Collections.emptyMap())
                .build();

        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE_NAME)
                .pipelineSteps(Map.of(TEST_STEP_NAME, kafkaInputStep))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE_NAME, testPipeline))
                .build();

        PipelineModuleConfiguration dummyModuleConfig = PipelineModuleConfiguration.builder()
                .implementationId("dummy-service")
                .implementationName("Dummy Service for Kafka Test")
                .build();

        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(Map.of("dummy-service", dummyModuleConfig))
                .build();

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Collections.singleton(testInputTopic))
                .allowedGrpcServices(Collections.singleton("dummy-service"))
                .build();

        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        dynamicConfigurationManager.initialize(TEST_CLUSTER_NAME);

        LOG.info("Waiting for DynamicConfigurationManager to load config using Awaitility...");
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    boolean loaded = currentConfig.isPresent() && currentConfig.get().clusterName().equals(TEST_CLUSTER_NAME);
                    if (loaded) {
                        LOG.info("DynamicConfigurationManager loaded config for cluster: {}", TEST_CLUSTER_NAME);
                    }
                    return loaded;
                });
        assertNotNull(dynamicConfigurationManager.getCurrentPipelineClusterConfig().orElse(null), "DCM should have loaded the test cluster config");
    }

    @AfterEach
    void tearDown() {
        // Clean up any listeners that were created
        kafkaListenerManager.removeListener(TEST_PIPELINE_NAME, TEST_STEP_NAME);

        // Clean up Consul configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        dynamicConfigurationManager.shutdown();

        LOG.info("Test teardown complete.");
    }

    @Test
    @DisplayName("Should create a dynamic listener and receive messages")
    void testCreateDynamicListener() {
        // Create a listener for the pipeline
        LOG.info("Creating Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        List<String> createdListeners = kafkaListenerManager.createListenersForPipeline(TEST_PIPELINE_NAME);

        // Verify listener was created
        assertFalse(createdListeners.isEmpty(), "At least one listener should be created");

        // Allow time for listener to subscribe
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Send a message to the topic
        String streamId = "test-stream-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-1").setTitle("Hello Dynamic Listener").build())
                .setCurrentPipelineName("source-pipeline")
                .setTargetStepName("source-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to topic: {}", streamId, testInputTopic);
        try {
            kafkaForwarder.forwardToKafka(testPipeStream, testInputTopic).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Kafka send was interrupted", e);
        } catch (ExecutionException e) {
            LOG.error("Kafka send ExecutionException: ", e.getCause() != null ? e.getCause() : e);
            fail("Kafka send failed with an execution exception", e);
        } catch (TimeoutException e) {
            fail("Kafka send timed out", e);
        }

        LOG.info("Message sent to Kafka. Waiting for listener to receive and call PipeStreamEngine...");

        // Verify the message was received and processed
        ArgumentCaptor<PipeStream> receivedStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        verify(mockPipeStreamEngine, timeout(15000).times(1)).processStream(receivedStreamCaptor.capture());

        PipeStream receivedStream = receivedStreamCaptor.getValue();
        assertNotNull(receivedStream, "PipeStreamEngine should have been called with a non-null PipeStream");
        assertEquals(testPipeStream.getStreamId(), receivedStream.getStreamId(), "Stream ID should be preserved");
        assertEquals(testPipeStream.getDocument(), receivedStream.getDocument(), "Document should be preserved");
        assertEquals(TEST_PIPELINE_NAME, receivedStream.getCurrentPipelineName(), "Received stream should have pipeline name set by listener");
        assertEquals(TEST_STEP_NAME, receivedStream.getTargetStepName(), "Received stream should have step name set by listener");
        assertEquals(testPipeStream.getCurrentHopNumber(), receivedStream.getCurrentHopNumber(), "Received stream should have original hop number from Kafka message");

        LOG.info("PipeStream (streamId: {}) successfully sent to Kafka and received by listener.", streamId);
    }

    @Test
    @DisplayName("Should pause and resume a dynamic listener")
    void testPauseAndResumeDynamicListener() {
        // Create a listener for the pipeline
        LOG.info("Creating Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        List<String> createdListeners = kafkaListenerManager.createListenersForPipeline(TEST_PIPELINE_NAME);

        // Verify listener was created
        assertFalse(createdListeners.isEmpty(), "At least one listener should be created");

        // Allow time for listener to subscribe
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Pause the listener
        LOG.info("Pausing Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        try {
            kafkaListenerManager.pauseConsumer(TEST_PIPELINE_NAME, TEST_STEP_NAME).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to pause consumer", e);
        }

        // Wait a bit to ensure the pause takes effect
        LOG.info("Waiting for pause to take effect...");
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the listener is paused
        String listenerId = createdListeners.get(0);
        ConsumerState state = consumerStateManager.getState(listenerId);
        assertNotNull(state, "Consumer state should exist");
        assertTrue(state.paused(), "Consumer should be paused");
        LOG.info("Verified that listener is paused");

        // Send a message while paused (it should not be processed)
        String streamId = "test-stream-paused-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-paused").setTitle("Hello Paused Listener").build())
                .setCurrentPipelineName("source-pipeline")
                .setTargetStepName("source-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to topic while listener is paused: {}", streamId, testInputTopic);
        try {
            kafkaForwarder.forwardToKafka(testPipeStream, testInputTopic).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to send message to Kafka", e);
        }

        // Wait a bit to ensure the message is not processed
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the message was not processed (no calls to PipeStreamEngine)
        verify(mockPipeStreamEngine, timeout(1000).times(0)).processStream(Mockito.argThat(
                stream -> stream.getStreamId().equals(streamId)));

        // Resume the listener
        LOG.info("Resuming Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        try {
            kafkaListenerManager.resumeConsumer(TEST_PIPELINE_NAME, TEST_STEP_NAME).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to resume consumer", e);
        }

        // Verify the listener is resumed
        state = consumerStateManager.getState(listenerId);
        assertNotNull(state, "Consumer state should exist");
        assertFalse(state.paused(), "Consumer should be resumed");

        // Wait for the paused message to be processed now that the listener is resumed
        ArgumentCaptor<PipeStream> receivedStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        verify(mockPipeStreamEngine, timeout(15000).times(1)).processStream(receivedStreamCaptor.capture());

        // Verify the message was processed correctly
        PipeStream receivedStream = receivedStreamCaptor.getValue();
        assertNotNull(receivedStream, "PipeStreamEngine should have been called with a non-null PipeStream");
        assertEquals(streamId, receivedStream.getStreamId(), "Stream ID should match the sent message");

        LOG.info("PipeStream (streamId: {}) successfully processed after listener was resumed.", streamId);
    }

    @Test
    @DisplayName("Should remove a dynamic listener")
    void testRemoveDynamicListener() {
        // Create a listener for the pipeline
        LOG.info("Creating Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        List<String> createdListeners = kafkaListenerManager.createListenersForPipeline(TEST_PIPELINE_NAME);

        // Verify listener was created
        assertFalse(createdListeners.isEmpty(), "At least one listener should be created");
        String listenerId = createdListeners.get(0);

        // Allow time for listener to subscribe
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the listener exists in the state manager
        ConsumerState state = consumerStateManager.getState(listenerId);
        assertNotNull(state, "Consumer state should exist");

        // Remove the listener
        LOG.info("Removing Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        boolean removed = kafkaListenerManager.removeListener(TEST_PIPELINE_NAME, TEST_STEP_NAME);
        assertTrue(removed, "Listener should be successfully removed");

        // Verify the listener no longer exists in the state manager
        state = consumerStateManager.getState(listenerId);
        assertNull(state, "Consumer state should no longer exist after removal");

        // Send a message to the topic (it should not be processed since the listener is removed)
        String streamId = "test-stream-removed-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-removed").setTitle("Hello Removed Listener").build())
                .setCurrentPipelineName("source-pipeline")
                .setTargetStepName("source-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to topic after listener is removed: {}", streamId, testInputTopic);
        try {
            kafkaForwarder.forwardToKafka(testPipeStream, testInputTopic).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to send message to Kafka", e);
        }

        // Wait a bit to ensure the message is not processed
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the message was not processed (no calls to PipeStreamEngine with this stream ID)
        verify(mockPipeStreamEngine, timeout(1000).times(0)).processStream(Mockito.argThat(
                stream -> stream.getStreamId().equals(streamId)));

        LOG.info("Verified that no messages are processed after listener removal.");
    }
}
