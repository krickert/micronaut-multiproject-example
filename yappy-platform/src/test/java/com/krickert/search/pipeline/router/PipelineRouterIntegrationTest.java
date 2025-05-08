package com.krickert.search.pipeline.router;

// Core Application Classes
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder; // Mocked dependency
import com.krickert.search.engine.PipeStreamEngineGrpc; // For mock server impl

// Micronaut Test Imports
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider; // If needed for container props
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.annotation.MockBean; // For mocking KafkaForwarder

// gRPC Testing Imports
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import com.google.protobuf.Empty;

// JUnit 5 Imports
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

// Mockito Imports (Only needed for KafkaForwarder mock verification)
import static org.mockito.Mockito.*;

// Jakarta Inject
import jakarta.inject.Inject;

// Logging Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Java / Other Imports
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for PipelineRouter interacting with GrpcPipelineStepExecutor.
 * Uses @MicronautTest to load application context and Testcontainers (Consul).
 * Starts an in-process gRPC server to act as the target service and registers it with Consul.
 */
@MicronautTest(transactional = false) // Avoid transactions if not needed for config/discovery
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Manage server across tests in this class
class PipelineRouterIntegrationTest implements TestPropertyProvider { // Implement if needing container props

    private static final Logger log = LoggerFactory.getLogger(PipelineRouterIntegrationTest.class);

    // Inject REAL beans from the Micronaut context
    @Inject
    private PipelineRouter pipelineRouter;
    @Inject
    private ConfigurationService configurationService; // Assumes it reads from test Consul
    @Inject
    private GrpcPipelineStepExecutor grpcExecutor; // Real executor using real DiscoveryClient
    @Inject
    private ApplicationContext applicationContext; // To get ConsulClient bean

    // Mock KafkaForwarder as we are focusing on the gRPC path
    @Inject
    private KafkaForwarder kafkaForwarderMock;

    // --- Mock gRPC Server Setup ---
    private Server mockGrpcServer;
    private static final int MOCK_GRPC_PORT = 50059; // Ensure this port is free
    // Queue to capture PipeStreams received by the mock server's processAsync method
    private final BlockingQueue<PipeStream> receivedStreams = new LinkedBlockingQueue<>();
    // Details of the service our mock server will impersonate
    private String targetConsulServiceName; // e.g., "com.krickert.search.pipeline.service.SolrIndexerService"
    private String mockServiceIdInConsul; // Unique ID for registration

    // Factory for the KafkaForwarder mock bean
    @MockBean(KafkaForwarder.class)
    KafkaForwarder kafkaForwarder() {
        return mock(KafkaForwarder.class);
    }

    // --- Test Data ---
    // Using pipeline1 from your seed data for gRPC test
    private final String pipelineName = "pipeline1";
    // 'embedder' step publishes to Kafka AND forwards to 'solr-indexer' via gRPC
    // Let's assume 'embedder' is the step whose routing we test here.
    private final String sendingStepId = "embedder";
    private final String receivingGrpcStepId = "solr-indexer"; // The logical step ID for the gRPC target
    private final String streamId = UUID.randomUUID().toString();

    @BeforeAll
    void startAndRegisterMockGrpcServer() throws IOException {
        log.info("Setting up mock gRPC server and Consul registration...");

        // 1. Determine the target Consul service name from config
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        assertNotNull(pipelineConfig, "Setup failed: Pipeline config '" + pipelineName + "' not found. Ensure seed data is loaded.");
        PipeStepConfigurationDto receivingStepConfig = pipelineConfig.getServices().get(receivingGrpcStepId);
        assertNotNull(receivingStepConfig, "Setup failed: Receiving step config '" + receivingGrpcStepId + "' not found.");
        targetConsulServiceName = receivingStepConfig.getServiceImplementation();
        assertNotNull(targetConsulServiceName, "Setup failed: Target Consul service name is null in config for step " + receivingGrpcStepId);
        mockServiceIdInConsul = targetConsulServiceName + "-mock-" + MOCK_GRPC_PORT; // Unique ID

        // 2. Start the mock gRPC Server
        log.info("Starting mock gRPC server on port {} for service {}", MOCK_GRPC_PORT, targetConsulServiceName);
        mockGrpcServer = ServerBuilder.forPort(MOCK_GRPC_PORT)
                .addService(new MockPipeStreamEngineImpl(receivedStreams)) // Add our mock implementation
                .build()
                .start();
        log.info("Mock gRPC server started successfully.");

        // 3. Register the mock server with Consul
        registerMockServiceWithConsul();
    }

