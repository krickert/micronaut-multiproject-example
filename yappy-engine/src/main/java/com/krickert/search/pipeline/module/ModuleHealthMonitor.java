package com.krickert.search.pipeline.module;

import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.model.PipeDoc;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors the health of discovered modules by performing periodic health checks.
 */
@Singleton
@Requires(property = "yappy.module.health.check.enabled", value = "true", defaultValue = "true")
public class ModuleHealthMonitor {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleHealthMonitor.class);
    
    private final ModuleDiscoveryService discoveryService;
    private final Duration checkInterval;
    private final Duration checkTimeout;
    
    // Map of module name to monitoring subscription
    private final Map<String, reactor.core.Disposable> monitoringSubscriptions = new ConcurrentHashMap<>();
    
    @Inject
    public ModuleHealthMonitor(
            ModuleDiscoveryService discoveryService,
            @Property(name = "yappy.module.health.check.interval", defaultValue = "30s") Duration checkInterval,
            @Property(name = "yappy.module.health.check.timeout", defaultValue = "10s") Duration checkTimeout) {
        this.discoveryService = discoveryService;
        this.checkInterval = checkInterval;
        this.checkTimeout = checkTimeout;
    }
    
    /**
     * Starts monitoring a module's health with periodic checks.
     */
    public void startMonitoring(String moduleName) {
        if (monitoringSubscriptions.containsKey(moduleName)) {
            LOG.debug("Module {} is already being monitored", moduleName);
            return;
        }
        
        LOG.info("Starting health monitoring for module: {}", moduleName);
        
        reactor.core.Disposable subscription = Flux.interval(checkInterval)
                .flatMap(tick -> checkModuleHealth(moduleName)
                        .doOnNext(result -> handleHealthCheckResult(result))
                        .onErrorResume(error -> {
                            LOG.error("Error during health check for module {}: {}", 
                                    moduleName, error.getMessage());
                            return Mono.just(HealthCheckResult.failed(moduleName, error.getMessage()));
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        
        monitoringSubscriptions.put(moduleName, subscription);
    }
    
    /**
     * Stops monitoring a module's health.
     */
    public void stopMonitoring(String moduleName) {
        reactor.core.Disposable subscription = monitoringSubscriptions.remove(moduleName);
        if (subscription != null) {
            LOG.info("Stopping health monitoring for module: {}", moduleName);
            subscription.dispose();
        }
    }
    
    /**
     * Performs a single health check on a module.
     */
    public Mono<HealthCheckResult> checkModuleHealth(String moduleName) {
        return Mono.defer(() -> {
            ModuleDiscoveryService.ModuleInfo moduleInfo = discoveryService.getModuleInfo(moduleName);
            if (moduleInfo == null) {
                return Mono.just(HealthCheckResult.failed(moduleName, "Module not found"));
            }
            
            return performHealthCheck(moduleInfo)
                    .timeout(checkTimeout)
                    .onErrorResume(error -> {
                        String errorMsg = error instanceof StatusRuntimeException ?
                                ((StatusRuntimeException) error).getStatus().getDescription() :
                                error.getMessage();
                        return Mono.just(HealthCheckResult.failed(moduleName, errorMsg));
                    });
        });
    }
    
    private Mono<HealthCheckResult> performHealthCheck(ModuleDiscoveryService.ModuleInfo moduleInfo) {
        return Mono.create(sink -> {
            try {
                PipeStepProcessorGrpc.PipeStepProcessorStub stub = moduleInfo.stub();
                Instant startTime = Instant.now();
                
                // Use the new CheckHealth RPC
                stub.checkHealth(com.google.protobuf.Empty.getDefaultInstance(), 
                        new io.grpc.stub.StreamObserver<com.krickert.search.sdk.HealthCheckResponse>() {
                    @Override
                    public void onNext(com.krickert.search.sdk.HealthCheckResponse response) {
                        Duration responseTime = Duration.between(startTime, Instant.now());
                        
                        if (response.getHealthy()) {
                            sink.success(HealthCheckResult.healthy(
                                    moduleInfo.serviceName(), 
                                    responseTime.toMillis()
                            ));
                        } else {
                            String error = response.getMessage() != null && !response.getMessage().isEmpty() ? 
                                    response.getMessage() : "Module unhealthy";
                            sink.success(HealthCheckResult.failed(moduleInfo.serviceName(), error));
                        }
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        // If CheckHealth is not implemented, fall back to ProcessData health check
                        if (t instanceof io.grpc.StatusRuntimeException && 
                            ((io.grpc.StatusRuntimeException) t).getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
                            performFallbackHealthCheck(moduleInfo, startTime, sink);
                        } else {
                            sink.error(t);
                        }
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Already handled in onNext
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }
    
    private void performFallbackHealthCheck(ModuleDiscoveryService.ModuleInfo moduleInfo, 
                                           Instant startTime, 
                                           reactor.core.publisher.MonoSink<HealthCheckResult> sink) {
        // Fallback to using ProcessData for health check if CheckHealth is not implemented
        try {
            PipeDoc healthCheckDoc = PipeDoc.newBuilder()
                    .setId("health-check-" + System.currentTimeMillis())
                    .setBody("HEALTH_CHECK")
                    .build();
            
            Struct.Builder configBuilder = Struct.newBuilder();
            configBuilder.putFields("health_check", Value.newBuilder().setBoolValue(true).build());
            
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(healthCheckDoc)
                    .setConfig(com.krickert.search.sdk.ProcessConfiguration.newBuilder()
                            .setCustomJsonConfig(configBuilder.build())
                            .build())
                    .build();
            
            moduleInfo.stub().processData(request, new io.grpc.stub.StreamObserver<ProcessResponse>() {
                @Override
                public void onNext(ProcessResponse response) {
                    Duration responseTime = Duration.between(startTime, Instant.now());
                    
                    if (response.getSuccess()) {
                        sink.success(
                                HealthCheckResult.healthy(moduleInfo.serviceName(), responseTime.toMillis())
                        );
                    } else {
                        String error = response.hasErrorDetails() ? 
                                response.getErrorDetails().toString() : "Unknown error";
                        sink.success(
                                HealthCheckResult.failed(moduleInfo.serviceName(), error)
                        );
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    sink.error(t);
                }
                
                @Override
                public void onCompleted() {
                    // Already handled in onNext
                }
            });
        } catch (Exception e) {
            sink.error(e);
        }
    }
    
    private void handleHealthCheckResult(HealthCheckResult result) {
        LOG.debug("Health check result for module {}: healthy={}, responseTime={}ms, error={}",
                result.getModuleName(), result.isHealthy(), result.getResponseTime(), result.getError());
        
        // Update module status based on health check
        if (result.isHealthy()) {
            discoveryService.updateModuleHealth(result.getModuleName(), true, Instant.now());
        } else {
            discoveryService.updateModuleHealth(result.getModuleName(), false, Instant.now());
        }
    }
    
    @PreDestroy
    public void shutdown() {
        LOG.info("Shutting down module health monitor");
        monitoringSubscriptions.values().forEach(subscription -> subscription.dispose());
        monitoringSubscriptions.clear();
    }
    
    /**
     * Result of a health check.
     */
    public static class HealthCheckResult {
        private final String moduleName;
        private final boolean healthy;
        private final Long responseTime; // in milliseconds
        private final String error;
        private final Instant timestamp;
        
        private HealthCheckResult(String moduleName, boolean healthy, Long responseTime, String error) {
            this.moduleName = moduleName;
            this.healthy = healthy;
            this.responseTime = responseTime;
            this.error = error;
            this.timestamp = Instant.now();
        }
        
        public static HealthCheckResult healthy(String moduleName, long responseTimeMs) {
            return new HealthCheckResult(moduleName, true, responseTimeMs, null);
        }
        
        public static HealthCheckResult failed(String moduleName, String error) {
            return new HealthCheckResult(moduleName, false, null, error);
        }
        
        // Getters
        public String getModuleName() { return moduleName; }
        public boolean isHealthy() { return healthy; }
        public Long getResponseTime() { return responseTime; }
        public String getError() { return error; }
        public Instant getTimestamp() { return timestamp; }
    }
}