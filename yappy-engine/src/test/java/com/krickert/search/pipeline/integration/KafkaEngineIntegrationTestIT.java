// File: yappy-engine/src/test/java/com/krickert/search/pipeline/integration/KafkaEngineIntegrationTestIT.java

package com.krickert.search.pipeline.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.fail; // Keep this if you use fail()
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "testcontainers.consul.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = KafkaEngineIntegrationTestIT.TEST_CLUSTER_NAME)

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
class KafkaEngineIntegrationTestIT {

    static final String TEST_CLUSTER_NAME = "kafkaEngineTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEngineIntegrationTestIT.class);
    private static final String TEST_INPUT_TOPIC = "kafka-engine-test-input";
    private static final String TEST_PIPELINE_NAME = "kafka-test-pipeline";
    private static final String TEST_STEP_NAME = "kafka-input-step";
    private static final String TEST_CONSUMER_GROUP_ID = "kafka-test-group";

    @Inject
    KafkaForwarder kafkaForwarder;

    @Inject
    KafkaListenerManager kafkaListenerManager;

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
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();

        PipelineStepConfig.ProcessorInfo dummyProcessorInfo = new PipelineStepConfig.ProcessorInfo("dummy-service", null);
        PipelineStepConfig kafkaInputStep = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(dummyProcessorInfo)
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of(TEST_INPUT_TOPIC))
                                .consumerGroupId(TEST_CONSUMER_GROUP_ID)
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
                .allowedKafkaTopics(Collections.singleton(TEST_INPUT_TOPIC))
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

        LOG.info("Creating Kafka listener for pipeline: {}, step: {}", TEST_PIPELINE_NAME, TEST_STEP_NAME);
        kafkaListenerManager.createListenersForPipeline(TEST_PIPELINE_NAME);

        try {
            TimeUnit.SECONDS.sleep(3); // Allow time for listener to subscribe
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Kafka listener creation process initiated.");
    }

    @AfterEach
    void tearDown() {
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        kafkaListenerManager.removeListener(TEST_PIPELINE_NAME, TEST_STEP_NAME);
        dynamicConfigurationManager.shutdown();
        LOG.info("Test teardown complete.");
    }

    @Test
    @DisplayName("Should forward a PipeStream to Kafka and have the listener receive it")
    void testForwardAndReceivePipeStream() {
        String streamId = "test-stream-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-1").setTitle("Hello Kafka").build())
                .setCurrentPipelineName("source-pipeline")
                .setTargetStepName("source-step")
                .setCurrentHopNumber(1)
                .build();

        LOG.info("Sending PipeStream (streamId: {}) to topic: {}", streamId, TEST_INPUT_TOPIC);
        try {
            kafkaForwarder.forwardToKafka(testPipeStream, TEST_INPUT_TOPIC).get(10, TimeUnit.SECONDS);
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
}