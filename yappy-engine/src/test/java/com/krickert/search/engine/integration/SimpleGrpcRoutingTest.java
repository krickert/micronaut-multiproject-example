package com.krickert.search.engine.integration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.pipeline.impl.PipelineExecutionServiceImpl;
import com.krickert.search.engine.registration.impl.GrpcRegistrationService;
import com.krickert.search.engine.routing.impl.GrpcModuleConnector;
import com.krickert.search.engine.routing.impl.PipelineRouterImpl;
import com.krickert.search.engine.service.impl.MessageRoutingServiceImpl;
import com.krickert.search.engine.test.modules.TestSinkModule;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kiwiproject.consul.model.agent.FullService;
import org.kiwiproject.consul.model.health.ServiceHealth;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple integration test for gRPC routing without full Micronaut context.
 * This tests the core routing functionality directly.
 */
public class SimpleGrpcRoutingTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(SimpleGrpcRoutingTest.class);
    
    // Test components
    private PipelineExecutionServiceImpl pipelineExecutionService;
    private MessageRoutingServiceImpl messageRoutingService;
    private GrpcModuleConnector moduleConnector;
    private PipelineRouterImpl pipelineRouter;
    
    // Test infrastructure
    private Server grpcServer;
    private TestSinkModule testSink;
    private int grpcPort;
    private static final String TEST_PIPELINE_ID = "test-pipeline";
    private static final String TEST_SINK_MODULE_ID = "test-sink-module";
    
    @BeforeEach
    void setup() throws IOException {
        // Find a free port
        try (ServerSocket socket = new ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        
        // Create and start the test sink gRPC server
        testSink = new TestSinkModule();
        grpcServer = ServerBuilder.forPort(grpcPort)
            .addService(testSink)
            .build()
            .start();
        
        LOG.info("Started test sink gRPC server on port: {}", grpcPort);
        
        // Create components manually (without DI)
        ObjectMapper objectMapper = new ObjectMapper();
        moduleConnector = new GrpcModuleConnector(objectMapper);
        pipelineRouter = new PipelineRouterImpl();
        
        // Create a mock Kafka producer
        MockKafkaProducer kafkaProducer = new MockKafkaProducer();
        
        // Create a mock ConsulBusinessOperationsService that returns our test pipeline
        MockConsulService mockConsulService = new MockConsulService();
        
        // Create message routing service with mocked dependencies
        messageRoutingService = new MessageRoutingServiceImpl(
            null, // registrationService - not needed for this test
            mockConsulService,
            pipelineRouter,
            moduleConnector,
            kafkaProducer,
            "test-cluster"
        );
        
        // Create pipeline execution service
        pipelineExecutionService = new PipelineExecutionServiceImpl(messageRoutingService);
        
        // Reset test sink
        testSink.reset();
    }
    
    @AfterEach
    void teardown() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
            }
        }
        
        // Disconnect all modules
        if (moduleConnector != null) {
            moduleConnector.disconnectAll();
        }
    }
    
    @Test
    @DisplayName("Should process document through gRPC module directly")
    void testDirectGrpcModuleProcessing() throws Exception {
        // Create a test document
        PipeDoc testDoc = PipeDoc.newBuilder()
            .setId("test-doc-1")
            .setTitle("Test Document")
            .setBody("This is a test document for gRPC routing")
            .build();
        
        // Set expectation in test sink
        testSink.setExpectedMessageCount(1);
        
        // Create a PipeStream
        PipeStream testStream = PipeStream.newBuilder()
            .setStreamId("test-stream-1")
            .setDocument(testDoc)
            .setCurrentPipelineName(TEST_PIPELINE_ID)
            .setTargetStepName("sink-step")
            .setCurrentHopNumber(0)
            .build();
        
        // Create module info for direct connection
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
            .setServiceId(TEST_SINK_MODULE_ID)
            .setServiceName("Test Sink")
            .setHost("localhost")
            .setPort(grpcPort)
            .build();
        
        // Test direct module connection
        StepVerifier.create(moduleConnector.isModuleAvailable(moduleInfo))
            .expectNext(true)
            .verifyComplete();
        
        // Process document directly through the module connector
        Map<String, Object> stepConfig = Map.of("validation", "enabled");
        
        StepVerifier.create(
            moduleConnector.processDocument(moduleInfo, testStream, "sink-step", stepConfig)
        )
        .assertNext(response -> {
            assertNotNull(response);
            assertTrue(response.getSuccess(), "Processing should succeed");
            assertTrue(response.hasOutputDoc(), "Response should have output doc");
            assertNotNull(response.getOutputDoc());
            assertEquals("test-doc-1", response.getOutputDoc().getId());
            // Check if the sink added its marker
            assertTrue(response.getOutputDoc().getKeywordsList().contains("processed-by-test-sink"));
        })
        .verifyComplete();
        
        // Wait for the sink to receive the message
        assertTrue(testSink.awaitMessages(5, TimeUnit.SECONDS), "Test sink should receive the message");
        
        // Verify the sink received the correct data
        assertEquals(1, testSink.getProcessedCount());
        
        var receivedStreams = testSink.getReceivedStreams();
        assertEquals(1, receivedStreams.size());
        
        PipeStream receivedStream = receivedStreams.get(0);
        assertEquals("test-doc-1", receivedStream.getDocument().getId());
    }
    
    @Test
    @DisplayName("Should handle module processing failure")
    void testModuleProcessingFailure() throws Exception {
        // Configure sink to fail
        testSink.setShouldSucceed(false);
        testSink.setErrorMessage("Simulated processing failure");
        testSink.setExpectedMessageCount(1);
        
        // Create a test document
        PipeDoc testDoc = PipeDoc.newBuilder()
            .setId("test-doc-fail")
            .setTitle("Test Document for Failure")
            .setBody("This document will fail processing")
            .build();
        
        // Create a PipeStream
        PipeStream testStream = PipeStream.newBuilder()
            .setStreamId("test-stream-fail")
            .setDocument(testDoc)
            .setCurrentPipelineName(TEST_PIPELINE_ID)
            .setTargetStepName("sink-step")
            .setCurrentHopNumber(0)
            .build();
        
        // Create module info
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
            .setServiceId(TEST_SINK_MODULE_ID)
            .setServiceName("Test Sink")
            .setHost("localhost")
            .setPort(grpcPort)
            .build();
        
        // Process document directly through the module connector
        Map<String, Object> stepConfig = Map.of("validation", "enabled");
        
        StepVerifier.create(
            moduleConnector.processDocument(moduleInfo, testStream, "sink-step", stepConfig)
        )
        .assertNext(response -> {
            assertNotNull(response);
            assertFalse(response.getSuccess(), "Processing should fail");
            assertTrue(response.hasErrorDetails(), "Response should have error details");
            assertNotNull(response.getErrorDetails());
        })
        .verifyComplete();
        
        // Wait for the sink to receive the message
        assertTrue(testSink.awaitMessages(5, TimeUnit.SECONDS));
        
        // Verify failure was handled
        assertEquals(1, testSink.getProcessedCount());
    }
    
    private PipelineConfig createSingleStepPipeline() {
        // Create processor info pointing to localhost gRPC service
        PipelineStepConfig.ProcessorInfo processorInfo = 
            new PipelineStepConfig.ProcessorInfo("localhost:" + grpcPort, null);
        
        // Create a simple pipeline with one step that goes to our test sink
        PipelineStepConfig sinkStep = new PipelineStepConfig(
            "sink-step",
            StepType.SINK,
            processorInfo,
            new PipelineStepConfig.JsonConfigOptions(Map.of("validation", "enabled"))
        );
        
        Map<String, PipelineStepConfig> steps = new TreeMap<>();
        steps.put("sink-step", sinkStep);
        
        return new PipelineConfig(TEST_PIPELINE_ID, steps);
    }
    
    /**
     * Mock Kafka producer for testing without Kafka.
     */
    private static class MockKafkaProducer implements MessageRoutingServiceImpl.KafkaMessageProducer {
        @Override
        public void sendMessage(@Topic String topic, String key, byte[] value) {
            LOG.info("Mock: Would send message to topic {} with key {}", topic, key);
        }
    }
    
    /**
     * Mock Consul service for testing without Consul.
     * This is a partial implementation that only provides the methods needed for testing.
     */
    private class MockConsulService extends ConsulBusinessOperationsService {
        
        public MockConsulService() {
            super(null, null, "", "", "", null, null, null, null);
        }
        
        @Override
        public Mono<Optional<PipelineConfig>> getSpecificPipelineConfig(String clusterName, String pipelineName) {
            if (TEST_PIPELINE_ID.equals(pipelineName)) {
                return Mono.just(Optional.of(createSingleStepPipeline()));
            }
            return Mono.just(Optional.empty());
        }
        
        @Override
        public Mono<Optional<FullService>> getAgentServiceDetails(String serviceId) {
            // Return empty for now, as the module connector should handle this differently
            return Mono.just(Optional.empty());
        }
    }
}