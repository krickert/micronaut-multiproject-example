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
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus; // Import ConsumerStatus
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
@Property(name = "kafka.consumers.default.bootstrap.servers", value = "${kafka.bootstrap.servers}")
class DynamicListenerIntegrationTestIT {

    static final String TEST_CLUSTER_NAME = "dynamicListenerTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(DynamicListenerIntegrationTestIT.class);
    private static final String TEST_PIPELINE_NAME = "dynamic-listener-test-pipeline";
    private static final String TEST_STEP_NAME = "dynamic-listener-test-step";

    private String testInputTopic;
    private String testConsumerGroupId;
    private String expectedListenerKey; // To store the generated key for assertions

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
    PipeStreamEngine mockPipeStreamEngine;

    @MockBean(PipeStreamEngine.class)
    PipeStreamEngine mockPipeStreamEngineFactoryMethod() {
        return Mockito.mock(PipeStreamEngine.class);
    }

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        testInputTopic = "dynamic-listener-test-input-" + uniqueId;
        testConsumerGroupId = "dynamic-listener-test-group-" + uniqueId;
        expectedListenerKey = String.format("%s:%s:%s:%s", TEST_PIPELINE_NAME, TEST_STEP_NAME, testInputTopic, testConsumerGroupId);
        LOG.info("Using unique topic: {}, group: {}, expected listener key: {}", testInputTopic, testConsumerGroupId, expectedListenerKey);

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
                                .kafkaConsumerProperties(Collections.emptyMap())
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
                .allowedKafkaTopics(Collections.singleton(testInputTopic)) // Ensure the topic is allowed
                .allowedGrpcServices(Collections.singleton("dummy-service"))
                .build();

        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        // DCM is initialized via @PostConstruct in its own class, or if we call initialize explicitly.
        // For tests where DCM might be re-initialized or its state matters per test, explicit call is safer.
        // However, since app.config.cluster-name is fixed for this test class, @PostConstruct should be fine.
        // Let's rely on @PostConstruct for now and ensure the await block is robust.

