package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.config.InternalServiceConfig;
import com.krickert.search.pipeline.config.PipelineConfig;
import com.krickert.search.pipeline.config.PipelineConfigService;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DynamicKafkaConsumerManager.
 * This test verifies that the manager correctly creates consumers based on the pipeline configuration.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DynamicKafkaConsumerManagerTest extends DynamicKafkaConsumerTestBase implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = super.getProperties();
        // Enable DynamicKafkaConsumerManager
        properties.put("kafka.consumer.dynamic.enabled", "true");

        // Configure the executor service
        properties.put("micronaut.executors.dynamic-kafka-consumer-executor.type", "fixed");
        properties.put("micronaut.executors.dynamic-kafka-consumer-executor.nThreads", "5");

        // Set the pipeline service name
        properties.put("pipeline.service.name", "test-service");

        return properties;
    }
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaConsumerManagerTest.class);

    /**
     * Factory for test beans.
     */
    @Factory
    static class TestBeanFactory {
        /**
         * Provides an ExecutorService for the DynamicKafkaConsumerManager.
         * 
         * @return An ExecutorService.
         */
        @Bean
        @Singleton
        @Primary
        @Named("dynamic-kafka-consumer-executor")
        ExecutorService dynamicKafkaExecutor() {
            return Executors.newFixedThreadPool(5);
        }
    }

    @Inject
    private DynamicKafkaConsumerManager consumerManager;

    @Inject
    private PipelineConfigService configService;

    @Inject
    private InternalServiceConfig internalServiceConfig;

    @Inject
    private ApplicationEventPublisher eventPublisher;

    /**
     * Set up the test environment before each test.
     * This method ensures that consumers are created before each test.
     */
    @BeforeEach
    public void setUpConsumers() throws Exception {
        log.info("Setting up consumers for test");
        // Trigger synchronization of consumers
        consumerManager.synchronizeConsumers();
        // Wait a bit for consumers to start
        TimeUnit.SECONDS.sleep(2);
    }

    /**
     * Get the input PipeStream for testing.
     * 
     * @return the input PipeStream
     */
    @Override
    protected PipeStream getInput() {
        // Create a simple PipeStream for testing
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-id")
                .setTitle("Test Document")
                .setBody("This is a test document")
                .build();

        return PipeStream.newBuilder()
                .setRequest(com.krickert.search.model.PipeRequest.newBuilder().setDoc(doc).build())
                .build();
    }

    /**
     * Get the expected output PipeResponse after processing.
     * 
     * @return the expected PipeResponse
     */
    @Override
    protected PipeResponse getExpectedOutput() {
        return PipeResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    /**
     * Get the expected output PipeDoc after processing.
     * 
     * @return the expected PipeDoc
     */
    @Override
    protected PipeDoc getExpectedPipeDoc() {
        return getInput().getRequest().getDoc();
    }

    /**
     * Test that the DynamicKafkaConsumerManager creates consumers for each pipeline
     * that has the current service configured with Kafka listen topics.
     */
    @Test
    public void testConsumerCreation() throws Exception {
        log.info("Starting testConsumerCreation");

        // Verify that the DynamicKafkaConsumerManager is properly injected
        assertNotNull(consumerManager, "DynamicKafkaConsumerManager should be injected");

        // Verify that the PipelineConfigService is properly injected
        assertNotNull(configService, "PipelineConfigService should be injected");

        // Get the current service name
        String serviceName = internalServiceConfig.getPipelineServiceName();
        log.info("Current service name: {}", serviceName);

        // Get the pipeline configurations
        Map<String, PipelineConfig> pipelineConfigs = configService.getPipelineConfigs();
        assertNotNull(pipelineConfigs, "Pipeline configurations should not be null");
        assertFalse(pipelineConfigs.isEmpty(), "Pipeline configurations should not be empty");

        log.info("Found {} pipeline configurations", pipelineConfigs.size());
        for (Map.Entry<String, PipelineConfig> entry : pipelineConfigs.entrySet()) {
            log.info("Pipeline: {}", entry.getKey());
            PipelineConfig config = entry.getValue();
            Map<String, ServiceConfiguration> services = config.getService();
            if (services != null) {
                for (Map.Entry<String, ServiceConfiguration> serviceEntry : services.entrySet()) {
                    log.info("  Service: {}", serviceEntry.getKey());
                    ServiceConfiguration serviceConfig = serviceEntry.getValue();
                    List<String> listenTopics = serviceConfig.getKafkaListenTopics();
                    if (listenTopics != null) {
                        log.info("    Kafka listen topics: {}", listenTopics);
                    }
                }
            }
        }

        // Trigger synchronization of consumers
        log.info("Triggering synchronization of consumers");
        consumerManager.synchronizeConsumers();

        // Wait a bit for consumers to start
        TimeUnit.SECONDS.sleep(2);

        // Use reflection to access the private runningConsumers map
        Field runningConsumersField = DynamicKafkaConsumerManager.class.getDeclaredField("runningConsumers");
        runningConsumersField.setAccessible(true);
        Map<String, Object> runningConsumers = (Map<String, Object>) runningConsumersField.get(consumerManager);

        // Verify that consumers were created for each pipeline with the current service
        for (Map.Entry<String, PipelineConfig> entry : pipelineConfigs.entrySet()) {
            String pipelineName = entry.getKey();
            PipelineConfig config = entry.getValue();

            // Get the service configuration for the current service
            ServiceConfiguration serviceConfig = config.getService().get(serviceName);
            if (serviceConfig != null && serviceConfig.getKafkaListenTopics() != null && !serviceConfig.getKafkaListenTopics().isEmpty()) {
                log.info("Checking if consumer was created for pipeline: {}", pipelineName);
                assertTrue(runningConsumers.containsKey(pipelineName), 
                        "Consumer should be created for pipeline " + pipelineName);
            }
        }

        // Trigger synchronization again to simulate what happens when a ServiceReadyEvent is received
        log.info("Triggering synchronization again");
        consumerManager.synchronizeConsumers();

        // Wait a bit for consumers to synchronize
        TimeUnit.SECONDS.sleep(2);

        // Verify again that consumers were created
        for (Map.Entry<String, PipelineConfig> entry : pipelineConfigs.entrySet()) {
            String pipelineName = entry.getKey();
            PipelineConfig config = entry.getValue();

            // Get the service configuration for the current service
            ServiceConfiguration serviceConfig = config.getService().get(serviceName);
            if (serviceConfig != null && serviceConfig.getKafkaListenTopics() != null && !serviceConfig.getKafkaListenTopics().isEmpty()) {
                log.info("Checking if consumer was created for pipeline after second synchronization: {}", pipelineName);
                assertTrue(runningConsumers.containsKey(pipelineName), 
                        "Consumer should be created for pipeline " + pipelineName + " after second synchronization");
            }
        }

        log.info("testConsumerCreation completed successfully");
    }
}