    @AfterAll
    void stopAndDeregisterMockGrpcServer() throws InterruptedException {
        if (mockGrpcServer != null) {
            log.info("Shutting down mock gRPC server and deregistering from Consul...");
            // De-register from Consul first
            deregisterMockServiceFromConsul();
            // Shutdown server
            mockGrpcServer.shutdown();
            if (!mockGrpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Mock gRPC server did not terminate gracefully after 5 seconds. Forcing shutdown.");
                mockGrpcServer.shutdownNow();
            } else {
                log.info("Mock gRPC server shut down gracefully.");
            }
        }
    }

    @BeforeEach
    void clearTestState() {
        receivedStreams.clear(); // Clear queue before each test
        reset(kafkaForwarderMock); // Reset mock interactions
    }

    @Test
    @DisplayName("Should route via gRPC and call mock server when gRPC route exists")
    void routeAndForward_shouldForwardToMockGrpcServerViaConsul() throws InterruptedException {
        // Arrange
        // Fetch the config for the *sending* step (embedder) using real ConfigService
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        PipeStepConfigurationDto sendingStepConfig = pipelineConfig.getServices().get(sendingStepId); // "embedder"
        assertNotNull(sendingStepConfig, "Sending step config '" + sendingStepId + "' not found.");
        // Verify routing rule points to the step our mock server represents
        assertNotNull(sendingStepConfig.getGrpcForwardTo(), "'embedder' step should have gRPC forward rules");
        assertTrue(sendingStepConfig.getGrpcForwardTo().contains(receivingGrpcStepId), // "solr-indexer"
                "Routing rule mismatch: 'embedder' should forward to '" + receivingGrpcStepId + "'");

        // Prepare the outgoing stream state (as if 'embedder' just finished)
        PipeStream.Builder outgoingStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setPipelineName(pipelineName)
                .setCurrentHopNumber(3) // Example hop number after embedder
                .setCurrentPipestepId(sendingStepId); // ID of the step *that just finished*

        // Act
        // Call the real router. This triggers the real executor, which discovers the mock server via Consul.
        pipelineRouter.routeAndForward(pipelineName, sendingStepConfig, outgoingStreamBuilder);

        // Assert
        // Check that the mock gRPC server received the stream via its processAsync method
        // Poll the queue with a timeout to allow for async processing and network latency (even to localhost)
        PipeStream receivedStream = receivedStreams.poll(10, TimeUnit.SECONDS); // Wait up to 10 seconds

        assertNotNull(receivedStream, "Mock gRPC server did not receive the PipeStream within timeout");

        // Verify the stream received by the mock server has the correct next step ID set
        assertEquals(streamId, receivedStream.getStreamId());
        assertEquals(pipelineName, receivedStream.getPipelineName());
        assertEquals(receivingGrpcStepId, receivedStream.getCurrentPipestepId(), "PipeStream should have target pipestep ID set for receiver");
        assertEquals(3, receivedStream.getCurrentHopNumber(), "Hop number should be unchanged by router before sending"); // Router uses hop from builder

        // Verify Kafka forwarder was also potentially called based on seed data for 'embedder'
        // Assuming 'embedder' also publishes to 'enhanced-documents' targeting 'solr-indexer'
        verify(kafkaForwarderMock, times(1)).forwardToKafka(
                eq("enhanced-documents"), // Topic name from seed data
                eq(UUID.fromString(streamId)), // Key
                any(PipeStream.class) // Can capture and check pipestep_id if needed
        );
    }

    // --- Helper Methods for Consul Interaction ---

