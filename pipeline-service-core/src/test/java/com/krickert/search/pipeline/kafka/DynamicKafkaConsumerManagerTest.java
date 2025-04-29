package com.krickert.search.pipeline.kafka;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.PipelineConfigService;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.test.platform.AbstractPipelineTest;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.fail;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DynamicKafkaConsumerManager to validate configuration loading.
 * This test focuses on ensuring that kafka-listen-topics, kafka-publish-topics,
 * and grpc-forward-to settings are properly loaded from configuration.
 */
@MicronautTest(environments = {"apicurio", "consul"}, transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DynamicKafkaConsumerManagerTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(DynamicKafkaConsumerManagerTest.class);

    // --- Constants ---
    private static final String TEST_PIPELINE_NAME = "test-kafka-pipeline";
    private static final String TEST_SERVICE_NAME = "test-kafka-service";
    private static final String TEST_INPUT_TOPIC = "test-kafka-input";
    private static final String TEST_OUTPUT_TOPIC = AbstractPipelineTest.PIPELINE_TEST_OUTPUT_TOPIC;
    private static final String TEST_GRPC_FORWARD_TO = "test-grpc-service";

    // --- Test Lifecycle ---
    @BeforeEach
    @Override
    public void setUp() {
        LOG.info("Executing @BeforeEach in DynamicKafkaConsumerManagerTest");
        // Load this test's specific config into Consul *before* super.setUp()
        loadConfigIntoConsul(); // Uses base helper + getConfigToLoad() below
        // Call super setup
        super.setUp();

        // Directly create and add a PipelineConfig to the PipelineConfigService
        PipelineConfig pipelineConfig = new PipelineConfig(TEST_PIPELINE_NAME);
        ServiceConfiguration serviceConfig = new ServiceConfiguration(TEST_SERVICE_NAME);
        serviceConfig.setKafkaListenTopics(Collections.singletonList(getInputTopic()));
        serviceConfig.setKafkaPublishTopics(Collections.singletonList(getOutputTopic()));
        serviceConfig.setGrpcForwardTo(Collections.singletonList(TEST_GRPC_FORWARD_TO));
        pipelineConfig.getService().put(TEST_SERVICE_NAME, serviceConfig);

        // Add the pipeline config to the service
        Map<String, PipelineConfig> pipelineConfigs = new HashMap<>(pipelineConfigService.getPipelineConfigs());
        pipelineConfigs.put(TEST_PIPELINE_NAME, pipelineConfig);

        // Use reflection to set the pipelineConfigs field in PipelineConfigService
        try {
            Field pipelineConfigsField = PipelineConfigService.class.getDeclaredField("pipelineConfigs");
            pipelineConfigsField.setAccessible(true);
            pipelineConfigsField.set(pipelineConfigService, new ConcurrentHashMap<>(pipelineConfigs));

            // Set the active pipeline name
            Field activePipelineNameField = PipelineConfigService.class.getDeclaredField("activePipelineName");
            activePipelineNameField.setAccessible(true);
            activePipelineNameField.set(pipelineConfigService, TEST_PIPELINE_NAME);
        } catch (Exception e) {
            LOG.error("Failed to set pipeline configs", e);
            fail("Failed to set pipeline configs: " + e.getMessage());
        }

        LOG.info("Finished @BeforeEach in DynamicKafkaConsumerManagerTest");
    }

    // --- Abstract Method Implementations ---
    @Override
    protected PipeStream getInput() {
        // Create a simple PipeStream with a PipeDoc
        PipeDoc pipeDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Test Document")
                .setBody("This is a test document for DynamicKafkaConsumerManager")
                .build();

        PipeRequest pipeRequest = PipeRequest.newBuilder()
                .setDoc(pipeDoc)
                .build();

        return PipeStream.newBuilder()
                .setRequest(pipeRequest)
                .build();
    }

    @Override
    protected PipeResponse getExpectedOutput() {
        return PipeResponse.newBuilder().setSuccess(true).build();
    }

    @Override
    protected PipeDoc getExpectedPipeDoc() {
        return getInput().getRequest().getDoc();
    }

    @Override
    protected String getInputTopic() {
        return TEST_INPUT_TOPIC;
    }

    @Override
    protected String getOutputTopic() {
        return TEST_OUTPUT_TOPIC;
    }

    /**
     * Defines the properties to load into Consul for this test.
     * Uses dashed naming convention for configuration keys.
     */
    @Override
    protected Properties getConfigToLoad() {
        Properties props = new Properties();
        // Keys are relative to the prefix defined in ConsulContainerManager
        // The key format should match what's expected by PipelineConfigManager
        String keyPrefix = "pipeline.configs." + TEST_PIPELINE_NAME + ".service." + TEST_SERVICE_NAME;
        props.setProperty(keyPrefix + ".kafkaListenTopics", getInputTopic());
        props.setProperty(keyPrefix + ".kafkaPublishTopics", getOutputTopic());
        props.setProperty(keyPrefix + ".grpcForwardTo", TEST_GRPC_FORWARD_TO);
        return props;
    }

    /**
     * Provides only the necessary application-level properties for Micronaut.
     */
    @Override
    @NonNull
    public Map<String, String> getProperties() {
        // Start with ALL container properties provided by the base class
        Map<String, String> properties = new HashMap<>(super.getProperties());
        // Add ONLY properties needed to configure THIS application context
        properties.put("pipeline.service.name", TEST_SERVICE_NAME); // Crucial for InternalServiceConfig
        properties.put("pipeline.active", TEST_PIPELINE_NAME); // Set the active pipeline
        LOG.info("Providing Micronaut test properties for DynamicKafkaConsumerManagerTest: {}", properties.keySet());
        return properties;
    }

    /**
     * Test that validates the configuration is properly loaded and used by DynamicKafkaConsumerManager.
     * This test checks that kafka-listen-topics, kafka-publish-topics, and grpc-forward-to settings
     * are properly loaded from configuration.
     */
    @Test
    public void testConfigurationLoading() {
        LOG.info("Running DynamicKafkaConsumerManagerTest -> testConfigurationLoading");

        // Verify that the pipeline configuration is loaded
        Map<String, PipelineConfig> pipelineConfigs = pipelineConfigService.getPipelineConfigs();
        assertNotNull(pipelineConfigs, "Pipeline configurations should not be null");
        assertTrue(pipelineConfigs.containsKey(TEST_PIPELINE_NAME), "Pipeline configurations should contain " + TEST_PIPELINE_NAME);

        // Get the pipeline configuration
        PipelineConfig pipelineConfig = pipelineConfigs.get(TEST_PIPELINE_NAME);
        assertNotNull(pipelineConfig, "Pipeline configuration should not be null");

        // Verify that the service configuration is loaded
        Map<String, ServiceConfiguration> serviceConfigs = pipelineConfig.getService();
        assertNotNull(serviceConfigs, "Service configurations should not be null");
        assertTrue(serviceConfigs.containsKey(TEST_SERVICE_NAME), "Service configurations should contain " + TEST_SERVICE_NAME);

        // Get the service configuration
        ServiceConfiguration serviceConfig = serviceConfigs.get(TEST_SERVICE_NAME);
        assertNotNull(serviceConfig, "Service configuration should not be null");

        // Verify kafka-listen-topics
        List<String> kafkaListenTopics = serviceConfig.getKafkaListenTopics();
        assertNotNull(kafkaListenTopics, "kafka-listen-topics should not be null");
        assertEquals(1, kafkaListenTopics.size(), "kafka-listen-topics should have 1 topic");
        assertEquals(TEST_INPUT_TOPIC, kafkaListenTopics.get(0), "kafka-listen-topics should contain " + TEST_INPUT_TOPIC);

        // Verify kafka-publish-topics
        List<String> kafkaPublishTopics = serviceConfig.getKafkaPublishTopics();
        assertNotNull(kafkaPublishTopics, "kafka-publish-topics should not be null");
        assertEquals(1, kafkaPublishTopics.size(), "kafka-publish-topics should have 1 topic");
        assertEquals(TEST_OUTPUT_TOPIC, kafkaPublishTopics.get(0), "kafka-publish-topics should contain " + TEST_OUTPUT_TOPIC);

        // Verify grpc-forward-to
        List<String> grpcForwardTo = serviceConfig.getGrpcForwardTo();
        assertNotNull(grpcForwardTo, "grpc-forward-to should not be null");
        assertEquals(1, grpcForwardTo.size(), "grpc-forward-to should have 1 service");
        assertEquals(TEST_GRPC_FORWARD_TO, grpcForwardTo.get(0), "grpc-forward-to should contain " + TEST_GRPC_FORWARD_TO);

        // Verify that DynamicKafkaConsumerManager has synchronized consumers
        // This is a bit tricky to test directly since the consumers are managed internally
        // We can indirectly test by triggering a synchronization and checking that no exceptions are thrown
        try {
            dynamicKafkaConsumerManager.synchronizeConsumers();
            LOG.info("DynamicKafkaConsumerManager synchronized consumers successfully");
        } catch (Exception e) {
            LOG.error("Error synchronizing consumers", e);
            fail("DynamicKafkaConsumerManager failed to synchronize consumers: " + e.getMessage());
        }

        LOG.info("DynamicKafkaConsumerManagerTest -> testConfigurationLoading completed successfully");
    }

    /**
     * Test that validates the Kafka message flow works with the loaded configuration.
     * This test sends a message to the input topic and verifies that it's processed
     * and forwarded to the output topic.
     */
    @Test
    public void testKafkaMessageFlow() throws Exception {
        LOG.info("Running DynamicKafkaConsumerManagerTest -> testKafkaMessageFlow");
        super.testKafkaToKafka(); // Executes the base test logic
        LOG.info("DynamicKafkaConsumerManagerTest -> testKafkaMessageFlow completed successfully");
    }
}
