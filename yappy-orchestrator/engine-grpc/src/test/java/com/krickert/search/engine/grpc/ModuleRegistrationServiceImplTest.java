package com.krickert.search.engine.grpc;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import com.krickert.yappy.registration.api.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ModuleRegistrationServiceImplTest {
    
    private ModuleRegistrationServiceImpl registrationService;
    private ConsulClient mockConsulClient;
    
    @BeforeEach
    void setUp() {
        mockConsulClient = mock(ConsulClient.class);
        registrationService = new ModuleRegistrationServiceImpl(mockConsulClient);
    }
    
    @Test
    void testRegisterModule_Success() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-instance")
                .setHost("localhost")
                .setPort(50051)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .setInstanceCustomConfigJson("{\"test\": \"config\"}")
                .setModuleSoftwareVersion("1.0.0")
                .build();
        
        TestResponseObserver<RegisterModuleResponse> responseObserver = new TestResponseObserver<>();
        
        // When
        registrationService.registerModule(request, responseObserver);
        
        // Then
        assertTrue(responseObserver.isCompleted());
        assertNotNull(responseObserver.getResponse());
        assertTrue(responseObserver.getResponse().getSuccess());
        assertEquals("Module registered successfully", responseObserver.getResponse().getMessage());
        assertNotNull(responseObserver.getResponse().getRegisteredServiceId());
        assertFalse(responseObserver.getResponse().getCalculatedConfigDigest().isEmpty());
        
        // Verify Consul interaction
        ArgumentCaptor<NewService> serviceCaptor = ArgumentCaptor.forClass(NewService.class);
        verify(mockConsulClient).agentServiceRegister(serviceCaptor.capture());
        
        NewService registeredService = serviceCaptor.getValue();
        assertEquals("test-module", registeredService.getName());
        assertEquals("localhost", registeredService.getAddress());
        assertEquals(50051, registeredService.getPort().intValue());
        assertTrue(registeredService.getTags().contains("module"));
        assertTrue(registeredService.getTags().contains("grpc"));
        assertTrue(registeredService.getTags().contains("v1"));
        assertTrue(registeredService.getTags().contains("version:1.0.0"));
        assertEquals("test-module", registeredService.getMeta().get("implementation-id"));
        assertEquals("test-instance", registeredService.getMeta().get("instance-name"));
        assertNotNull(registeredService.getMeta().get("config-digest"));
    }
    
    @Test
    void testRegisterModule_HttpHealthCheck() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("http-module")
                .setInstanceServiceName("http-instance")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .build();
        
        TestResponseObserver<RegisterModuleResponse> responseObserver = new TestResponseObserver<>();
        
        // When
        registrationService.registerModule(request, responseObserver);
        
        // Then
        assertTrue(responseObserver.getResponse().getSuccess());
        
        ArgumentCaptor<NewService> serviceCaptor = ArgumentCaptor.forClass(NewService.class);
        verify(mockConsulClient).agentServiceRegister(serviceCaptor.capture());
        
        NewService registeredService = serviceCaptor.getValue();
        assertNotNull(registeredService.getCheck());
        assertEquals("http://localhost:8080/health", registeredService.getCheck().getHttp());
        assertEquals("10s", registeredService.getCheck().getInterval());
    }
    
    @Test
    void testRegisterModule_ConsulError() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("failing-module")
                .setInstanceServiceName("failing-instance")
                .setHost("localhost")
                .setPort(50051)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setHealthCheckEndpoint("grpc.health.v1.Health/Check")
                .build();
        
        doThrow(new RuntimeException("Consul connection error"))
                .when(mockConsulClient).agentServiceRegister(any(NewService.class));
        
        TestResponseObserver<RegisterModuleResponse> responseObserver = new TestResponseObserver<>();
        
        // When
        registrationService.registerModule(request, responseObserver);
        
        // Then
        assertTrue(responseObserver.isCompleted());
        assertNotNull(responseObserver.getResponse());
        assertFalse(responseObserver.getResponse().getSuccess());
        assertTrue(responseObserver.getResponse().getMessage().contains("Consul connection error"));
    }
    
    // Helper class for testing StreamObserver
    private static class TestResponseObserver<T> implements StreamObserver<T> {
        private T response;
        private Throwable error;
        private boolean completed = false;
        
        @Override
        public void onNext(T value) {
            this.response = value;
        }
        
        @Override
        public void onError(Throwable t) {
            this.error = t;
        }
        
        @Override
        public void onCompleted() {
            this.completed = true;
        }
        
        public T getResponse() {
            return response;
        }
        
        public Throwable getError() {
            return error;
        }
        
        public boolean isCompleted() {
            return completed;
        }
    }
}