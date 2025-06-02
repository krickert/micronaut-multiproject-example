package com.krickert.search.pipeline.module;

import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.TestRequest;
import com.krickert.search.sdk.TestResponse;
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
                .subscribeOn(Schedulers.elastic())
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
                
                TestRequest request = TestRequest.newBuilder()
                        .putAdditionalTests("health_check", "true")
                        .build();
                
                stub.testMode(request, new io.grpc.stub.StreamObserver<TestResponse>() {
                    @Override
                    public void onNext(TestResponse response) {
                        Duration responseTime = Duration.between(startTime, Instant.now());
                        
                        if (response.getSuccess()) {
                            sink.success(HealthCheckResult.healthy(
                                    moduleInfo.serviceName(), 
                                    responseTime.toMillis()
                            ));
                        } else {
                            String error = response.getTestResultsOrDefault("error", "Unknown error");
                            sink.success(HealthCheckResult.failed(moduleInfo.serviceName(), error));
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
        });
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