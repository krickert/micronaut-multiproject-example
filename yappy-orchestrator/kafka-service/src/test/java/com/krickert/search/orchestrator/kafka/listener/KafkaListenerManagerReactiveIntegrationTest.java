package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for KafkaListenerManager focusing on the reactive API
 * and event-driven listener creation/deletion.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
class KafkaListenerManagerReactiveIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaListenerManagerReactiveIntegrationTest.class);
    
    private String TEST_CLUSTER_NAME; // Will be set from the actual cluster name
    private static final String PIPELINE_NAME = "reactiveTestPipeline";
    private static final String STEP_NAME = "kafkaInputStep";
    private static final String TOPIC_NAME = "reactive-test-topic";
    private static final String GROUP_ID = "reactive-test-group";
    private static final String STEP_TYPE = "KAFKA_INPUT_JAVA";

    @Inject
    KafkaListenerManager kafkaListenerManager;
    
    @Inject
    ConsulBusinessOperationsService consulOps;
    
    @Inject
    DynamicConfigurationManager dcm;
    
    @Inject
    KafkaAdminService kafkaAdminService;
    
    @Inject
    KafkaListenerPool listenerPool;
    
    @Inject
    AdminClient adminClient;
    
    @Inject
    ApplicationContext applicationContext;
    
    private KafkaProducer<UUID, PipeStream> producer;
    
    @BeforeAll
    void setupAll() {
        // Get the actual cluster name from the applicationContext
        TEST_CLUSTER_NAME = applicationContext.getProperty("app.config.cluster-name", String.class)
                .orElseThrow(() -> new IllegalStateException("app.config.cluster-name not configured"));
        LOG.info("Using cluster name: {}", TEST_CLUSTER_NAME);
        
        // Create topic
        NewTopic newTopic = new NewTopic(TOPIC_NAME, 1, (short) 1);
        try {
            adminClient.createTopics(Collections.singletonList(newTopic)).all().get();
            LOG.info("Created topic: {}", TOPIC_NAME);
        } catch (ExecutionException e) {
            if (e.getCause().getMessage().contains("already exists")) {
                LOG.info("Topic {} already exists", TOPIC_NAME);
            } else {
                throw new RuntimeException("Failed to create topic", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create topic", e);
        }
        
        // Create producer for testing with proper Apicurio configuration
        Properties producerProps = new Properties();
        String bootstrapServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class)
                .orElseThrow(() -> new IllegalStateException("kafka.bootstrap.servers not configured"));
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer");
        
        // Add all necessary Apicurio configuration
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class)
            .orElse("http://localhost:8081");
        producerProps.put(SerdeConfig.REGISTRY_URL, apicurioUrl);
        producerProps.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        producerProps.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, 
            "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        
        producer = new KafkaProducer<>(producerProps);
    }
    
    @BeforeEach
    void setUp() {
        // Clear any existing configuration
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Wait for deletion to propagate
        await().atMost(Duration.ofSeconds(5))
            .until(() -> dcm.getCurrentPipelineClusterConfig().isEmpty());
    }
    
    @AfterEach
    void tearDown() {
        // Clean up configuration
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Shutdown all listeners
        listenerPool.shutdownAllListeners();
    }
    
    @AfterAll
    void tearDownAll() {
        if (producer != null) {
            producer.close();
        }
        if (adminClient != null) {
            try {
                adminClient.deleteTopics(Collections.singletonList(TOPIC_NAME)).all().get();
                LOG.info("Deleted topic: {}", TOPIC_NAME);
            } catch (Exception e) {
                LOG.warn("Failed to delete topic: {}", e.getMessage());
            }
        }
    }
    
    @Test
    void testEventDrivenListenerCreation() {
        // Create configuration
        PipelineClusterConfig config = createTestConfig();
        
        // Store in Consul
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        LOG.info("Stored configuration in Consul for cluster: {}", TEST_CLUSTER_NAME);
        
        // Wait for listener to be created via event
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertEquals(1, listenerPool.getListenerCount(), 
                    "One listener should be created from the configuration");
                
                // Verify the listener details
                Collection<DynamicKafkaListener> listeners = listenerPool.getAllListeners();
                DynamicKafkaListener listener = listeners.iterator().next();
                assertEquals(TOPIC_NAME, listener.getTopic());
                assertEquals(GROUP_ID, listener.getGroupId());
            });
    }
    
    @Test  
    void testPauseAndResumeConsumer() {
        // Setup: Create configuration and wait for listener
        PipelineClusterConfig config = createTestConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 1);
            
        // Test pause
        Mono<Void> pauseResult = kafkaListenerManager.pauseConsumer(PIPELINE_NAME, STEP_NAME, TOPIC_NAME, GROUP_ID);
        
        StepVerifier.create(pauseResult)
            .verifyComplete();
            
        // Verify consumer state is paused
        Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
        assertEquals(1, statuses.size());
        ConsumerStatus status = statuses.values().iterator().next();
        assertTrue(status.paused(), "Consumer should be paused");
        
        // Test resume
        Mono<Void> resumeResult = kafkaListenerManager.resumeConsumer(PIPELINE_NAME, STEP_NAME, TOPIC_NAME, GROUP_ID);
        
        StepVerifier.create(resumeResult)
            .verifyComplete();
            
        // Verify consumer state is resumed
        statuses = kafkaListenerManager.getConsumerStatuses();
        status = statuses.values().iterator().next();
        assertFalse(status.paused(), "Consumer should be resumed");
    }
    
    @Test
    void testResetOffsetToEarliest() throws Exception {
        // Setup: Create configuration and wait for listener
        PipelineClusterConfig config = createTestConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 1);
            
        // Send some messages first
        for (int i = 0; i < 5; i++) {
            UUID messageId = UUID.randomUUID();
            PipeStream message = PipeStream.newBuilder()
                .setStreamId(messageId.toString())
                .setDocument(PipeDoc.newBuilder()
                    .setId("doc-" + i)
                    .build())
                .setCurrentPipelineName(PIPELINE_NAME)
                .setTargetStepName(STEP_NAME)
                .build();
                
            producer.send(new ProducerRecord<>(TOPIC_NAME, messageId, message)).get();
        }
        
        // Wait for consumer group to form
        Thread.sleep(2000);
        
        // Reset offset to earliest
        Mono<Void> resetResult = kafkaListenerManager.resetOffsetToEarliest(PIPELINE_NAME, STEP_NAME, TOPIC_NAME, GROUP_ID);
        
        StepVerifier.create(resetResult)
            .verifyComplete();
            
        // Verify offset was reset (would need to check consumer group offset)
        // This is a simplified check - in reality you'd verify the actual offset
        Thread.sleep(1000); // Give time for reset to take effect
        
        // Check consumer group exists and has expected configuration
        Map<String, ConsumerGroupDescription> groups = adminClient.describeConsumerGroups(Collections.singletonList(GROUP_ID))
            .all().get();
        assertTrue(groups.containsKey(GROUP_ID), "Consumer group should exist");
    }
    
    @Test
    void testResetOffsetToLatest() {
        // Setup: Create configuration and wait for listener
        PipelineClusterConfig config = createTestConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 1);
            
        // Reset offset to latest
        Mono<Void> resetResult = kafkaListenerManager.resetOffsetToLatest(PIPELINE_NAME, STEP_NAME, TOPIC_NAME, GROUP_ID);
        
        StepVerifier.create(resetResult)
            .verifyComplete();
    }
    
    @Test
    void testResetOffsetToDate() {
        // Setup: Create configuration and wait for listener
        PipelineClusterConfig config = createTestConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 1);
            
        // Reset offset to specific date
        Instant targetDate = Instant.now().minus(Duration.ofHours(1));
        Mono<Void> resetResult = kafkaListenerManager.resetOffsetToDate(PIPELINE_NAME, STEP_NAME, TOPIC_NAME, GROUP_ID, targetDate);
        
        StepVerifier.create(resetResult)
            .verifyComplete();
    }
    
    @Test
    void testConfigurationDeletion() {
        // Setup: Create configuration and wait for listener
        PipelineClusterConfig config = createTestConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 1);
            
        // Delete configuration
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Wait for listener to be removed via event
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertEquals(0, listenerPool.getListenerCount(), 
                    "All listeners should be removed when configuration is deleted");
            });
    }
    
    @Test
    @Disabled("TODO: Implement when pipeline model is clarified")
    void testMultipleListenersFromConfiguration() {
        // TODO: This test needs to be implemented once we understand the correct
        // pipeline configuration model structure
    }
    
    @Test
    void testNonExistentListenerOperations() {
        // Test operations on non-existent listeners
        Mono<Void> pauseResult = kafkaListenerManager.pauseConsumer("nonexistent", "pipeline", "topic", "group");
        StepVerifier.create(pauseResult)
            .expectError(IllegalArgumentException.class)
            .verify();
            
        Mono<Void> resumeResult = kafkaListenerManager.resumeConsumer("nonexistent", "pipeline", "topic", "group");
        StepVerifier.create(resumeResult)
            .expectError(IllegalArgumentException.class)
            .verify();
            
        Mono<Void> resetResult = kafkaListenerManager.resetOffsetToEarliest("nonexistent", "pipeline", "topic", "group");
        StepVerifier.create(resetResult)
            .expectError(IllegalArgumentException.class)
            .verify();
    }
    
    private PipelineClusterConfig createTestConfig() {
        // Create a Kafka input step
        List<KafkaInputDefinition> kafkaInputs = List.of(
            KafkaInputDefinition.builder()
                .listenTopics(List.of(TOPIC_NAME))
                .consumerGroupId(GROUP_ID)
                .kafkaConsumerProperties(Map.of(
                    "auto.offset.reset", "earliest",
                    "max.poll.records", "100"))
                .build()
        );
        
        // Create processor info for the step
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
            .grpcServiceName("kafka-listener-service")
            .build();
        
        // Create the Kafka input step
        PipelineStepConfig kafkaInputStep = PipelineStepConfig.builder()
            .stepName(STEP_NAME)
            .stepType(StepType.INITIAL_PIPELINE)
            .description("Kafka input step for integration test")
            .kafkaInputs(kafkaInputs)
            .processorInfo(processorInfo)
            .build();
        
        // Create pipeline with the Kafka input step
        Map<String, PipelineStepConfig> pipelineSteps = new HashMap<>();
        pipelineSteps.put(kafkaInputStep.stepName(), kafkaInputStep);
        
        PipelineConfig pipeline = PipelineConfig.builder()
            .name(PIPELINE_NAME)
            .pipelineSteps(pipelineSteps)
            .build();
        
        // Create pipeline graph
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put(pipeline.name(), pipeline);
        
        PipelineGraphConfig pipelineGraphConfig = PipelineGraphConfig.builder()
            .pipelines(pipelines)
            .build();
        
        // Create module configuration for our service
        PipelineModuleConfiguration moduleConfig = PipelineModuleConfiguration.builder()
            .implementationName("Kafka Listener Service")
            .implementationId("kafka-listener-service")
            .build();
        
        Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();
        availableModules.put(moduleConfig.implementationId(), moduleConfig);
        
        PipelineModuleMap pipelineModuleMap = PipelineModuleMap.builder()
            .availableModules(availableModules)
            .build();
        
        // Create the cluster config
        return PipelineClusterConfig.builder()
            .clusterName(TEST_CLUSTER_NAME)
            .pipelineGraphConfig(pipelineGraphConfig)
            .pipelineModuleMap(pipelineModuleMap)
            .defaultPipelineName(PIPELINE_NAME)
            .allowedKafkaTopics(Set.of(TOPIC_NAME))
            .allowedGrpcServices(Set.of("kafka-listener-service"))
            .build();
    }
}