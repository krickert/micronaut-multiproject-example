package com.krickert.search.orchestrator.kafka.listener; // Or your test package

import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

//TODO: reconsider - this should be an integration test so no mocking
@MicronautTest(
    environments = {"dev", "test-integration"}// Ensures application-dev.yml is loaded, add test-integration.yml for overrides
)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
//TODO - does this match how we normally get it today?
@Property(name = "app.config.cluster-name", value = "test-integ-cluster")
@Property(name = "micronaut.server.port" , value = "${random.port}")
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
    DefaultKafkaListenerPool mockListenerPool;

    //TODO: inject the event type instead
    @Inject
    PipeStreamEngine mockPipeStreamEngine; // KafkaListenerManager needs this

    // Mock the DefaultKafkaListenerPool with a @Replaces factory
    @Requires(env = {"test-integration"}) // Only for this test environment
    @Singleton
    @Replaces(DefaultKafkaListenerPool.class)
    DefaultKafkaListenerPool testListenerPool() {
        return Mockito.mock(DefaultKafkaListenerPool.class);
    }

    // Mock PipeStreamEngine for KafkaListenerManager dependency
    @Requires(env = {"test-integration"})
    @Singleton
    //TODO: inject the event type instead
    @Replaces(PipeStreamEngine.class) // Or DefaultPipeStreamEngineLogicImpl.class if that's what's normally injected
    PipeStreamEngine testPipeStreamEngine() {
        return Mockito.mock(PipeStreamEngine.class);
    }


    @BeforeEach
    void setUp() {
        // Reset the mock pool before each test
        Mockito.reset(mockListenerPool);
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

        // 3. ASSERT: Verify KafkaListenerManager calls DefaultKafkaListenerPool.createListener
        // with the correct parameters, including Apicurio settings.
        ArgumentCaptor<String> poolListenerIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> groupIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> consumerConfigCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, String>> originalPropsCaptor = ArgumentCaptor.forClass(Map.class);


        // Wait for the asynchronous event handling and listener creation
        // The timeout should be generous enough for Consul watch + event + async processing.
        LOG.info("Verifying listener creation attempt (will wait up to 15 seconds)...");
        verify(mockListenerPool, timeout(15000).times(1)).createListener(
                poolListenerIdCaptor.capture(),
                topicCaptor.capture(),
                groupIdCaptor.capture(),
                consumerConfigCaptor.capture(),
                originalPropsCaptor.capture(),
                eq(TEST_PIPELINE_NAME),
                eq(TEST_STEP_NAME),
                //TODO: needs to be removed
                any(PipeStreamEngine.class) // or eq(mockPipeStreamEngine)
        );

        // Assertions on captured arguments
        assertEquals(TEST_TOPIC_1, topicCaptor.getValue());
        assertEquals(TEST_GROUP_ID, groupIdCaptor.getValue());

        Map<String, Object> capturedConsumerConfig = consumerConfigCaptor.getValue();
        LOG.info("Captured Kafka Consumer Config for listener: {}", capturedConsumerConfig);

        // Check for Bootstrap Servers (should be picked up from Micronaut's Kafka config, possibly via TestResources)
        assertNotNull(capturedConsumerConfig.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG), "Bootstrap servers should be set");
        LOG.info("Bootstrap servers in captured config: {}", capturedConsumerConfig.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));


        // **VERIFY APICURIO CONFIGURATION**
        assertEquals("io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer",
                capturedConsumerConfig.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG),
                "Value deserializer should be Apicurio Protobuf for 'apicurio' type.");

        assertNotNull(capturedConsumerConfig.get(SerdeConfig.REGISTRY_URL), "Apicurio registry URL should be set.");
        // You might want to assert the actual URL if it's predictable in your test environment
        // e.g., assertEquals("http://localhost:8081/apis/registry/v2", capturedConsumerConfig.get(SerdeConfig.REGISTRY_URL));

        // Verify that the original "auto.offset.reset" property from the step definition was included
        assertEquals("earliest", capturedConsumerConfig.get("auto.offset.reset"));

        // Verify that the original properties map was passed through for comparison/reference
        assertEquals(Map.of("auto.offset.reset", "earliest"), originalPropsCaptor.getValue());

        // Also, let's check the configuredSchemaRegistryType in KafkaListenerManager itself
        // This assumes KafkaListenerManager has been successfully injected.
        // We might need reflection or a getter if it's private, or check logs.
        // If KafkaListenerManager has instanceId logging, confirm its schema registry type log.
        // For this test, we can check the applicationContext directly
        String resolvedSchemaTypeInManager = applicationContext.getBean(KafkaListenerManager.class)
            .getConfiguredSchemaRegistryType(); // You'd need to add this getter for testing
        assertEquals("apicurio", resolvedSchemaTypeInManager, 
            "KafkaListenerManager should be configured with 'apicurio' schema registry type in this test environment.");

        LOG.info("Test completed. Listener creation attempt verified with expected Apicurio config.");
    }
}
