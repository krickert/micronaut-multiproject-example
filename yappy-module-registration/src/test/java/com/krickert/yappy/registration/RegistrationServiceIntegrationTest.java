package com.krickert.yappy.registration;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.health.HealthServicesRequest;
import com.ecwid.consul.v1.health.model.HealthService;
import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.api.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for RegistrationService using mocked services.
 * This test starts mock gRPC servers and verifies that modules can register through the engine.
 */
@MicronautTest(environments = "test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RegistrationServiceIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationServiceIntegrationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    RegistrationService registrationService;
    
    @Property(name = "test-module.host")
    String testModuleHost;
    
    @Property(name = "test-module.grpc.port")
    Integer testModuleGrpcPort;
    
    @Property(name = "consul.host")
    String consulHost;
    
    @Property(name = "consul.port")
    Integer consulPort;
    
    private Server mockEngineServer;
    private int mockEnginePort;
    private MockEngineRegistrationService mockEngineService;
    private ConsulClient consulClient;
    
    private Server mockModuleServer;
    private MockPipeStepProcessor mockModuleService;
    
    @BeforeAll
    void setupMockEngine() throws IOException {
        // Start a mock engine gRPC server that implements ModuleRegistrationService
        mockEnginePort = findAvailablePort();
        mockEngineService = new MockEngineRegistrationService();
        
        mockEngineServer = ServerBuilder.forPort(mockEnginePort)
                .addService(mockEngineService)
                .build()
                .start();
        
        LOG.info("Started mock engine gRPC server on port {}", mockEnginePort);
        
        // Start a mock module gRPC server that implements PipeStepProcessor
        mockModuleService = new MockPipeStepProcessor();
        
        mockModuleServer = ServerBuilder.forPort(testModuleGrpcPort)
                .addService(mockModuleService)
                .build()
                .start();
        
        LOG.info("Started mock module gRPC server on port {}", testModuleGrpcPort);
        
        // Create Consul client for verification (this will be a mock in simplified test)
        consulClient = new ConsulClient(consulHost, consulPort);
    }
    
    @AfterAll
    void tearDownMockEngine() {
        if (mockEngineServer != null) {
            mockEngineServer.shutdownNow();
            try {
                mockEngineServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.error("Error shutting down mock engine", e);
            }
        }
        if (mockModuleServer != null) {
            mockModuleServer.shutdownNow();
            try {
                mockModuleServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.error("Error shutting down mock module", e);
            }
        }
    }
    
    @BeforeEach
    void resetMocks() {
        mockEngineService.reset();
    }
    
    @Test
    void testSuccessfulModuleRegistration() throws InterruptedException {
        // Given - we have a test module from Test Resources
        LOG.info("Test module available at {}:{}", testModuleHost, testModuleGrpcPort);
        
        // First verify the test module is responding
        ManagedChannel moduleChannel = ManagedChannelBuilder
                .forAddress(testModuleHost, testModuleGrpcPort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub moduleStub = 
                    PipeStepProcessorGrpc.newBlockingStub(moduleChannel);
            
            ServiceRegistrationData moduleData = moduleStub.getServiceRegistration(Empty.getDefaultInstance());
            LOG.info("Module reports: {}", moduleData.getModuleName());
            assertFalse(moduleData.getModuleName().isEmpty(), "Module should have a name");
            
        } finally {
            moduleChannel.shutdown();
            moduleChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // When - we register the module through our mock engine
        String engineEndpoint = "localhost:" + mockEnginePort;
        registrationService.registerModule(
                testModuleHost, 
                testModuleGrpcPort, 
                engineEndpoint,
                "test-module-instance",
                "GRPC",
                "grpc.health.v1.Health/Check",
                "1.0.0"
        );
        
        // Then - the engine should have received the registration
        assertTrue(mockEngineService.wasRegistrationReceived(), "Engine should have received registration");
        RegisterModuleRequest request = mockEngineService.getLastRequest();
        assertNotNull(request, "Request should not be null");
        assertEquals(testModuleHost, request.getHost());
        assertEquals(testModuleGrpcPort.intValue(), request.getPort());
        assertEquals("test-module-instance", request.getInstanceServiceName());
        assertEquals(HealthCheckType.GRPC, request.getHealthCheckType());
        assertEquals("grpc.health.v1.Health/Check", request.getHealthCheckEndpoint());
        assertEquals("1.0.0", request.getModuleSoftwareVersion());
    }
    
    @Test
    void testRegistrationWithHttpHealthCheck() {
        // Given
        String engineEndpoint = "localhost:" + mockEnginePort;
        
        // When - register with HTTP health check
        registrationService.registerModule(
                testModuleHost,
                testModuleGrpcPort,
                engineEndpoint,
                "http-test-instance",
                "HTTP",
                "/health",
                null
        );
        
        // Then
        assertTrue(mockEngineService.wasRegistrationReceived());
        RegisterModuleRequest request = mockEngineService.getLastRequest();
        assertEquals(HealthCheckType.HTTP, request.getHealthCheckType());
        assertEquals("/health", request.getHealthCheckEndpoint());
        assertFalse(request.hasModuleSoftwareVersion());
    }
    
    @Test
    void testRegistrationFailure() {
        // Given - configure mock to return failure
        mockEngineService.setShouldFail(true);
        String engineEndpoint = "localhost:" + mockEnginePort;
        
        // When/Then - registration should throw exception
        assertThrows(RuntimeException.class, () -> {
            registrationService.registerModule(
                    testModuleHost,
                    testModuleGrpcPort,
                    engineEndpoint,
                    "failing-instance",
                    "GRPC",
                    "grpc.health.v1.Health/Check",
                    null
            );
        });
    }
    
    private int findAvailablePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find available port", e);
        }
    }
    
    /**
     * Mock implementation of the engine's ModuleRegistrationService
     */
    private static class MockEngineRegistrationService extends ModuleRegistrationServiceGrpc.ModuleRegistrationServiceImplBase {
        private boolean registrationReceived = false;
        private RegisterModuleRequest lastRequest;
        private boolean shouldFail = false;
        
        @Override
        public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
            LOG.info("Mock engine received registration for: {}", request.getImplementationId());
            registrationReceived = true;
            lastRequest = request;
            
            RegisterModuleResponse.Builder responseBuilder = RegisterModuleResponse.newBuilder();
            
            if (shouldFail) {
                responseBuilder
                        .setSuccess(false)
                        .setMessage("Mock failure for testing");
            } else {
                responseBuilder
                        .setSuccess(true)
                        .setMessage("Module registered successfully")
                        .setRegisteredServiceId("mock-service-" + System.currentTimeMillis())
                        .setCalculatedConfigDigest("mock-digest");
            }
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }
        
        void reset() {
            registrationReceived = false;
            lastRequest = null;
            shouldFail = false;
        }
        
        boolean wasRegistrationReceived() {
            return registrationReceived;
        }
        
        RegisterModuleRequest getLastRequest() {
            return lastRequest;
        }
        
        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
        }
    }
    
    /**
     * Mock implementation of PipeStepProcessor for testing
     */
    private static class MockPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        
        @Override
        public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
            ServiceRegistrationData response = ServiceRegistrationData.newBuilder()
                    .setModuleName("test-chunker")
                    .setJsonConfigSchema("{\"type\":\"object\",\"properties\":{\"test\":\"config\"}}")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}