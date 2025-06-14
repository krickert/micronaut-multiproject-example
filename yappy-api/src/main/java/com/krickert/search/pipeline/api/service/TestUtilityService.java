package com.krickert.search.pipeline.api.service;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service interface for test utilities that are useful as admin operations.
 * Based on utilities from yappy-engine/engine-core/src/test/java/.../test/util
 */
public interface TestUtilityService {
    
    // Module Registration Operations
    /**
     * Register a module with full configuration.
     */
    Mono<ModuleRegistrationResponse> registerModule(ModuleRegistrationRequest request);
    
    /**
     * Deregister a module by ID.
     */
    Mono<Void> deregisterModule(String serviceId);
    
    /**
     * Get all registered modules.
     */
    Flux<ModuleInfo> getAllModules();
    
    // Pipeline Management Operations
    /**
     * Create a simple linear pipeline.
     */
    Mono<PipelineConfig> createSimplePipeline(String pipelineName, List<String> stepNames);
    
    /**
     * Create a complex pipeline with custom steps.
     */
    Mono<PipelineConfig> createPipeline(PipelineCreateRequest request);
    
    /**
     * Create test data for pipeline testing.
     */
    Mono<TestDataResponse> createTestData(TestDataRequest request);
    
    // Health Check Operations
    /**
     * Check health of a specific gRPC service.
     */
    Mono<HealthCheckResponse> checkServiceHealth(String serviceName, String host, int port);
    
    /**
     * Wait for a service to become healthy.
     */
    Mono<HealthCheckResponse> waitForHealthy(String serviceName, String host, int port, long timeoutSeconds);
    
    // Test Data Generation
    /**
     * Generate batch of test documents.
     */
    Flux<TestDocument> generateTestDocuments(TestDataGenerationRequest request);
    
    // Environment Verification
    /**
     * Verify all required containers/services are ready.
     */
    Mono<EnvironmentStatus> verifyEnvironment();
    
    // KV Store Operations
    /**
     * Seed KV store with test data.
     */
    Mono<Void> seedKvStore(String key, Object value);
    
    /**
     * Clean KV store by prefix.
     */
    Mono<Void> cleanKvStore(String prefix);
    
    // Schema Registry Operations
    /**
     * Seed schema registry with test schemas.
     */
    Mono<Void> seedSchemas();
    
    /**
     * Register a single schema.
     */
    Mono<Void> registerSchema(String schemaId, String schemaContent);
    
    /**
     * Validate JSON content against schema.
     */
    Mono<ValidationResult> validateAgainstSchema(String schemaId, String jsonContent);
}