package com.krickert.yappy.registration;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.api.*;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Disabled("TODO: Fix this test to use Test Resources instead of manual mock servers. " +
         "This requires implementing ModuleRegistrationService in the engine first.")
class RegistrationServiceTest {

    @Inject
    RegistrationService registrationService;

    private Server moduleServer;
    private Server engineServer;
    private int modulePort;
    private int enginePort;
    
    private boolean registrationReceived = false;
    private RegisterModuleRequest lastRequest;

    @BeforeEach
    void setUp() throws IOException {
        // Start mock module server
        modulePort = findAvailablePort();
        moduleServer = ServerBuilder.forPort(modulePort)
                .addService(new MockPipeStepProcessor())
                .build()
                .start();

        // Start mock engine server
        enginePort = findAvailablePort();
        engineServer = ServerBuilder.forPort(enginePort)
                .addService(new MockModuleRegistrationService())
                .build()
                .start();
    }

    @AfterEach
    void tearDown() {
        if (moduleServer != null) {
            moduleServer.shutdownNow();
        }
        if (engineServer != null) {
            engineServer.shutdownNow();
        }
    }

    @Test
    void testSuccessfulRegistration() {
        // Given
        String moduleHost = "localhost";
        String engineEndpoint = "localhost:" + enginePort;
        String instanceName = "test-module-instance";
        String healthCheckType = "GRPC";
        String healthCheckPath = "grpc.health.v1.Health/Check";
        String moduleVersion = "1.0.0";

        // When
        registrationService.registerModule(
                moduleHost, modulePort, engineEndpoint,
                instanceName, healthCheckType, healthCheckPath, moduleVersion
        );

        // Then
        assertTrue(registrationReceived, "Registration should have been received by engine");
        assertNotNull(lastRequest, "Request should have been captured");
        assertEquals("test-module", lastRequest.getImplementationId());
        assertEquals(instanceName, lastRequest.getInstanceServiceName());
        assertEquals(moduleHost, lastRequest.getHost());
        assertEquals(modulePort, lastRequest.getPort());
        assertEquals(HealthCheckType.GRPC, lastRequest.getHealthCheckType());
        assertEquals(healthCheckPath, lastRequest.getHealthCheckEndpoint());
        assertEquals(moduleVersion, lastRequest.getModuleSoftwareVersion());
        assertEquals("{\"test\": \"schema\"}", lastRequest.getInstanceCustomConfigJson());
    }

    @Test
    void testRegistrationWithDefaultInstanceName() {
        // Given
        String moduleHost = "localhost";
        String engineEndpoint = "localhost:" + enginePort;
        String healthCheckType = "HTTP";
        String healthCheckPath = "/health";

        // When
        registrationService.registerModule(
                moduleHost, modulePort, engineEndpoint,
                null, healthCheckType, healthCheckPath, null
        );

        // Then
        assertTrue(registrationReceived);
        assertEquals("test-module-instance", lastRequest.getInstanceServiceName());
        assertEquals(HealthCheckType.HTTP, lastRequest.getHealthCheckType());
    }

    @Test
    void testRegistrationFailure() {
        // Given - use MockFailingRegistrationService
        engineServer.shutdownNow();
        enginePort = findAvailablePort();
        try {
            engineServer = ServerBuilder.forPort(enginePort)
                    .addService(new MockFailingRegistrationService())
                    .build()
                    .start();
        } catch (IOException e) {
            fail("Failed to start engine server");
        }

        String moduleHost = "localhost";
        String engineEndpoint = "localhost:" + enginePort;

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            registrationService.registerModule(
                    moduleHost, modulePort, engineEndpoint,
                    "test", "GRPC", "/health", null
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

    // Mock implementations
    private class MockPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        @Override
        public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
            ServiceRegistrationData response = ServiceRegistrationData.newBuilder()
                    .setModuleName("test-module")
                    .setJsonConfigSchema("{\"test\": \"schema\"}")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void processData(com.krickert.search.sdk.ProcessRequest request,
                              StreamObserver<com.krickert.search.sdk.ProcessResponse> responseObserver) {
            // Not used in this test
            responseObserver.onCompleted();
        }
    }

    private class MockModuleRegistrationService extends ModuleRegistrationServiceGrpc.ModuleRegistrationServiceImplBase {
        @Override
        public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
            registrationReceived = true;
            lastRequest = request;

            RegisterModuleResponse response = RegisterModuleResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module registered successfully")
                    .setRegisteredServiceId("test-service-id")
                    .setCalculatedConfigDigest("test-digest")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    private class MockFailingRegistrationService extends ModuleRegistrationServiceGrpc.ModuleRegistrationServiceImplBase {
        @Override
        public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
            RegisterModuleResponse response = RegisterModuleResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Registration failed for testing")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}