    private void registerMockServiceWithConsul() {
        // Get the ConsulClient bean injected by Micronaut based on test resource config
        ConsulClient consulClient = applicationContext.getBean(ConsulClient.class);

        NewService newService = new NewService();
        newService.setId(mockServiceIdInConsul); // Unique ID for this test instance
        newService.setName(targetConsulServiceName); // The name DiscoveryClient will look for
        newService.setPort(MOCK_GRPC_PORT);
        // Address defaults to localhost usually, set explicitly if needed:
        // newService.setAddress("127.0.0.1");

        // Add a simple TCP health check for the mock gRPC server port
        NewService.Check check = new NewService.Check();
        check.setTcp("localhost:" + MOCK_GRPC_PORT); // Check if the port is open
        check.setInterval("10s"); // How often to check
        check.setTimeout("1s"); // Timeout for the check connection
        check.setDeregisterCriticalAfter("30s"); // Remove if check fails for 30s
        newService.setCheck(check);

        log.info("Registering mock gRPC service '{}' with ID '{}' at port {} in Consul",
                targetConsulServiceName, mockServiceIdInConsul, MOCK_GRPC_PORT);
        try {
            // Use the ConsulClient to register the service with the agent
            consulClient.agentServiceRegister(newService);
            log.info("Mock service registration request sent.");
            // Allow Consul time to process the registration and health check
            Thread.sleep(2000); // Increase delay slightly for health check to potentially pass once
        } catch (Exception e) {
            log.error("Failed to register mock service with Consul: {}", e.getMessage(), e);
            // Fail the test setup if registration doesn't work
            fail("Failed to register mock service with Consul: " + e.getMessage());
        }
    }

    private void deregisterMockServiceFromConsul() {
        try {
            ConsulClient consulClient = applicationContext.getBean(ConsulClient.class);
            log.info("Deregistering mock gRPC service '{}' with ID '{}' from Consul", targetConsulServiceName, mockServiceIdInConsul);
            consulClient.agentServiceDeregister(mockServiceIdInConsul);
            log.info("Mock service deregistered successfully.");
        } catch (Exception e) {
            // Log warning but don't fail the test during cleanup
            log.warn("Failed to deregister mock service '{}' from Consul: {}", mockServiceIdInConsul, e.getMessage());
        }
    }


    // --- Mock gRPC Service Implementation (Nested Class) ---
    private static class MockPipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
        private final BlockingQueue<PipeStream> receivedStreamsQueue;
        // Use a separate logger instance if needed inside static nested class
        private static final Logger mockLog = LoggerFactory.getLogger(MockPipeStreamEngineImpl.class);

        MockPipeStreamEngineImpl(BlockingQueue<PipeStream> receivedStreamsQueue) {
            this.receivedStreamsQueue = receivedStreamsQueue;
        }

        @Override
        public void processAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
            mockLog.info("MOCK SERVER received processAsync call for stream: {}, pipeline: {}, target step: {}",
                    request.getStreamId(), request.getPipelineName(),
                    request.hasCurrentPipestepId() ? request.getCurrentPipestepId() : "[NONE]");
            try {
                // Record the received stream for test assertion
                receivedStreamsQueue.put(request); // Use put to potentially block if queue is full (shouldn't happen in test)
                // Acknowledge receipt successfully
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
                mockLog.info("MOCK SERVER acknowledged stream {}", request.getStreamId());
            } catch (InterruptedException e) {
                mockLog.error("MOCK SERVER interrupted while adding to queue", e);
                Thread.currentThread().interrupt();
                responseObserver.onError(io.grpc.Status.INTERNAL.withDescription("Mock server interrupted").asRuntimeException());
            } catch (Exception e) {
                mockLog.error("MOCK SERVER error processing request", e);
                responseObserver.onError(io.grpc.Status.INTERNAL.withDescription("Mock server error: " + e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void process(PipeStream request, StreamObserver<PipeStream> responseObserver) {
            mockLog.warn("MOCK SERVER received unexpected SYNC process call for stream: {}", request.getStreamId());
            // Return UNIMPLEMENTED for the synchronous testing method in this mock
            responseObserver.onError(io.grpc.Status.UNIMPLEMENTED
                    .withDescription("Sync process method not implemented in this mock server")
                    .asRuntimeException());
        }
    }

    // --- TestPropertyProvider Implementation ---
    // Implement this method if you need to dynamically set properties based on Testcontainers
    // e.g., setting consul.client.host and consul.client.port
    // If micronaut-test-resources handles this automatically via yml files, this can return empty map.
    @Override
    public Map<String, String> getProperties() {
        // Example: If you have a ConsulContainer instance `consulContainer` managed by JUnit Jupiter extensions:
        // return Map.of(
        //     "consul.client.host", consulContainer.getHost(),
        //     "consul.client.port", String.valueOf(consulContainer.getMappedPort(8500))
        // );
        // If Micronaut Test Resources handles config via application-test.yml, return empty:
        return Collections.emptyMap();
    }
}
