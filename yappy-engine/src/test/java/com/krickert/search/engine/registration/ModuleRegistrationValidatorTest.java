package com.krickert.search.engine.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.engine.registration.impl.ModuleRegistrationValidatorImpl;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for module registration validator.
 */
@MicronautTest
public class ModuleRegistrationValidatorTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationValidatorTest.class);
    
    @Inject
    ObjectMapper objectMapper;
    
    private SchemaValidationService createMockSchemaValidationService() {
        SchemaValidationService mockService = Mockito.mock(SchemaValidationService.class);
        // Mock isValidJson to always return true for these tests
        Mockito.when(mockService.isValidJson(Mockito.anyString()))
                .thenReturn(Mono.just(true));
        return mockService;
    }
    
    @Test
    @DisplayName("Validator rejects empty implementation ID")
    void testRejectEmptyImplementationId() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("")  // Empty
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{}")
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Implementation ID is required", result.errorMessage());
        assertEquals(ModuleRegistrationValidator.ValidationLevel.ERROR, result.level());
        LOG.info("Validation correctly rejected empty implementation ID");
    }
    
    @Test
    @DisplayName("Validator rejects invalid port")
    void testRejectInvalidPort() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(99999)  // Invalid port
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{}")
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals("Port must be between 1 and 65535", result.errorMessage());
        LOG.info("Validation correctly rejected invalid port");
    }
    
    @Test
    @DisplayName("Validator rejects invalid JSON")
    void testRejectInvalidJson() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{invalid json")  // Invalid JSON
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("Invalid JSON format"));
        LOG.info("Validation correctly rejected invalid JSON: {}", result.errorMessage());
    }
    
    @Test
    @DisplayName("Validator rejects unreachable host")
    void testRejectUnreachableHost() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("unreachable.invalid.host")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"test\": \"config\"}")
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("Cannot connect"));
        LOG.info("Validation correctly rejected unreachable host: {}", result.errorMessage());
    }
    
    @Test
    @DisplayName("Validator accepts valid TTL health check request")
    void testAcceptValidTTLRequest() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        // Use TTL health check which doesn't require connectivity validation
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("some-host")  // Any host is fine for TTL
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.TTL)  // TTL doesn't validate connectivity
                .setHealthCheckEndpoint("")
                .setInstanceCustomConfigJson("{\"test\": \"config\"}")
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertTrue(result.isValid(), "Validation should pass for TTL health check");
        assertNull(result.errorMessage());
        LOG.info("Validation correctly accepted TTL health check request");
    }
    
    @Test
    @DisplayName("Validator handles complex JSON configuration")
    void testComplexJsonConfiguration() {
        ModuleRegistrationValidator validator = new ModuleRegistrationValidatorImpl(
                objectMapper, createMockSchemaValidationService());
        
        String complexJson = """
                {
                    "maxContentLength": 10000000,
                    "supportedFormats": ["pdf", "docx", "txt"],
                    "extractMetadata": true,
                    "nestedConfig": {
                        "timeout": 30000,
                        "retries": 3
                    }
                }
                """;
        
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-module")
                .setInstanceServiceName("test-service")
                .setHost("some-host")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.TTL)  // Use TTL to avoid connectivity check
                .setHealthCheckEndpoint("")
                .setInstanceCustomConfigJson(complexJson)
                .build();
        
        ModuleRegistrationValidator.ValidationResult result = validator.validate(request).block();
        
        assertNotNull(result);
        assertTrue(result.isValid());
        LOG.info("Validation correctly accepted complex JSON configuration");
    }
}