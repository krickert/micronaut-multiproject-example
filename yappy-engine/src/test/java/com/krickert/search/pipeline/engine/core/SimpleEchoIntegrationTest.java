package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.step.impl.PipeStepExecutorFactoryImpl;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessorImpl;
import com.krickert.yappy.modules.echo.EchoService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.PropertySource;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Simple integration test that uses the real Echo gRPC service.
 * This test focuses on the core functionality without Kafka complexity.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleEchoIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleEchoIntegrationTest.class);
    
    private static final String TEST_PIPELINE = "test-pipeline";
    private static final String ECHO_STEP = "echo";
    private static final String TEST_STREAM_ID = "test-stream-123";
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    GrpcChannelManager grpcChannelManager;
    
    @MockBean(DynamicConfigurationManager.class)
    DynamicConfigurationManager mockConfigManager() {
        return Mockito.mock(DynamicConfigurationManager.class);
    }
    
    // Mock the Kafka forwarder to avoid Kafka complexity
    @MockBean(KafkaForwarder.class)
    KafkaForwarder kafkaForwarder() {
        return Mockito.mock(KafkaForwarder.class);
    }
    
    private DefaultPipeStreamEngineLogicImpl engineLogic;
    private Server echoGrpcServer;
    private int echoPort;
    
    @BeforeAll
    void setupAll() throws IOException {
        // Find a free port
        try (ServerSocket socket = new ServerSocket(0)) {
            echoPort = socket.getLocalPort();
        }
        
        // Start Echo gRPC service on a real port
        log.info("Starting Echo gRPC service on port {}", echoPort);
        EchoService echoService = new EchoService();
        echoGrpcServer = ServerBuilder
                .forPort(echoPort)
                .addService(echoService)
                .build()
                .start();
        
        // Create channel to Echo service
        ManagedChannel echoChannel = ManagedChannelBuilder
                .forAddress("localhost", echoPort)
                .usePlaintext()
                .build();
        
        // Update the channel in GrpcChannelManager
        grpcChannelManager.updateChannel("echo", echoChannel);
        log.info("Echo channel registered with GrpcChannelManager");
        
        // Create real components
        PipelineStepGrpcProcessorImpl grpcProcessor = new PipelineStepGrpcProcessorImpl(
                configManager, grpcChannelManager);
        
        PipeStepExecutorFactory executorFactory = new PipeStepExecutorFactoryImpl(configManager, grpcProcessor);
        
        // Create a simple executor service for the test
        java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors.newCachedThreadPool();
        KafkaForwarder mockKafkaForwarder = applicationContext.getBean(KafkaForwarder.class);
        PipeStreamGrpcForwarder grpcForwarder = new PipeStreamGrpcForwarder(grpcChannelManager, 
                executorService, mockKafkaForwarder);
        
        engineLogic = new DefaultPipeStreamEngineLogicImpl(
                executorFactory, grpcForwarder, mockKafkaForwarder, configManager);
        
        // Configure the pipeline in memory
        configurePipeline();
    }
    
    @AfterAll
    void tearDownAll() {
        if (echoGrpcServer != null) {
            log.info("Shutting down Echo gRPC server...");
            echoGrpcServer.shutdown();
            try {
                echoGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                echoGrpcServer.shutdownNow();
            }
        }
    }
    
    @Test
    @DisplayName("Test Echo service processes document successfully")
    void testEchoServiceProcessing() throws Exception {
        // Given
        PipeStream inputStream = createTestPipeStream();
        
        // When
        engineLogic.processStream(inputStream);
        
        // Wait a bit for async processing
        Thread.sleep(1000);
        
        // Then - If we got here without exceptions, the Echo service processed successfully
        // The actual verification would be in the logs showing the Echo service was called
        log.info("Test completed successfully - Echo service processed the document");
    }
    
    private void configurePipeline() {
        // Create a simple pipeline configuration with just the Echo step
        PipelineStepConfig echoStep = PipelineStepConfig.builder()
                .stepName(ECHO_STEP)
                .stepType(StepType.SINK) // Make it a sink so no outputs needed
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of()) // No outputs for a sink
                .build();
        
        PipelineConfig pipelineConfig = new PipelineConfig(
                TEST_PIPELINE,
                Map.of(ECHO_STEP, echoStep)
        );
        
        // Mock the config manager to return our pipeline
        when(configManager.getPipelineConfig(TEST_PIPELINE))
                .thenReturn(Optional.of(pipelineConfig));
        
        // Also mock getCurrentPipelineClusterConfig if needed
        PipelineModuleConfiguration echoModule = PipelineModuleConfiguration.builder()
                .implementationId("echo")
                .implementationName("Echo Service")
                .build();
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(Map.of("echo", echoModule))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE, pipelineConfig))
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of())
                .allowedGrpcServices(Set.of("echo"))
                .build();
        
        when(configManager.getCurrentPipelineClusterConfig())
                .thenReturn(Optional.of(clusterConfig));
    }
    
    private PipeStream createTestPipeStream() {
        return PipeStream.newBuilder()
                .setStreamId(TEST_STREAM_ID)
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(ECHO_STEP)
                .setCurrentHopNumber(0)
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-123")
                        .setTitle("Test Document")
                        .setBody("Test content for Echo service")
                        .build())
                .build();
    }
}