package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service that discovers and manages modules regardless of their implementation language.
 * Handles automatic registration, health checking, and failover.
 */
@Singleton
@Requires(property = "yappy.module.discovery.enabled", value = "true", defaultValue = "false")
public class ModuleDiscoveryService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleDiscoveryService.class);
    
    private final ConsulBusinessOperationsService consulService;
    private final GrpcChannelManager channelManager;
    private final ModuleSchemaRegistryService schemaRegistryService;
    private final ModuleSchemaValidator schemaValidator;
    
    // Cache of discovered modules and their status
    private final Map<String, ModuleStatus> moduleStatusCache = new ConcurrentHashMap<>();
    
    // Cache of module stubs and additional info
    private final Map<String, ModuleInfo> moduleInfoCache = new ConcurrentHashMap<>();
    
    @io.micronaut.context.annotation.Value("${yappy.module.discovery.interval:30s}")
    private String discoveryIntervalString;
    
    @io.micronaut.context.annotation.Value("${yappy.module.health-check.timeout:5s}")
    private String healthCheckTimeoutString;
    
    @io.micronaut.context.annotation.Value("${yappy.module.test.enabled:true}")
    private boolean moduleTestingEnabled;
    
    private Duration discoveryInterval;
    private Duration healthCheckTimeout;
    
    @Inject
    public ModuleDiscoveryService(
            ConsulBusinessOperationsService consulService,
            GrpcChannelManager channelManager,
            @jakarta.annotation.Nullable ModuleSchemaRegistryService schemaRegistryService,
            @jakarta.annotation.Nullable ModuleSchemaValidator schemaValidator) {
        this.consulService = consulService;
        this.channelManager = channelManager;
        this.schemaRegistryService = schemaRegistryService;
        this.schemaValidator = schemaValidator;
        
        // Parse duration strings
        this.discoveryInterval = parseDuration(discoveryIntervalString, Duration.ofSeconds(30));
        this.healthCheckTimeout = parseDuration(healthCheckTimeoutString, Duration.ofSeconds(5));
    }
    
    private Duration parseDuration(String durationString, Duration defaultValue) {
        if (durationString == null || durationString.isEmpty()) {
            return defaultValue;
        }
        try {
            // Handle simple format like "30s", "5m", etc.
            if (durationString.endsWith("s")) {
                long seconds = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofSeconds(seconds);
            } else if (durationString.endsWith("m")) {
                long minutes = Long.parseLong(durationString.substring(0, durationString.length() - 1));
                return Duration.ofMinutes(minutes);
            } else {
                // Try parsing as ISO-8601
                return Duration.parse(durationString);
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse duration '{}', using default: {}", durationString, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Discovers modules from Consul and registers them with the engine.
     * This runs periodically to find new modules or handle changes.
     */
    @Scheduled(fixedDelay = "${yappy.module.discovery.interval:30s}")
    public void discoverAndRegisterModules() {
        LOG.debug("Starting module discovery scan...");
        
        consulService.listServices()
            .flatMapMany(services -> Flux.fromIterable(services.entrySet()))
            .filter(entry -> isYappyModule(entry.getKey(), entry.getValue()))
            .flatMap(entry -> processModuleService(entry.getKey()))
            .subscribe(
                result -> LOG.info("Module discovery result: {}", result),
                error -> LOG.error("Error during module discovery", error),
                () -> LOG.debug("Module discovery scan completed")
            );
    }
    
    /**
     * Determines if a service is a Yappy module based on tags or naming convention.
     */
    private boolean isYappyModule(String serviceName, List<String> tags) {
        // Check for yappy-module tag
        if (tags.contains("yappy-module")) {
            return true;
        }
        
        // Check naming convention (modules might follow a pattern)
        if (serviceName.endsWith("-processor") || 
            serviceName.endsWith("-module") ||
            serviceName.endsWith("-connector") ||
            serviceName.endsWith("-sink")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Processes a discovered module service.
     */
    private Mono<ModuleRegistrationResult> processModuleService(String serviceName) {
        return consulService.getHealthyServiceInstances(serviceName)
            .flatMap(instances -> {
                if (instances.isEmpty()) {
                    LOG.warn("No healthy instances found for service: {}", serviceName);
                    return Mono.just(new ModuleRegistrationResult(serviceName, false, "No healthy instances"));
                }
                
                // Try to get service metadata from the first healthy instance
                return getModuleMetadata(serviceName)
                    .flatMap(metadata -> {
                        if (moduleTestingEnabled) {
                            return testModule(serviceName, metadata)
                                .flatMap(testResult -> {
                                    if (testResult.isSuccess()) {
                                        return registerModule(serviceName, metadata, instances.size());
                                    } else {
                                        LOG.warn("Module {} failed testing: {}", serviceName, testResult.getMessage());
                                        return Mono.just(new ModuleRegistrationResult(serviceName, false, "Failed testing"));
                                    }
                                });
                        } else {
                            return registerModule(serviceName, metadata, instances.size());
                        }
                    });
            })
            .onErrorResume(error -> {
                LOG.error("Error processing module {}: {}", serviceName, error.getMessage());
                return Mono.just(new ModuleRegistrationResult(serviceName, false, error.getMessage()));
            });
    }
    
    /**
     * Gets metadata from a module by calling its getServiceRegistration RPC.
     */
    private Mono<ServiceMetadata> getModuleMetadata(String serviceName) {
        return Mono.create(sink -> {
            try {
                ManagedChannel channel = channelManager.getChannel(serviceName);
                PipeStepProcessorGrpc.PipeStepProcessorStub stub = PipeStepProcessorGrpc.newStub(channel)
                    .withDeadlineAfter(healthCheckTimeout.toMillis(), TimeUnit.MILLISECONDS);
                
                stub.getServiceRegistration(Empty.getDefaultInstance(), new StreamObserver<ServiceMetadata>() {
                    @Override
                    public void onNext(ServiceMetadata metadata) {
                        sink.success(metadata);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        LOG.error("Failed to get metadata for service {}", serviceName, t);
                        sink.error(t);
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already sent in onNext
                    }
                });
            } catch (Exception e) {
                LOG.error("Error creating channel for service {}", serviceName, e);
                sink.error(e);
            }
        });
    }
    
    /**
     * Tests a module by sending a simple test request.
     */
    private Mono<TestResult> testModule(String serviceName, ServiceMetadata metadata) {
        return Mono.create(sink -> {
            try {
                ManagedChannel channel = channelManager.getChannel(serviceName);
                PipeStepProcessorGrpc.PipeStepProcessorStub stub = PipeStepProcessorGrpc.newStub(channel)
                    .withDeadlineAfter(healthCheckTimeout.toMillis(), TimeUnit.MILLISECONDS);
                
                // Create a minimal test request
                ProcessRequest testRequest = createTestRequest(metadata);
                
                stub.processData(testRequest, new StreamObserver<ProcessResponse>() {
                    @Override
                    public void onNext(ProcessResponse response) {
                        if (response.getSuccess()) {
                            sink.success(new TestResult(true, "Module test passed"));
                        } else {
                            sink.success(new TestResult(false, "Module returned failure: " + 
                                String.join(", ", response.getProcessorLogsList())));
                        }
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        if (t instanceof StatusRuntimeException) {
                            StatusRuntimeException sre = (StatusRuntimeException) t;
                            if (sre.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
                                // Module doesn't support testing, consider it passed
                                sink.success(new TestResult(true, "Module doesn't support test mode"));
                            } else {
                                sink.success(new TestResult(false, "Test failed: " + sre.getStatus().getDescription()));
                            }
                        } else {
                            sink.success(new TestResult(false, "Test error: " + t.getMessage()));
                        }
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already sent in onNext
                    }
                });
            } catch (Exception e) {
                sink.success(new TestResult(false, "Test setup error: " + e.getMessage()));
            }
        });
    }
    
    /**
     * Creates a minimal test request for module testing.
     */
    private ProcessRequest createTestRequest(ServiceMetadata metadata) {
        ProcessRequest.Builder builder = ProcessRequest.newBuilder();
        
        // Create a minimal test document
        builder.setDocument(com.krickert.search.model.PipeDoc.newBuilder()
            .setId("test-doc-" + UUID.randomUUID())
            .setTitle("Test Document")
            .setBody("This is a test document for module validation.")
            .build());
        
        // Add test configuration
        Struct.Builder configBuilder = Struct.newBuilder();
        configBuilder.putFields("_test_mode", Value.newBuilder().setBoolValue(true).build());
        
        // Add default config if no schema provided
        if (!metadata.getContextParamsMap().containsKey("json_config_schema")) {
            configBuilder.putFields("log_prefix", Value.newBuilder().setStringValue("[TEST]").build());
        }
        
        builder.setConfig(com.krickert.search.sdk.ProcessConfiguration.newBuilder()
            .setCustomJsonConfig(configBuilder.build())
            .build());
        
        // Add service metadata
        builder.setMetadata(metadata);
        
        return builder.build();
    }
    
    /**
     * Registers a module with the engine's registry.
     */
    private Mono<ModuleRegistrationResult> registerModule(String serviceName, ServiceMetadata metadata, int instanceCount) {
        // Update our internal cache
        ModuleStatus status = new ModuleStatus(
            serviceName,
            metadata.getPipeStepName(),
            true,
            instanceCount,
            metadata.getContextParamsMap()
        );
        
        moduleStatusCache.put(serviceName, status);
        
        // Create and cache the module info with stub
        try {
            ManagedChannel channel = channelManager.getChannel(serviceName);
            PipeStepProcessorGrpc.PipeStepProcessorStub stub = PipeStepProcessorGrpc.newStub(channel);
            
            ModuleInfo moduleInfo = new ModuleInfo(
                serviceName,
                metadata.getPipeStepName(),
                stub,
                ModuleStatusEnum.READY,
                instanceCount,
                metadata.getContextParamsMap(),
                Instant.now()
            );
            
            moduleInfoCache.put(serviceName, moduleInfo);
        } catch (Exception e) {
            LOG.error("Failed to create stub for module {}", serviceName, e);
        }
        
        // Register schema if provided and schema registry is available
        String schemaJson = metadata.getContextParamsOrDefault("json_config_schema", null);
        if (schemaJson != null && !schemaJson.isEmpty() && schemaRegistryService != null) {
            LOG.info("Module {} provides configuration schema, registering with schema registry", serviceName);
            
            schemaRegistryService.registerModuleSchema(metadata.getPipeStepName(), schemaJson)
                .subscribe(
                    result -> {
                        if (result.isSuccess()) {
                            LOG.info("Successfully registered schema for module {} with ID {}", 
                                    serviceName, result.getSchemaId());
                            // Update status with schema info
                            status.getMetadata().put("schema_id", String.valueOf(result.getSchemaId()));
                            status.getMetadata().put("schema_subject", result.getSubjectName());
                        } else {
                            LOG.warn("Failed to register schema for module {}: {}", 
                                    serviceName, result.getMessage());
                        }
                    },
                    error -> LOG.error("Error registering schema for module {}", serviceName, error)
                );
        }
        
        LOG.info("Module {} registered successfully with {} instances", serviceName, instanceCount);
        return Mono.just(new ModuleRegistrationResult(serviceName, true, "Registered with " + instanceCount + " instances"));
    }
    
    /**
     * Gets the status of all registered modules.
     */
    public Map<String, ModuleStatus> getModuleStatuses() {
        return new HashMap<>(moduleStatusCache);
    }
    
    /**
     * Checks if a module is available and healthy.
     */
    public boolean isModuleAvailable(String moduleName) {
        ModuleStatus status = moduleStatusCache.get(moduleName);
        return status != null && status.isHealthy();
    }
    
    /**
     * Gets the full module information including stub.
     */
    public ModuleInfo getModuleInfo(String moduleName) {
        return moduleInfoCache.get(moduleName);
    }
    
    /**
     * Gets healthy instances for failover.
     */
    public Mono<List<String>> getHealthyInstances(String moduleName) {
        return consulService.getHealthyServiceInstances(moduleName)
            .map(instances -> instances.stream()
                .map(instance -> instance.getService().getAddress() + ":" + instance.getService().getPort())
                .toList());
    }
    
    // Inner classes for results
    
    private static class ModuleRegistrationResult {
        private final String serviceName;
        private final boolean success;
        private final String message;
        
        public ModuleRegistrationResult(String serviceName, boolean success, String message) {
            this.serviceName = serviceName;
            this.success = success;
            this.message = message;
        }
        
        @Override
        public String toString() {
            return String.format("ModuleRegistrationResult{service='%s', success=%s, message='%s'}", 
                serviceName, success, message);
        }
    }
    
    private static class TestResult {
        private final boolean success;
        private final String message;
        
        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
    }
    
    public static class ModuleStatus {
        private final String serviceName;
        private final String pipeStepName;
        private final boolean healthy;
        private final int instanceCount;
        private final Map<String, String> metadata;
        
        public ModuleStatus(String serviceName, String pipeStepName, boolean healthy, 
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
    
    /**
     * Represents the full information about a module including its stub.
     */
    public record ModuleInfo(
            String serviceName,
            String pipeStepName,
            PipeStepProcessorGrpc.PipeStepProcessorStub stub,
            ModuleStatusEnum status,
            int instanceCount,
            Map<String, String> metadata,
            Instant lastHealthCheck
    ) {}
    
    /**
     * Module status enumeration.
     */
    public enum ModuleStatusEnum {
        READY,
        UNHEALTHY,
        TESTING,
        FAILED
    }
    
    /**
     * Updates the health status of a module based on health check results.
     * 
     * @param moduleName The name of the module
     * @param healthy Whether the module is healthy
     * @param checkTime The time of the health check
     */
    public void updateModuleHealth(String moduleName, boolean healthy, java.time.Instant checkTime) {
        ModuleStatus currentStatus = moduleStatusCache.get(moduleName);
        if (currentStatus != null) {
            // Create updated module status with new health state
            ModuleStatus updatedStatus = new ModuleStatus(
                    currentStatus.getServiceName(),
                    currentStatus.getPipeStepName(),
                    healthy,
                    currentStatus.getInstanceCount(),
                    currentStatus.getMetadata()
            );
            
            moduleStatusCache.put(moduleName, updatedStatus);
            
            // Also update the module info cache
            ModuleInfo currentInfo = moduleInfoCache.get(moduleName);
            if (currentInfo != null) {
                ModuleInfo updatedInfo = new ModuleInfo(
                        currentInfo.serviceName(),
                        currentInfo.pipeStepName(),
                        currentInfo.stub(),
                        healthy ? ModuleStatusEnum.READY : ModuleStatusEnum.UNHEALTHY,
                        currentInfo.instanceCount(),
                        currentInfo.metadata(),
                        checkTime
                );
                moduleInfoCache.put(moduleName, updatedInfo);
            }
            
            LOG.debug("Updated module {} health status to {} at {}", 
                    moduleName, healthy ? "healthy" : "unhealthy", checkTime);
        } else {
            LOG.warn("Attempted to update health for unknown module: {}", moduleName);
        }
    }
}