package com.krickert.search.pipeline.integration;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.admin.PipelineKafkaTopicService;
import com.krickert.search.pipeline.test.dummy.DummyPipeStepProcessor;
import com.krickert.search.pipeline.test.dummy.StandaloneDummyGrpcServer;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating the NEW registration flow where:
 * 1. Module exposes GetServiceRegistration
 * 2. We query the module for its info
 * 3. We register the module with the engine
 * 4. Engine handles Consul registration
 * 5. Engine uses the module for processing
 * 
 * This is much simpler than the old flow!
 */
@MicronautTest(environments = {"test", "engine-dummy-test"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "app.config.cluster-name", value = NewRegistrationFlowIntegrationTest.TEST_CLUSTER_NAME)
@Property(name = "app.engine.bootstrapper.enabled", value = "false")
@Property(name = "grpc.server.port", value = "50050") // Engine's gRPC port
public class NewRegistrationFlowIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(NewRegistrationFlowIntegrationTest.class);
    
    static final String TEST_CLUSTER_NAME = "test-cluster-new-registration";
    private static final String TEST_PIPELINE_NAME = "test-pipeline";
    private static final String TEST_STEP_NAME = "dummy-step";
    private static final int MODULE_GRPC_PORT = 50062; // Module's gRPC port
    
    @Inject
    PipeStreamEngine pipeStreamEngine;
    
    @Inject
    PipelineKafkaTopicService pipelineKafkaTopicService;
    
    @Inject
    DynamicConfigurationManager dynamicConfigurationManager;
    
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;
    
    @Inject
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Inject
    @Property(name = "apicurio.registry.url")
    String apicurioRegistryUrl;
    
    private StandaloneDummyGrpcServer dummyGrpcServer;
    private KafkaConsumer<UUID, PipeStream> testConsumer;
    private String outputTopic;
    
    @BeforeEach
    void setUp() throws Exception {
        LOG.info("=== Setting up NEW registration flow test ===");
        
        // Step 1: Start the module with GetServiceRegistration support
        LOG.info("Starting dummy module on port {}", MODULE_GRPC_PORT);
        DummyPipeStepProcessor processor = new DummyPipeStepProcessor("append", " [PROCESSED-NEW-FLOW]");
        dummyGrpcServer = new StandaloneDummyGrpcServer(MODULE_GRPC_PORT, processor);
        dummyGrpcServer.start();
        
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> dummyGrpcServer.isRunning());
        
        // Step 2: Query the module for its registration info (what the CLI would do)
        LOG.info("Querying module for registration info...");
        ManagedChannel moduleChannel = ManagedChannelBuilder
                .forAddress("localhost", MODULE_GRPC_PORT)
                .usePlaintext()
                .build();
        
        ServiceRegistrationData moduleInfo;
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub moduleStub = 
                    PipeStepProcessorGrpc.newBlockingStub(moduleChannel);
            moduleInfo = moduleStub.getServiceRegistration(Empty.getDefaultInstance());
            
            LOG.info("Module reports name: {}", moduleInfo.getModuleName());
            LOG.info("Module has config schema: {}", moduleInfo.hasJsonConfigSchema());
        } finally {
            moduleChannel.shutdown();
            moduleChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // Step 3: Register the module with the engine (what the CLI would do)
        LOG.info("Registering module with engine...");
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", 50050) // Engine's gRPC port
                .usePlaintext()
                .build();
        
        try {
            YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub engineStub =
                    YappyModuleRegistrationServiceGrpc.newBlockingStub(engineChannel);
            
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId(moduleInfo.getModuleName())
                    .setInstanceServiceName("dummy-processor-test-instance")
                    .setHost("localhost")
                    .setPort(MODULE_GRPC_PORT)
                    .setHealthCheckType(HealthCheckType.GRPC)
                    .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                    .setInstanceCustomConfigJson(moduleInfo.getJsonConfigSchema())
                    .setModuleSoftwareVersion("test-1.0.0")
                    .build();
            
            RegisterModuleResponse response = engineStub.registerModule(request);
            
            assertTrue(response.getSuccess(), "Registration should succeed");
            LOG.info("Module registered successfully! Service ID: {}", response.getRegisteredServiceId());
            LOG.info("Config digest: {}", response.getCalculatedConfigDigest());
            
        } finally {
            engineChannel.shutdown();
            try {
                engineChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while waiting for channel shutdown", e);
            }
        }
        
        // Step 4: Set up pipeline configuration to use the registered module
        LOG.info("Setting up pipeline configuration...");
        
        // Create topics
        pipelineKafkaTopicService.createAllTopics(TEST_PIPELINE_NAME, TEST_STEP_NAME);
        outputTopic = pipelineKafkaTopicService.generateTopicName(TEST_PIPELINE_NAME, TEST_STEP_NAME, PipelineKafkaTopicService.TopicType.OUTPUT);
        
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> pipelineKafkaTopicService.listTopicsForStep(TEST_PIPELINE_NAME, TEST_STEP_NAME).size() == 4);
        
        // Create pipeline config that references the registered service
        // Create custom config for the step
        PipelineStepConfig.JsonConfigOptions customConfig = new PipelineStepConfig.JsonConfigOptions(
            Map.of(
                "behavior", "append",
                "simulate_error", "false"
            )
        );
        
        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("dummy-processor-test-instance", null))
                .outputs(Map.of("kafka", new PipelineStepConfig.OutputTarget(
                    outputTopic, 
                    TransportType.KAFKA,
                    null,
                    new KafkaTransportConfig(outputTopic, null)
                )))
                .customConfig(customConfig)
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE_NAME)
                .pipelineSteps(Map.of(TEST_STEP_NAME, step))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE_NAME, testPipeline))
                .build();
        
        PipelineModuleConfiguration dummyModuleConfig = PipelineModuleConfiguration.builder()
                .implementationId("dummy-processor-test-instance")
                .implementationName("Dummy Test Service Instance")
                .build();
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(Map.of("dummy-processor-test-instance", dummyModuleConfig))
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(outputTopic))
                .allowedGrpcServices(Set.of("dummy-processor-test-instance"))
                .build();
        
        // Store configuration in Consul
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        
        // Set up consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.avro.AvroKafkaDeserializer");
        consumerProps.put("apicurio.registry.url", apicurioRegistryUrl);
        consumerProps.put("apicurio.registry.auto-register", "true");
        consumerProps.put("apicurio.registry.find-latest", "true");
        
        testConsumer = new KafkaConsumer<>(consumerProps);
        testConsumer.subscribe(List.of(outputTopic));
        
        LOG.info("=== Setup complete! ===");
    }
    
    @Test
    @DisplayName("Should process documents using module registered via new flow")
    void testProcessingWithNewRegistrationFlow() {
        // Create test document
        PipeDoc testDoc = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setTitle("Test Document")
                .setBody("This is a test document for the new registration flow.")
                .setSourceUri("integration-test")
                .build();
        
        PipeStream testStream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setCurrentPipelineName(TEST_PIPELINE_NAME)
                .setTargetStepName(TEST_STEP_NAME)
                .setDocument(testDoc)
                .setCurrentHopNumber(0)
                .build();
        
        // Process document
        LOG.info("Processing document through engine...");
        pipeStreamEngine.processStream(testStream);
        
        // Verify output
        LOG.info("Waiting for processed document in Kafka...");
        PipeStream processedStream;
        
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(100));
            if (!records.isEmpty()) {
                LOG.info("Received {} records from Kafka", records.count());
                return true;
            }
            return false;
        });
        
        ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
        assertEquals(1, records.count(), "Should receive exactly one processed document");
        
        ConsumerRecord<UUID, PipeStream> record = records.iterator().next();
        processedStream = record.value();
        
        assertNotNull(processedStream);
        assertEquals(testDoc.getId(), processedStream.getDocument().getId());
        
        // Verify processing happened
        PipeDoc processedDoc = processedStream.getDocument();
        assertEquals("Test Document [PROCESSED-NEW-FLOW]", processedDoc.getTitle(), 
                "Title should have the processing suffix");
        assertTrue(processedDoc.getBody().contains("[Processed by DummyPipeStepProcessor]"),
                "Body should contain processing marker");
        
        // Verify metadata
        assertTrue(processedDoc.hasCustomData());
        assertTrue(processedDoc.getCustomData().containsFields("dummy_processed"));
        assertEquals("true", processedDoc.getCustomData().getFieldsOrThrow("dummy_processed").getStringValue());
        assertEquals("append", processedDoc.getCustomData().getFieldsOrThrow("dummy_behavior").getStringValue());
        
        LOG.info("✅ Document processed successfully using NEW registration flow!");
    }
    
    @Test
    @DisplayName("Should handle multiple registrations of same module")
    void testMultipleRegistrations() {
        // Register the same module again with a different instance name
        LOG.info("Registering module again with different instance name...");
        
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", 50050)
                .usePlaintext()
                .build();
        
        try {
            YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub engineStub =
                    YappyModuleRegistrationServiceGrpc.newBlockingStub(engineChannel);
            
            RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                    .setImplementationId("dummy-processor")
                    .setInstanceServiceName("dummy-processor-test-instance-2")
                    .setHost("localhost")
                    .setPort(MODULE_GRPC_PORT)
                    .setHealthCheckType(HealthCheckType.GRPC)
                    .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                    .setModuleSoftwareVersion("test-1.0.1")
                    .build();
            
            RegisterModuleResponse response = engineStub.registerModule(request);
            
            assertTrue(response.getSuccess(), "Second registration should also succeed");
            assertNotNull(response.getRegisteredServiceId());
            LOG.info("Second registration successful! Service ID: {}", response.getRegisteredServiceId());
            
        } finally {
            engineChannel.shutdown();
            try {
                engineChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while waiting for channel shutdown", e);
            }
        }
    }
    
    @AfterEach
    void tearDown() {
        LOG.info("Cleaning up test...");
        
        if (testConsumer != null) {
            testConsumer.close();
        }
        
        if (dummyGrpcServer != null) {
            try {
                dummyGrpcServer.stop();
            } catch (Exception e) {
                LOG.error("Error stopping dummy gRPC server", e);
            }
        }
        
        // Note: In the new flow, the engine handles Consul deregistration
        // We don't need to manually clean up Consul registrations!
        
        LOG.info("Cleanup complete");
    }
}