        LOG.info("Waiting for DynamicConfigurationManager to load config and KafkaListenerManager to react for topic {} and group {}...", testInputTopic, testConsumerGroupId);
        await().atMost(25, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentDcmConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    boolean dcmLoaded = currentDcmConfig.isPresent() && currentDcmConfig.get().clusterName().equals(TEST_CLUSTER_NAME);
                    if (!dcmLoaded) {
                        LOG.trace("DCM not yet loaded for cluster {}", TEST_CLUSTER_NAME);
                        return false;
                    }
                    LOG.trace("DCM loaded for cluster {}. Checking KLM statuses...", TEST_CLUSTER_NAME);
                    Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
                    boolean listenerActive = statuses.containsKey(expectedListenerKey) && !statuses.get(expectedListenerKey).paused();

                    if (listenerActive) {
                        LOG.info("KLM listener active for key: {}", expectedListenerKey);
                    } else {
                        LOG.trace("KLM listener not yet active for key {}. Current statuses: {}", expectedListenerKey, statuses.keySet());
                    }
                    return listenerActive;
                });
        assertNotNull(dynamicConfigurationManager.getCurrentPipelineClusterConfig().orElse(null), "DCM should have loaded the test cluster config");
        LOG.info("Kafka listener setup process complete for topic {} and group {}.", testInputTopic, testConsumerGroupId);
    }

    @AfterEach
    void tearDown() {
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        LOG.info("Waiting for KafkaListenerManager to remove listeners for cluster {} (specifically key {})...", TEST_CLUSTER_NAME, expectedListenerKey);
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
                    boolean listenerRemoved = !statuses.containsKey(expectedListenerKey);
                    if (listenerRemoved) {
                        LOG.info("Listener for key {} successfully removed.", expectedListenerKey);
                    } else {
                        LOG.trace("Listener for key {} still present. Current statuses: {}", expectedListenerKey, statuses.keySet());
                    }
                    return listenerRemoved;
                });
        // dynamicConfigurationManager.shutdown(); // Micronaut context handles this
        LOG.info("Test teardown complete for DynamicListenerIntegrationTestIT.");
    }

    @Test
    @DisplayName("Should create a dynamic listener and receive messages")
    void testCreateDynamicListener() {
        // Listener creation is now handled by setUp and verified by Awaitility.
        // We just need to assert it's there before proceeding.
        Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
        assertTrue(statuses.containsKey(expectedListenerKey),
                "Listener for " + expectedListenerKey + " should be active. Current statuses: " + statuses.keySet());
        assertFalse(statuses.get(expectedListenerKey).paused(), "Listener should not be paused.");

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
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.error("Kafka send failed", e);
            fail("Kafka send failed", e);
        }

        LOG.info("Message sent to Kafka. Waiting for listener to receive and call PipeStreamEngine...");
        ArgumentCaptor<PipeStream> receivedStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        verify(mockPipeStreamEngine, timeout(20000).times(1)).processStream(receivedStreamCaptor.capture());

        PipeStream receivedStream = receivedStreamCaptor.getValue();
        assertNotNull(receivedStream, "PipeStreamEngine should have been called with a non-null PipeStream");
        assertEquals(testPipeStream.getStreamId(), receivedStream.getStreamId());
        assertEquals(testPipeStream.getDocument(), receivedStream.getDocument());
        assertEquals(TEST_PIPELINE_NAME, receivedStream.getCurrentPipelineName());
        assertEquals(TEST_STEP_NAME, receivedStream.getTargetStepName());
        assertEquals(testPipeStream.getCurrentHopNumber(), receivedStream.getCurrentHopNumber());
        LOG.info("PipeStream (streamId: {}) successfully sent to Kafka and received by listener.", streamId);
    }

    @Test
    @DisplayName("Should pause and resume a dynamic listener")
    void testPauseAndResumeDynamicListener() {
        // Listener is already created and active from setUp.
        Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
        assertTrue(statuses.containsKey(expectedListenerKey), "Listener should be active at start of test.");
        String listenerPoolId = statuses.get(expectedListenerKey).id(); // Get the actual pool ID for state checking

        // Pause the listener
        LOG.info("Pausing Kafka listener for key: {}", expectedListenerKey);
        try {
            kafkaListenerManager.pauseConsumer(TEST_PIPELINE_NAME, TEST_STEP_NAME, testInputTopic, testConsumerGroupId)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to pause consumer", e);
        }

        // Verify the listener is paused using Awaitility for state change
        await().atMost(10, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS).until(() -> {
            ConsumerState state = consumerStateManager.getState(listenerPoolId);
            return state != null && state.paused();
        });
        LOG.info("Verified that listener (Pool ID: {}) is paused.", listenerPoolId);

        // Send a message while paused
        String streamIdPaused = "test-stream-paused-" + UUID.randomUUID();
        PipeStream pausedMessage = PipeStream.newBuilder().setStreamId(streamIdPaused).setDocument(PipeDoc.newBuilder().setId("paused-doc")).build();
        LOG.info("Sending PipeStream (streamId: {}) to topic while listener is paused: {}", streamIdPaused, testInputTopic);
        try {
            kafkaForwarder.forwardToKafka(pausedMessage, testInputTopic).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to send message to Kafka", e);
        }

        // Verify the message was not processed immediately
        verify(mockPipeStreamEngine, Mockito.after(3000).never()).processStream(Mockito.argThat(
                stream -> stream.getStreamId().equals(streamIdPaused)));
        LOG.info("Verified message sent while paused was not processed yet.");

        // Resume the listener
        LOG.info("Resuming Kafka listener for key: {}", expectedListenerKey);
        try {
            kafkaListenerManager.resumeConsumer(TEST_PIPELINE_NAME, TEST_STEP_NAME, testInputTopic, testConsumerGroupId)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to resume consumer", e);
        }

        // Verify the listener is resumed
        await().atMost(10, TimeUnit.SECONDS).pollInterval(200, TimeUnit.MILLISECONDS).until(() -> {
            ConsumerState state = consumerStateManager.getState(listenerPoolId);
            return state != null && !state.paused();
        });
        LOG.info("Verified that listener (Pool ID: {}) is resumed.", listenerPoolId);

        // Wait for the paused message to be processed
        ArgumentCaptor<PipeStream> receivedStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);
        verify(mockPipeStreamEngine, timeout(20000).times(1)).processStream(receivedStreamCaptor.capture());

        PipeStream receivedStream = receivedStreamCaptor.getValue();
        assertNotNull(receivedStream);
        assertEquals(streamIdPaused, receivedStream.getStreamId(), "Stream ID of paused message should match.");
        LOG.info("PipeStream (streamId: {}) successfully processed after listener was resumed.", streamIdPaused);
    }

    @Test
    @DisplayName("Should remove a dynamic listener by updating Consul config")
    void testRemoveDynamicListenerViaConsulUpdate() {
        // Listener is active from setUp.
        assertTrue(kafkaListenerManager.getConsumerStatuses().containsKey(expectedListenerKey), "Listener should be active initially.");

        // 1. Create a new PipelineClusterConfig WITHOUT the KafkaInputDefinition for our listener
        PipelineClusterConfig configWithoutListener = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig( // An empty graph, or one without the specific step/input
                        PipelineGraphConfig.builder().pipelines(Collections.emptyMap()).build()
                )
                .pipelineModuleMap(PipelineModuleMap.builder().availableModules(Collections.emptyMap()).build())
                .allowedKafkaTopics(Collections.emptySet()) // Remove the topic from allowed list too
                .allowedGrpcServices(Collections.singleton("dummy-service")) // Keep dummy if needed by other parts
                .build();

        // 2. Store this new config in Consul, overwriting the old one
        LOG.info("Updating Consul config to remove listener definition for key: {}", expectedListenerKey);
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, configWithoutListener).block();

        // 3. Wait for KafkaListenerManager to process the update and remove the listener
        LOG.info("Waiting for KafkaListenerManager to remove the listener for key {}...", expectedListenerKey);
        await().atMost(25, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> !kafkaListenerManager.getConsumerStatuses().containsKey(expectedListenerKey));
        LOG.info("Listener for key {} successfully removed after Consul update.", expectedListenerKey);

        assertFalse(kafkaListenerManager.getConsumerStatuses().containsKey(expectedListenerKey), "Listener should be removed.");

        // 4. Send a message - it should NOT be processed
        String streamIdRemoved = "test-stream-removed-" + UUID.randomUUID();
        PipeStream removedMessage = PipeStream.newBuilder().setStreamId(streamIdRemoved).setDocument(PipeDoc.newBuilder().setId("removed-doc")).build();
        LOG.info("Sending PipeStream (streamId: {}) to topic after listener should be removed: {}", streamIdRemoved, testInputTopic);
        try {
            kafkaForwarder.forwardToKafka(removedMessage, testInputTopic).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            fail("Failed to send message to Kafka", e);
        }

        verify(mockPipeStreamEngine, Mockito.after(3000).never()).processStream(Mockito.argThat(
                stream -> stream.getStreamId().equals(streamIdRemoved)));
        LOG.info("Verified that no messages are processed after listener removal via Consul update.");
    }
}