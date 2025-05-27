package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.core.DefaultPipeStreamEngineLogicImpl;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MicronautTest(
    environments = {"dev", "test-integration"}
)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = "test-e2e-cluster")
public class DynamicKafkaListenerE2ETest {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicKafkaListenerE2ETest.class);
    private static final String TEST_CLUSTER_NAME = "test-e2e-cluster";
    private static final String TEST_PIPELINE_NAME = "testKafkaPipeline";
    private static final String TEST_STEP_NAME = "kafkaListenerStep";
    private static final String TEST_TOPIC = "test-input-topic-1";
    private static final String TEST_GROUP_ID = "test-group-1";
    private static final String TEST_PROCESSOR_BEAN_ID = "testProcessorBeanForKafkaStep";

    @Inject
    ApplicationContext applicationContext;

    @Inject
    ConsulBusinessOperationsService consulOpsService;

    @Inject
    DynamicConfigurationManagerImpl dcm;

    @Inject
    KafkaListenerManager kafkaListenerManager;

    @Inject
    PipeStreamEngine pipeStreamEngine;

    @KafkaClient
    @Requires(env = {"test-integration"})
    public interface TestKafkaProducer {
        void sendMessage(String topic, PipeStream message);
    }

    @Inject
    TestKafkaProducer kafkaProducer;

    @BeforeEach
    void setUp() {
        // Reset the mocks before each test
        reset(pipeStreamEngine);

        // Ensure DCM starts fresh for its config
        LOG.info("Setting up test: Deleting existing config for cluster {}", TEST_CLUSTER_NAME);
        consulOpsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block(Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        // Clean up the config from Consul
        LOG.info("Tearing down test: Deleting config for cluster {}", TEST_CLUSTER_NAME);
        consulOpsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block(Duration.ofSeconds(5));
    }

    @Test
    void testDynamicKafkaListenerE2E() throws InterruptedException {
        // 1. ARRANGE: Define a PipelineClusterConfig with a Kafka input step
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(Collections.singletonList(TEST_TOPIC))
                .consumerGroupId(TEST_GROUP_ID)
                .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                .build();

        PipelineStepConfig kafkaStep = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.INITIAL_PIPELINE)
                .kafkaInputs(Collections.singletonList(kafkaInput))
                .processorInfo(new PipelineStepConfig.ProcessorInfo(null, TEST_PROCESSOR_BEAN_ID))
                .build();

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .name(TEST_PIPELINE_NAME)
                .pipelineSteps(Map.of(TEST_STEP_NAME, kafkaStep))
                .build();

        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE_NAME, pipelineConfig))
                .build();

        // Create a module configuration for the processor bean used in the test
        PipelineModuleConfiguration processorModule = PipelineModuleConfiguration.builder()
                .implementationName("Test Processor for Kafka")
                .implementationId(TEST_PROCESSOR_BEAN_ID)
                .build();

        Map<String, PipelineModuleConfiguration> moduleMap = Collections.singletonMap(
                TEST_PROCESSOR_BEAN_ID, processorModule);

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(new PipelineModuleMap(moduleMap))
                .defaultPipelineName(TEST_PIPELINE_NAME)
                .allowedKafkaTopics(Collections.singleton(TEST_TOPIC))
                .allowedGrpcServices(Collections.emptySet())
                .build();

        // 2. ACT: Store this configuration in Consul
        LOG.info("Storing test configuration in Consul for cluster {}", TEST_CLUSTER_NAME);
        Boolean stored = consulOpsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig)
                                         .block(Duration.ofSeconds(10));
        assertTrue(stored, "Test config should be stored in Consul");

        // Wait for the KafkaListenerManager to create the listener
        LOG.info("Waiting for KafkaListenerManager to create the listener...");
        Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
                    LOG.info("Current consumer statuses: {}", statuses);
                    return statuses.size() > 0;
                });

        // 3. Create a sample PipeStream message and send it to the Kafka topic
        LOG.info("Creating and sending a test message to topic: {}", TEST_TOPIC);
        String streamId = UUID.randomUUID().toString();
        PipeStream testMessage = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder().setId("test-doc-1").build())
                .setCurrentPipelineName(TEST_PIPELINE_NAME)
                .setTargetStepName(TEST_STEP_NAME)
                .build();

        // Send the message to Kafka
        kafkaProducer.sendMessage(TEST_TOPIC, testMessage);

        // 4. ASSERT: Verify that the message was processed by the engine
        LOG.info("Verifying that the message was processed by the engine...");
        ArgumentCaptor<PipeStream> pipeStreamCaptor = ArgumentCaptor.forClass(PipeStream.class);

        // Wait for the message to be processed
        verify(pipeStreamEngine, timeout(15000).times(1)).processStream(
                pipeStreamCaptor.capture()
        );

        // Verify the captured PipeStream matches our test message
        PipeStream capturedStream = pipeStreamCaptor.getValue();
        assertEquals(streamId, capturedStream.getStreamId(), "The processed message stream ID should match the sent message stream ID");
        assertEquals(TEST_PIPELINE_NAME, capturedStream.getCurrentPipelineName(), "The pipeline name should match");

        LOG.info("Test completed successfully. Dynamic Kafka listener created and processed the message.");
    }

    // Mock the DefaultPipeStreamEngineLogicImpl for verification
    @Requires(env = {"test-integration"})
    @Singleton
    @Replaces(DefaultPipeStreamEngineLogicImpl.class)
    PipeStreamEngine testPipeStreamEngine() {
        return Mockito.mock(PipeStreamEngine.class);
    }
}
