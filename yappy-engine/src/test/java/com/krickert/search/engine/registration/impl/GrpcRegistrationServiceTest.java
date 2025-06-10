package com.krickert.search.engine.registration.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.engine.health.ModuleHealthMonitor;
import com.krickert.search.engine.registration.ModuleRegistrationValidator;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.agent.FullService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class GrpcRegistrationServiceTest {
    
    @Mock
    private ConsulBusinessOperationsService consulBusinessOpsService;
    
    @Mock
    private ModuleRegistrationValidator validator;
    
    @Mock
    private ModuleHealthMonitor healthMonitor;
    
    @Mock
    private SchemaValidationService schemaValidationService;
    
    @Inject
    private ObjectMapper objectMapper;
    
    private GrpcRegistrationService registrationService;
    
    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        registrationService = new GrpcRegistrationService(
                consulBusinessOpsService, 
                validator, 
                objectMapper,
                healthMonitor
        );
        
        // Setup default mocks
        when(schemaValidationService.isValidJson(anyString()))
                .thenReturn(Mono.just(true));
    }
    
    @Test
    @DisplayName("Should successfully register module and start health monitoring")
    void testSuccessfulRegistration() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"key\":\"value\"}")
                .build();
        
        when(validator.validate(any(RegisterModuleRequest.class)))
                .thenReturn(Mono.just(ModuleRegistrationValidator.ValidationResult.valid()));
        
        when(consulBusinessOpsService.registerService(any(Registration.class)))
                .thenReturn(Mono.empty());
        
        when(healthMonitor.startMonitoring(any(ModuleInfo.class), any(HealthCheckType.class), anyString()))
                .thenReturn(Mono.empty());
        
        // When
        StepVerifier.create(registrationService.registerModule(request))
                .assertNext(response -> {
                    assertTrue(response.getSuccess());
                    assertEquals("Module registered successfully", response.getMessage());
                    assertNotNull(response.getRegisteredServiceId());
                    assertNotNull(response.getCalculatedConfigDigest());
                })
                .verifyComplete();
        
        // Then
        verify(validator).validate(request);
        verify(consulBusinessOpsService).registerService(any(Registration.class));
        
        // Verify health monitoring was started
        ArgumentCaptor<ModuleInfo> moduleInfoCaptor = ArgumentCaptor.forClass(ModuleInfo.class);
        verify(healthMonitor).startMonitoring(
                moduleInfoCaptor.capture(), 
                eq(HealthCheckType.HTTP), 
                eq("/health")
        );
        
        ModuleInfo capturedModuleInfo = moduleInfoCaptor.getValue();
        assertEquals("test-service", capturedModuleInfo.getServiceName());
        assertEquals("localhost", capturedModuleInfo.getHost());
        assertEquals(8080, capturedModuleInfo.getPort());
    }
    
    @Test
    @DisplayName("Should handle validation failure")
    void testValidationFailure() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("") // Invalid - empty
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setInstanceCustomConfigJson("{}")
                .build();
        
        when(validator.validate(any(RegisterModuleRequest.class)))
                .thenReturn(Mono.just(ModuleRegistrationValidator.ValidationResult.error("Implementation ID is required")));
        
        // When
        StepVerifier.create(registrationService.registerModule(request))
                .assertNext(response -> {
                    assertFalse(response.getSuccess());
                    assertTrue(response.getMessage().contains("Implementation ID is required"));
                })
                .verifyComplete();
        
        // Then
        verify(validator).validate(request);
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
        verify(healthMonitor, never()).startMonitoring(any(), any(), any());
    }
    
    @Test
    @DisplayName("Should continue registration even if health monitoring fails to start")
    void testHealthMonitoringFailure() {
        // Given
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.GRPC)
                .setInstanceCustomConfigJson("{}")
                .build();
        
        when(validator.validate(any(RegisterModuleRequest.class)))
                .thenReturn(Mono.just(ModuleRegistrationValidator.ValidationResult.valid()));
        
        when(consulBusinessOpsService.registerService(any(Registration.class)))
                .thenReturn(Mono.empty());
        
        when(healthMonitor.startMonitoring(any(ModuleInfo.class), any(HealthCheckType.class), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Health monitor error")));
        
        // When
        StepVerifier.create(registrationService.registerModule(request))
                .assertNext(response -> {
                    assertTrue(response.getSuccess());
                    assertEquals("Module registered successfully", response.getMessage());
                })
                .verifyComplete();
        
        // Then
        verify(healthMonitor).startMonitoring(any(), eq(HealthCheckType.GRPC), any());
    }
    
    @Test
    @DisplayName("Should deregister module and stop health monitoring")
    void testDeregistration() {
        // Given
        String serviceId = "test-service-123";
        
        when(healthMonitor.stopMonitoring(serviceId))
                .thenReturn(Mono.empty());
        
        when(consulBusinessOpsService.deregisterService(serviceId))
                .thenReturn(Mono.empty());
        
        // When
        StepVerifier.create(registrationService.deregisterModule(serviceId))
                .verifyComplete();
        
        // Then
        verify(healthMonitor).stopMonitoring(serviceId);
        verify(consulBusinessOpsService).deregisterService(serviceId);
    }
    
    @Test
    @DisplayName("Should continue deregistration even if health monitor stop fails")
    void testDeregistrationWithHealthMonitorFailure() {
        // Given
        String serviceId = "test-service-456";
        
        when(healthMonitor.stopMonitoring(serviceId))
                .thenReturn(Mono.error(new RuntimeException("Failed to stop monitoring")));
        
        when(consulBusinessOpsService.deregisterService(serviceId))
                .thenReturn(Mono.empty());
        
        // When
        StepVerifier.create(registrationService.deregisterModule(serviceId))
                .verifyComplete();
        
        // Then
        verify(healthMonitor).stopMonitoring(serviceId);
        verify(consulBusinessOpsService).deregisterService(serviceId);
    }
    
    @Test
    @DisplayName("Should check module health from health monitor")
    void testIsModuleHealthy() {
        // Given
        String serviceId = "healthy-module-123";
        
        when(healthMonitor.getCachedHealthStatus(serviceId))
                .thenReturn(Mono.just(
                        com.krickert.search.grpc.ModuleHealthStatus.newBuilder()
                                .setServiceId(serviceId)
                                .setIsHealthy(true)
                                .build()
                ));
        
        // When
        StepVerifier.create(registrationService.isModuleHealthy(serviceId))
                .expectNext(true)
                .verifyComplete();
        
        // Then
        verify(healthMonitor).getCachedHealthStatus(serviceId);
        verify(consulBusinessOpsService, never()).getAgentServiceDetails(any());
    }
    
    @Test
    @DisplayName("Should fall back to Consul when module not in health monitor")
    void testIsModuleHealthyFallback() {
        // Given
        String serviceId = "unknown-module-789";
        
        when(healthMonitor.getCachedHealthStatus(serviceId))
                .thenReturn(Mono.empty());
        
        FullService mockService = mock(FullService.class);
        when(consulBusinessOpsService.getAgentServiceDetails(serviceId))
                .thenReturn(Mono.just(Optional.of(mockService))); // Mock service exists
        
        // When
        StepVerifier.create(registrationService.isModuleHealthy(serviceId))
                .expectNext(true)
                .verifyComplete();
        
        // Then
        verify(healthMonitor).getCachedHealthStatus(serviceId);
        verify(consulBusinessOpsService).getAgentServiceDetails(serviceId);
    }
    
    @Test
    @DisplayName("Should handle custom config with schema validation")
    void testCustomConfigProcessing() {
        // Given
        String customConfig = """
                {
                    "timeout": 5000,
                    "retries": 3,
                    "endpoints": ["http://localhost:8080", "http://localhost:8081"]
                }
                """;
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("config-module")
                .setInstanceServiceName("config-service")
                .setHost("localhost")
                .setPort(9090)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setInstanceCustomConfigJson(customConfig)
                .build();
        
        when(validator.validate(any(RegisterModuleRequest.class)))
                .thenReturn(Mono.just(ModuleRegistrationValidator.ValidationResult.valid()));
        
        when(consulBusinessOpsService.registerService(any(Registration.class)))
                .thenReturn(Mono.empty());
        
        when(healthMonitor.startMonitoring(any(ModuleInfo.class), any(HealthCheckType.class), anyString()))
                .thenReturn(Mono.empty());
        
        // When
        StepVerifier.create(registrationService.registerModule(request))
                .assertNext(response -> {
                    assertTrue(response.getSuccess());
                    assertNotNull(response.getCalculatedConfigDigest());
                    assertNotNull(response.getCanonicalConfigJsonBase64());
                    
                    // Verify the config was processed correctly
                    String decodedConfig = new String(
                            java.util.Base64.getDecoder().decode(response.getCanonicalConfigJsonBase64())
                    );
                    assertTrue(decodedConfig.contains("timeout"));
                    assertTrue(decodedConfig.contains("5000"));
                })
                .verifyComplete();
        
        // Then
        ArgumentCaptor<Registration> registrationCaptor = ArgumentCaptor.forClass(Registration.class);
        verify(consulBusinessOpsService).registerService(registrationCaptor.capture());
        
        Registration capturedRegistration = registrationCaptor.getValue();
        assertTrue(capturedRegistration.getTags().stream()
                .anyMatch(tag -> tag.startsWith("yappy-config-digest=")));
    }
}