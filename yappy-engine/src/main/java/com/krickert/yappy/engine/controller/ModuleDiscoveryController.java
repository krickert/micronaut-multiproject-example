package com.krickert.yappy.engine.controller;

import com.krickert.search.pipeline.module.ModuleDiscoveryService;
import com.krickert.search.pipeline.module.ModuleSchemaRegistryService;
import com.krickert.search.pipeline.module.ModuleSchemaValidator;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for module discovery information.
 * Provides endpoints to view discovered modules and trigger discovery.
 */
@Controller("/api/modules")
@Requires(property = "yappy.module.discovery.enabled", value = "true")
@Requires(beans = ModuleDiscoveryService.class)
@Tag(name = "Module Discovery", description = "Endpoints for managing and discovering pipeline modules")
public class ModuleDiscoveryController {
    
    private final ModuleDiscoveryService moduleDiscoveryService;
    private final ModuleSchemaRegistryService schemaRegistryService;
    
    @Inject
    public ModuleDiscoveryController(
            ModuleDiscoveryService moduleDiscoveryService,
            @Nullable ModuleSchemaRegistryService schemaRegistryService) {
        this.moduleDiscoveryService = moduleDiscoveryService;
        this.schemaRegistryService = schemaRegistryService;
    }
    
    /**
     * Get all discovered modules
     */
    @Get
    @Operation(summary = "List all discovered modules", description = "Returns a list of all modules discovered by the engine")
    @ApiResponse(responseCode = "200", description = "List of modules",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ModulesResponse.class)))
    public HttpResponse<ModulesResponse> getModules() {
        Map<String, ModuleDiscoveryService.ModuleStatus> statuses = 
                moduleDiscoveryService.getModuleStatuses();
        
        List<ModuleInfo> modules = statuses.entrySet().stream()
                .map(entry -> new ModuleInfo(
                        entry.getKey(),
                        entry.getValue().getPipeStepName(),
                        entry.getValue().isHealthy(),
                        entry.getValue().getInstanceCount(),
                        entry.getValue().getMetadata()
                ))
                .collect(Collectors.toList());
        
        return HttpResponse.ok(new ModulesResponse(modules));
    }
    
    /**
     * Get information about a specific module
     */
    @Get("/{moduleName}")
    @Operation(summary = "Get module details", description = "Returns detailed information about a specific module")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ModuleInfo.class))),
            @ApiResponse(responseCode = "404", description = "Module not found")
    })
    public HttpResponse<ModuleInfo> getModule(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName) {
        Map<String, ModuleDiscoveryService.ModuleStatus> statuses = 
                moduleDiscoveryService.getModuleStatuses();
        
        ModuleDiscoveryService.ModuleStatus status = statuses.get(moduleName);
        if (status == null) {
            return HttpResponse.notFound();
        }
        
        ModuleInfo info = new ModuleInfo(
                moduleName,
                status.getPipeStepName(),
                status.isHealthy(),
                status.getInstanceCount(),
                status.getMetadata()
        );
        
        return HttpResponse.ok(info);
    }
    
    /**
     * Get healthy instances for a module (for load balancing/failover)
     */
    @Get("/{moduleName}/instances")
    @Operation(summary = "Get module instances", description = "Returns all healthy instances of a module for load balancing/failover")
    @ApiResponse(responseCode = "200", description = "List of instances",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = InstancesResponse.class)))
    public Mono<InstancesResponse> getModuleInstances(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName) {
        return moduleDiscoveryService.getHealthyInstances(moduleName)
                .map(instances -> new InstancesResponse(moduleName, instances));
    }
    
    /**
     * Get module status including last health check
     */
    @Get("/{moduleName}/status")
    @Operation(summary = "Get module status", description = "Returns the current status of a module including health check information")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module status",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ModuleStatusResponse.class))),
            @ApiResponse(responseCode = "404", description = "Module not found")
    })
    public HttpResponse<ModuleStatusResponse> getModuleStatus(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName) {
        var moduleInfo = moduleDiscoveryService.getModuleInfo(moduleName);
        if (moduleInfo == null) {
            return HttpResponse.notFound();
        }
        
        return HttpResponse.ok(new ModuleStatusResponse(
                moduleName,
                moduleInfo.status().name(),
                moduleInfo.lastHealthCheck() != null ? moduleInfo.lastHealthCheck().toString() : null,
                moduleInfo.instanceCount()
        ));
    }
    
    /**
     * Test a module with sample data
     */
    @Post("/{moduleName}/test")
    @Operation(summary = "Test module", description = "Tests a module with sample data")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Test results",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = TestResultResponse.class))),
            @ApiResponse(responseCode = "404", description = "Module not found")
    })
    public HttpResponse<TestResultResponse> testModule(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName,
            @Parameter(description = "Test request data", required = true)
            @Body TestRequest testRequest) {
        var moduleInfo = moduleDiscoveryService.getModuleInfo(moduleName);
        if (moduleInfo == null) {
            return HttpResponse.notFound();
        }
        
        // Create test document
        var testDoc = com.krickert.search.model.PipeDoc.newBuilder()
                .setId("test-" + System.currentTimeMillis())
                .setBody(testRequest.getContent())
                .build();
        
        var processRequest = com.krickert.search.sdk.ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .build();
        
        // Use the module's stub to test
        var testResult = new java.util.concurrent.CompletableFuture<TestResultResponse>();
        
        moduleInfo.stub().processData(processRequest, new io.grpc.stub.StreamObserver<com.krickert.search.sdk.ProcessResponse>() {
            @Override
            public void onNext(com.krickert.search.sdk.ProcessResponse response) {
                testResult.complete(new TestResultResponse(
                        response.getSuccess(),
                        response.getProcessorLogsList(),
                        response.hasErrorDetails() ? response.getErrorDetails().toString() : null
                ));
            }
            
            @Override
            public void onError(Throwable t) {
                testResult.complete(new TestResultResponse(
                        false,
                        List.of(),
                        t.getMessage()
                ));
            }
            
            @Override
            public void onCompleted() {
                // Handled in onNext
            }
        });
        
        try {
            return HttpResponse.ok(testResult.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            return HttpResponse.ok(new TestResultResponse(
                    false,
                    List.of(),
                    "Test timeout: " + e.getMessage()
            ));
        }
    }
    
    /**
     * Trigger module discovery manually
     */
    @Post("/discover")
    @Operation(summary = "Trigger module discovery", description = "Manually triggers the module discovery process")
    @ApiResponse(responseCode = "200", description = "Discovery initiated",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = DiscoveryResponse.class)))
    public HttpResponse<DiscoveryResponse> triggerDiscovery() {
        moduleDiscoveryService.discoverAndRegisterModules();
        
        return HttpResponse.ok(new DiscoveryResponse(
                "Discovery triggered",
                moduleDiscoveryService.getModuleStatuses().size()
        ));
    }
    
    /**
     * Get the configuration schema for a module
     */
    @Get(value = "/{moduleName}/schema", produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Get module schema", description = "Returns the JSON Schema for module configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SchemaResponse.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public HttpResponse<SchemaResponse> getModuleSchema(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName) {
        if (schemaRegistryService == null) {
            return HttpResponse.notFound();
        }
        
        String schema = schemaRegistryService.getModuleSchema(moduleName);
        if (schema == null) {
            // Try to get from module metadata
            var statuses = moduleDiscoveryService.getModuleStatuses();
            var status = statuses.get(moduleName);
            if (status != null && status.getMetadata().containsKey("json_config_schema")) {
                schema = status.getMetadata().get("json_config_schema");
            }
        }
        
        if (schema == null) {
            return HttpResponse.notFound();
        }
        
        return HttpResponse.ok(new SchemaResponse(moduleName, schema));
    }
    
    /**
     * Get the default configuration for a module based on its schema
     */
    @Get(value = "/{moduleName}/default-config", produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Get default configuration", description = "Returns the default configuration based on the module's schema")
    @ApiResponse(responseCode = "200", description = "Default configuration",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ConfigResponse.class)))
    public HttpResponse<ConfigResponse> getDefaultConfig(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName) {
        if (schemaRegistryService == null) {
            return HttpResponse.ok(new ConfigResponse(moduleName, "{}"));
        }
        
        String defaultConfig = schemaRegistryService.getDefaultConfiguration(moduleName);
        return HttpResponse.ok(new ConfigResponse(moduleName, defaultConfig));
    }
    
    /**
     * Validate a configuration against a module's schema
     */
    @Post(value = "/{moduleName}/validate-config", consumes = MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate module configuration", description = "Validates a configuration against the module's schema")
    @ApiResponse(responseCode = "200", description = "Validation result",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ValidationResponse.class)))
    public HttpResponse<ValidationResponse> validateConfig(
            @Parameter(description = "Name of the module", required = true)
            @PathVariable String moduleName,
            @Parameter(description = "Configuration to validate", required = true)
            @Body Map<String, Object> configuration) {
        
        if (schemaRegistryService == null) {
            return HttpResponse.ok(new ValidationResponse(
                    true, 
                    "Schema validation not available"
            ));
        }
        
        try {
            String configJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(configuration);
            
            var result = schemaRegistryService.validateModuleConfiguration(moduleName, configJson);
            
            return HttpResponse.ok(new ValidationResponse(
                    result.isValid(),
                    result.getMessage()
            ));
        } catch (Exception e) {
            return HttpResponse.ok(new ValidationResponse(
                    false,
                    "Validation error: " + e.getMessage()
            ));
        }
    }
    
    // Response DTOs
    
    @Serdeable
    @Schema(description = "Response containing a list of discovered modules")
    public static class ModulesResponse {
        private final List<ModuleInfo> modules;
        
        public ModulesResponse(List<ModuleInfo> modules) {
            this.modules = modules;
        }
        
        public List<ModuleInfo> getModules() {
            return modules;
        }
    }
    
    @Serdeable
    @Schema(description = "Detailed information about a module")
    public static class ModuleInfo {
        @Schema(description = "Service name in Consul")
        private final String serviceName;
        @Schema(description = "Pipeline step name")
        private final String pipeStepName;
        @Schema(description = "Whether the module is healthy")
        private final boolean healthy;
        @Schema(description = "Number of instances")
        private final int instanceCount;
        @Schema(description = "Module metadata")
        private final Map<String, String> metadata;
        
        public ModuleInfo(String serviceName, String pipeStepName, boolean healthy, 
                         int instanceCount, Map<String, String> metadata) {
            this.serviceName = serviceName;
            this.pipeStepName = pipeStepName;
            this.healthy = healthy;
            this.instanceCount = instanceCount;
            this.metadata = metadata;
        }
        
        // Getters
        public String getServiceName() { return serviceName; }
        public String getPipeStepName() { return pipeStepName; }
        public boolean isHealthy() { return healthy; }
        public int getInstanceCount() { return instanceCount; }
        public Map<String, String> getMetadata() { return metadata; }
    }
    
    @Serdeable
    @Schema(description = "Response containing module instances")
    public static class InstancesResponse {
        private final String moduleName;
        private final List<String> instances;
        
        public InstancesResponse(String moduleName, List<String> instances) {
            this.moduleName = moduleName;
            this.instances = instances;
        }
        
        public String getModuleName() { return moduleName; }
        public List<String> getInstances() { return instances; }
    }
    
    @Serdeable
    @Schema(description = "Response from module discovery trigger")
    public static class DiscoveryResponse {
        private final String message;
        private final int discoveredModules;
        
        public DiscoveryResponse(String message, int discoveredModules) {
            this.message = message;
            this.discoveredModules = discoveredModules;
        }
        
        public String getMessage() { return message; }
        public int getDiscoveredModules() { return discoveredModules; }
    }
    
    @Serdeable
    @Schema(description = "Module configuration schema")
    public static class SchemaResponse {
        private final String moduleName;
        private final String schema;
        
        public SchemaResponse(String moduleName, String schema) {
            this.moduleName = moduleName;
            this.schema = schema;
        }
        
        public String getModuleName() { return moduleName; }
        public String getSchema() { return schema; }
    }
    
    @Serdeable
    @Schema(description = "Module configuration")
    public static class ConfigResponse {
        private final String moduleName;
        private final String configuration;
        
        public ConfigResponse(String moduleName, String configuration) {
            this.moduleName = moduleName;
            this.configuration = configuration;
        }
        
        public String getModuleName() { return moduleName; }
        public String getConfiguration() { return configuration; }
    }
    
    @Serdeable
    @Schema(description = "Configuration validation result")
    public static class ValidationResponse {
        private final boolean valid;
        private final String message;
        
        public ValidationResponse(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }
        
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
    }
    
    @Serdeable
    @Schema(description = "Module status information")
    public static class ModuleStatusResponse {
        @Schema(description = "Module name")
        private final String moduleName;
        @Schema(description = "Current status")
        private final String status;
        @Schema(description = "Last health check timestamp")
        private final String lastHealthCheck;
        @Schema(description = "Number of instances")
        private final int instanceCount;
        
        public ModuleStatusResponse(String moduleName, String status, String lastHealthCheck, int instanceCount) {
            this.moduleName = moduleName;
            this.status = status;
            this.lastHealthCheck = lastHealthCheck;
            this.instanceCount = instanceCount;
        }
        
        public String getModuleName() { return moduleName; }
        public String getStatus() { return status; }
        public String getLastHealthCheck() { return lastHealthCheck; }
        public int getInstanceCount() { return instanceCount; }
    }
    
    @Serdeable
    @Schema(description = "Module test request")
    public static class TestRequest {
        @Schema(description = "Test content")
        private String content;
        
        public TestRequest() {}
        
        public TestRequest(String content) {
            this.content = content;
        }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
    
    @Serdeable
    @Schema(description = "Module test result")
    public static class TestResultResponse {
        @Schema(description = "Whether the test succeeded")
        private final boolean success;
        @Schema(description = "Test results data")
        private final Map<String, String> testResults;
        @Schema(description = "Error message if test failed")
        private final String error;
        
        public TestResultResponse(boolean success, List<String> logs, String error) {
            this.success = success;
            this.testResults = new HashMap<>();
            if (logs != null && !logs.isEmpty()) {
                for (int i = 0; i < logs.size(); i++) {
                    this.testResults.put("log_" + i, logs.get(i));
                }
            }
            this.error = error;
        }
        
        public boolean isSuccess() { return success; }
        public Map<String, String> getTestResults() { return testResults; }
        public String getError() { return error; }
    }
}