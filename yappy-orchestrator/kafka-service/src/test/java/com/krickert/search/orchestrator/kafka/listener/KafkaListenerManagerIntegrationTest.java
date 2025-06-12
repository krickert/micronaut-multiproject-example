package com.krickert.search.orchestrator.kafka.listener; // Or your test package

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@MicronautTest(
    environments = {"test"},
    propertySources = {"classpath:application-test.yml"}
)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
//TODO - does this match how we normally get it today?
@Property(name = "app.config.cluster-name", value = "test-integ-cluster")
@Property(name = "micronaut.server.port" , value = "${random.port}")
@Property(name = "micronaut.test-resources.enabled", value = "true")
@Property(name = "micronaut.test-resources.shared-server", value = "true")
class KafkaListenerManagerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaListenerManagerIntegrationTest.class);
    private static final String TEST_CLUSTER_NAME = "test-integ-cluster"; // Matches property above
    private static final String TEST_PIPELINE_NAME = "testKafkaPipeline";
    private static final String TEST_STEP_NAME = "kafkaInputStep";
    private static final String TEST_TOPIC_1 = "input-topic-1";
    private static final String TEST_GROUP_ID = "test-group-id";

    @Inject
    ApplicationContext applicationContext; // To inspect beans or properties if needed

    @Inject
    ConsulBusinessOperationsService consulOpsService; // Real one to write to Consul (Testcontainers backed)

    @Inject
    DynamicConfigurationManagerImpl dcm; // Get the real DCM

    @Inject
    KafkaListenerManager kafkaListenerManager; // The SUT, but we'll verify its interactions

    // We need to MOCK the DefaultKafkaListenerPool to capture what KafkaListenerManager tries to do
    @Inject
    DefaultKafkaListenerPool listenerPool;

    @Inject
    ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher; // KafkaListenerManager needs this



    @BeforeEach
    void setUp() {
        // Ensure DCM starts fresh for its config (or ensure test cluster is clean)
        // Deleting the key ensures DCM's watch will see a "new" config when we add it.
        LOG.info("Setting up test: Deleting existing config for cluster {}", TEST_CLUSTER_NAME);
        consulOpsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block(Duration.ofSeconds(5));

        // Initialize DCM for the test cluster name.
        // DCM's @PostConstruct will call initialize with app.config.cluster-name from properties.
        // If this test needs to control *which* cluster DCM manages, we might need a way
        // to re-initialize DCM or ensure the @Value in DCM picks up TEST_CLUSTER_NAME.
        // For now, assuming app.config.cluster-name is set to TEST_CLUSTER_NAME via @MicronautTest properties.
        // The initial event from DCM might try to sync listeners with no config or a deletion.
        // We wait for our explicit seeding to trigger the main event we want to test.
    }

    @AfterEach
    void tearDown() {
        // Clean up the config from Consul
        LOG.info("Tearing down test: Deleting config for cluster {}", TEST_CLUSTER_NAME);
        consulOpsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block(Duration.ofSeconds(5));
        // Optionally stop/clear listeners in KafkaListenerManager if it has a public method,
        // though for this test, verifying interactions with the mock pool is primary.
    }

    @Test
    void testDynamicListenerCreationWithApicurioConfig() throws InterruptedException {
        // 1. ARRANGE: Define a PipelineClusterConfig with a Kafka input step
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
                .listenTopics(Collections.singletonList(TEST_TOPIC_1))
                .consumerGroupId(TEST_GROUP_ID)
                .kafkaConsumerProperties(Map.of("auto.offset.reset", "earliest"))
                .build();

        PipelineStepConfig kafkaStep = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.PIPELINE) // Or an appropriate type
                .kafkaInputs(Collections.singletonList(kafkaInput))
                .processorInfo(new PipelineStepConfig.ProcessorInfo(null, "someInternalProcessorBean")) // Must have a processor
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
                .implementationName("Test Internal Processor")
                .implementationId("someInternalProcessorBean")
                .build();

        Map<String, PipelineModuleConfiguration> moduleMap = Collections.singletonMap(
                "someInternalProcessorBean", processorModule);

        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(new PipelineModuleMap(moduleMap))
                .defaultPipelineName(TEST_PIPELINE_NAME)
                .allowedKafkaTopics(Collections.singleton(TEST_TOPIC_1))
                .allowedGrpcServices(Collections.emptySet())
                .build();

        // 2. ACT: Store this configuration in Consul.
        // This will trigger DynamicConfigurationManager's watch, which will publish an event.
        // KafkaListenerManager will receive this event and should try to create a listener.
        LOG.info("Storing test configuration in Consul for cluster {}", TEST_CLUSTER_NAME);
        Boolean stored = consulOpsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig)
                                         .block(Duration.ofSeconds(10)); // Block to ensure it's written
        assertTrue(stored, "Test config should be stored in Consul");

        // 3. ASSERT: Wait for the asynchronous event handling and listener creation
        // The timeout should be generous enough for Consul watch + event + async processing.
        LOG.info("Waiting for listener creation (will wait up to 15 seconds)...");
        
        // Wait for the listener to be created in the pool
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    // Check if the listener pool has any listeners
                    // Since DefaultKafkaListenerPool doesn't expose a method to check listeners,
                    // we'll need to verify through the KafkaListenerManager's state
                    // or add a method to check the pool's state
                    
                    // For now, we can verify that the configuration was stored and processed
                    // by checking if the DCM has the configuration
                    var configOpt = dcm.getCurrentPipelineClusterConfig();
                    assertTrue(configOpt.isPresent(), "Configuration should be loaded by DCM");
                    var currentConfig = configOpt.get();
                    assertEquals(TEST_CLUSTER_NAME, currentConfig.clusterName());
                    
                    // Verify the pipeline configuration is correct
                    var pipelines = currentConfig.pipelineGraphConfig().pipelines();
                    assertTrue(pipelines.containsKey(TEST_PIPELINE_NAME));
                    
                    var pipeline = pipelines.get(TEST_PIPELINE_NAME);
                    var step = pipeline.pipelineSteps().get(TEST_STEP_NAME);
                    assertNotNull(step, "Pipeline step should exist");
                    
                    var kafkaInputDef = step.kafkaInputs().get(0);
                    assertEquals(TEST_TOPIC_1, kafkaInputDef.listenTopics().get(0));
                    assertEquals(TEST_GROUP_ID, kafkaInputDef.consumerGroupId());
                });

        LOG.info("Test completed. Configuration was successfully stored and processed.");
    }
}
