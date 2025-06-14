package com.krickert.search.pipeline.api.controller;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.TestUtilityService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Test utilities API - useful admin operations for testing and development.
 */
@Controller("/api/v1/test-utils")
@Tag(name = "Test Utilities", description = "Testing and development utilities")
@ExecuteOn(TaskExecutors.IO)
public class TestUtilityController {

    private final TestUtilityService testUtilityService;

    public TestUtilityController(TestUtilityService testUtilityService) {
        this.testUtilityService = testUtilityService;
    }

    @Post("/modules/register")
    @Status(HttpStatus.CREATED)
    @Operation(summary = "Register test module", description = "Quick module registration for testing")
    @ApiResponse(responseCode = "201", description = "Module registered successfully")
    @ApiResponse(responseCode = "400", description = "Invalid module configuration")
    public Mono<ModuleRegistrationResponse> registerTestModule(@Body @Valid ModuleRegistrationRequest request) {
        return testUtilityService.registerModule(request);
    }

    @Delete("/modules/{serviceId}")
    @Status(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deregister test module")
    @ApiResponse(responseCode = "204", description = "Module deregistered successfully")
    public Mono<Void> deregisterTestModule(@PathVariable @NotBlank String serviceId) {
        return testUtilityService.deregisterModule(serviceId);
    }

    @Get("/modules")
    @Operation(summary = "List all test modules")
    @ApiResponse(responseCode = "200", description = "Modules retrieved successfully")
    public Flux<ModuleInfo> getAllTestModules() {
        return testUtilityService.getAllModules();
    }

    @Post("/pipelines/simple")
    @Operation(summary = "Create simple pipeline", description = "Create a linear pipeline from step names")
    @ApiResponse(responseCode = "200", description = "Pipeline created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid pipeline configuration")
    public Mono<PipelineConfig> createSimplePipeline(
            @QueryValue @NotBlank String name,
            @Body java.util.List<String> steps) {
        return testUtilityService.createSimplePipeline(name, steps);
    }

    @Post("/pipelines/complex")
    @Operation(summary = "Create complex pipeline", description = "Create a pipeline with full configuration")
    @ApiResponse(responseCode = "200", description = "Pipeline created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid pipeline configuration")
    public Mono<PipelineConfig> createComplexPipeline(@Body @Valid PipelineCreateRequest request) {
        return testUtilityService.createPipeline(request);
    }

    @Post("/data/generate")
    @Operation(summary = "Generate test data")
    @ApiResponse(responseCode = "200", description = "Test data generated successfully")
    public Mono<TestDataResponse> generateTestData(@Body @Valid TestDataRequest request) {
        return testUtilityService.createTestData(request);
    }

    @Post("/data/documents")
    @Operation(summary = "Generate test documents")
    @ApiResponse(responseCode = "200", description = "Test documents generated successfully")
    public Flux<TestDocument> generateTestDocuments(@Body @Valid TestDataGenerationRequest request) {
        return testUtilityService.generateTestDocuments(request);
    }

    @Get("/health/{serviceName}")
    @Operation(summary = "Check service health")
    @ApiResponse(responseCode = "200", description = "Health status retrieved successfully")
    public Mono<HealthCheckResponse> checkHealth(
            @PathVariable @NotBlank String serviceName,
            @QueryValue @NotBlank String host,
            @QueryValue int port) {
        return testUtilityService.checkServiceHealth(serviceName, host, port);
    }

    @Get("/health/{serviceName}/wait")
    @Operation(summary = "Wait for service to be healthy")
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    public Mono<HealthCheckResponse> waitForHealthy(
            @PathVariable @NotBlank String serviceName,
            @QueryValue @NotBlank String host,
            @QueryValue int port,
            @QueryValue(defaultValue = "30") long timeoutSeconds) {
        return testUtilityService.waitForHealthy(serviceName, host, port, timeoutSeconds);
    }

    @Get("/environment/verify")
    @Operation(summary = "Verify environment", description = "Check that all required services are running")
    @ApiResponse(responseCode = "200", description = "Environment status retrieved successfully")
    public Mono<EnvironmentStatus> verifyEnvironment() {
        return testUtilityService.verifyEnvironment();
    }

    @Post("/kv/seed")
    @Requires(notEnv = "production")
    @Operation(summary = "⚠️ DANGEROUS: Direct KV store access", 
               description = "⚠️ WARNING: This endpoint provides DIRECT access to Consul KV store. " +
                            "This is for emergency fixes only and violates our security model. " +
                            "All production changes must go through validated consul-config services. " +
                            "DO NOT USE IN PRODUCTION! (Disabled in production environment)")
    @ApiResponse(responseCode = "200", description = "KV store seeded successfully")
    @ApiResponse(responseCode = "403", description = "Access denied in production mode")
    public Mono<Void> seedKvStore(
            @QueryValue @NotBlank String key,
            @Body Object value) {
        return testUtilityService.seedKvStore(key, value);
    }

    @Delete("/kv/clean")
    @Requires(notEnv = "production")
    @Operation(summary = "⚠️ DANGEROUS: Direct KV cleanup", 
               description = "⚠️ WARNING: This endpoint provides DIRECT access to delete from Consul KV store. " +
                            "This is for emergency cleanup only and violates our security model. " +
                            "All production changes must go through validated consul-config services. " +
                            "DO NOT USE IN PRODUCTION! (Disabled in production environment)")
    @ApiResponse(responseCode = "200", description = "KV store cleaned successfully")
    @ApiResponse(responseCode = "403", description = "Access denied in production mode")
    public Mono<Void> cleanKvStore(@QueryValue @NotBlank String prefix) {
        return testUtilityService.cleanKvStore(prefix);
    }

    @Post("/schemas/seed")
    @Operation(summary = "Seed schemas", description = "Load test schemas into registry")
    @ApiResponse(responseCode = "200", description = "Schemas seeded successfully")
    public Mono<Void> seedSchemas() {
        return testUtilityService.seedSchemas();
    }

    @Post("/schemas/register")
    @Operation(summary = "Register schema")
    @ApiResponse(responseCode = "200", description = "Schema registered successfully")
    @ApiResponse(responseCode = "400", description = "Invalid schema")
    public Mono<Void> registerSchema(
            @QueryValue @NotBlank String schemaId,
            @Body String schemaContent) {
        return testUtilityService.registerSchema(schemaId, schemaContent);
    }

    @Post("/schemas/validate")
    @Operation(summary = "Validate against schema")
    @ApiResponse(responseCode = "200", description = "Validation completed")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    public Mono<ValidationResult> validateAgainstSchema(
            @QueryValue @NotBlank String schemaId,
            @Body String jsonContent) {
        return testUtilityService.validateAgainstSchema(schemaId, jsonContent);
    }
}