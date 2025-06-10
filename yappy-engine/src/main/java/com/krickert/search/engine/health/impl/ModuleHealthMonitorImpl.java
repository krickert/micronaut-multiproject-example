package com.krickert.search.engine.health.impl;

import com.krickert.search.engine.health.ModuleHealthMonitor;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.yappy.registration.api.HealthCheckType;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.micronaut.context.annotation.Context;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of ModuleHealthMonitor that performs periodic health checks.
 */
@Singleton
@Context
public class ModuleHealthMonitorImpl implements ModuleHealthMonitor {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleHealthMonitorImpl.class);
    private static final Duration HEALTH_CHECK_INTERVAL = Duration.ofSeconds(10);
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    
    private final TaskScheduler taskScheduler;
    private final Map<String, MonitoredModule> monitoredModules = new ConcurrentHashMap<>();
    private final List<HealthChangeListener> listeners = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> healthCheckTask;
    
    @Inject
    public ModuleHealthMonitorImpl(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }
    
    @PostConstruct
    void startHealthCheckScheduler() {
        healthCheckTask = taskScheduler.scheduleAtFixedRate(
            Duration.ZERO,
            HEALTH_CHECK_INTERVAL,
            this::performScheduledHealthChecks
        );
        LOG.info("Module health monitor started with check interval: {}", HEALTH_CHECK_INTERVAL);
    }
    
    @PreDestroy
    void stopHealthCheckScheduler() {
        if (healthCheckTask != null) {
            healthCheckTask.cancel(true);
        }
        // Clean up any open channels
        monitoredModules.values().forEach(module -> {
            if (module.grpcChannel != null) {
                module.grpcChannel.shutdown();
            }
        });
        LOG.info("Module health monitor stopped");
    }
    
    @Override
    public Mono<Void> startMonitoring(ModuleInfo moduleInfo, HealthCheckType healthCheckType, String healthCheckEndpoint) {
        return Mono.fromRunnable(() -> {
            String moduleId = moduleInfo.getServiceId();
            
            MonitoredModule module = new MonitoredModule(
                moduleInfo,
                healthCheckType,
                healthCheckEndpoint,
                createInitialHealthStatus(moduleInfo)
            );
            
            // Initialize gRPC channel if needed
            if (healthCheckType == HealthCheckType.GRPC) {
                module.grpcChannel = ManagedChannelBuilder
                    .forAddress(moduleInfo.getHost(), moduleInfo.getPort())
                    .usePlaintext()
                    .build();
            }
            
            monitoredModules.put(moduleId, module);
            LOG.info("Started monitoring module: {} with health check type: {}", 
                moduleId, healthCheckType);
        }).then();
    }
    
    @Override
    public Mono<Void> stopMonitoring(String moduleId) {
        return Mono.fromRunnable(() -> {
            MonitoredModule removed = monitoredModules.remove(moduleId);
            if (removed != null) {
                if (removed.grpcChannel != null) {
                    removed.grpcChannel.shutdown();
                }
                LOG.info("Stopped monitoring module: {}", moduleId);
            }
        }).then();
    }
    
    @Override
    public Mono<ModuleHealthStatus> checkHealth(String moduleId) {
        return Mono.fromCallable(() -> {
            MonitoredModule module = monitoredModules.get(moduleId);
            if (module == null) {
                return createUnknownHealthStatus(moduleId);
            }
            return performHealthCheck(module);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<ModuleHealthStatus> getCachedHealthStatus(String moduleId) {
        return Mono.fromCallable(() -> {
            MonitoredModule module = monitoredModules.get(moduleId);
            if (module == null) {
                return createUnknownHealthStatus(moduleId);
            }
            return module.lastHealthStatus;
        });
    }
    
    @Override
    public void addHealthChangeListener(HealthChangeListener listener) {
        listeners.add(listener);
    }
    
    @Override
    public void removeHealthChangeListener(HealthChangeListener listener) {
        listeners.remove(listener);
    }
    
    private void performScheduledHealthChecks() {
        monitoredModules.values().parallelStream().forEach(module -> {
            try {
                ModuleHealthStatus newStatus = performHealthCheck(module);
                ModuleHealthStatus previousStatus = module.lastHealthStatus;
                
                module.lastHealthStatus = newStatus;
                module.lastCheckTime = Instant.now();
                
                if (newStatus.getIsHealthy() != previousStatus.getIsHealthy()) {
                    notifyHealthChange(module.moduleInfo.getServiceId(), newStatus, previousStatus);
                }
            } catch (Exception e) {
                LOG.error("Error performing health check for module: {}", 
                    module.moduleInfo.getServiceId(), e);
            }
        });
    }
    
    private ModuleHealthStatus performHealthCheck(MonitoredModule module) {
        try {
            boolean isHealthy = false;
            String details = "";
            
            switch (module.healthCheckType) {
                case HTTP:
                    var httpResult = performHttpHealthCheck(module);
                    isHealthy = httpResult.healthy;
                    details = httpResult.details;
                    break;
                    
                case GRPC:
                    var grpcResult = performGrpcHealthCheck(module);
                    isHealthy = grpcResult.healthy;
                    details = grpcResult.details;
                    break;
                    
                case TCP:
                    var tcpResult = performTcpHealthCheck(module);
                    isHealthy = tcpResult.healthy;
                    details = tcpResult.details;
                    break;
                    
                case TTL:
                    // TTL health is managed by heartbeats, not active checks
                    isHealthy = isTtlHealthy(module);
                    details = isHealthy ? "TTL healthy" : "TTL expired";
                    break;
                    
                default:
                    details = "Unknown health check type";
            }
            
            if (!isHealthy) {
                module.consecutiveFailures++;
            } else {
                module.consecutiveFailures = 0;
            }
            
            return ModuleHealthStatus.newBuilder()
                .setServiceId(module.moduleInfo.getServiceId())
                .setServiceName(module.moduleInfo.getServiceName())
                .setIsHealthy(isHealthy && module.consecutiveFailures < MAX_CONSECUTIVE_FAILURES)
                .setHealthDetails(details)
                .setLastChecked(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .build())
                .build();
                
        } catch (Exception e) {
            LOG.error("Error performing health check for module: {}", 
                module.moduleInfo.getServiceId(), e);
            return createErrorHealthStatus(module.moduleInfo, e.getMessage());
        }
    }
    
    private HealthCheckResult performHttpHealthCheck(MonitoredModule module) {
        String endpoint = module.healthCheckEndpoint != null ? module.healthCheckEndpoint : "/health";
        String url = String.format("http://%s:%d%s", 
            module.moduleInfo.getHost(), 
            module.moduleInfo.getPort(), 
            endpoint);
            
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) HEALTH_CHECK_TIMEOUT.toMillis());
            connection.setReadTimeout((int) HEALTH_CHECK_TIMEOUT.toMillis());
            
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            
            boolean healthy = responseCode >= 200 && responseCode < 300;
            String details = String.format("HTTP %d from %s", responseCode, url);
            
            return new HealthCheckResult(healthy, details);
        } catch (Exception e) {
            return new HealthCheckResult(false, "HTTP health check failed: " + e.getMessage());
        }
    }
    
    private HealthCheckResult performGrpcHealthCheck(MonitoredModule module) {
        if (module.grpcChannel == null) {
            return new HealthCheckResult(false, "gRPC channel not initialized");
        }
        
        try {
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(module.grpcChannel);
            
            HealthCheckRequest.Builder requestBuilder = HealthCheckRequest.newBuilder();
            if (module.healthCheckEndpoint != null && !module.healthCheckEndpoint.isEmpty()) {
                requestBuilder.setService(module.healthCheckEndpoint);
            }
            
            HealthCheckResponse response = healthStub
                .withDeadlineAfter(HEALTH_CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .check(requestBuilder.build());
                
            boolean healthy = response.getStatus() == HealthCheckResponse.ServingStatus.SERVING;
            String details = String.format("gRPC health status: %s", response.getStatus());
            
            return new HealthCheckResult(healthy, details);
        } catch (StatusRuntimeException e) {
            return new HealthCheckResult(false, "gRPC health check failed: " + e.getStatus());
        } catch (Exception e) {
            return new HealthCheckResult(false, "gRPC health check error: " + e.getMessage());
        }
    }
    
    private HealthCheckResult performTcpHealthCheck(MonitoredModule module) {
        try (Socket socket = new Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(
                    module.moduleInfo.getHost(), 
                    module.moduleInfo.getPort()
                ),
                (int) HEALTH_CHECK_TIMEOUT.toMillis()
            );
            return new HealthCheckResult(true, "TCP connection successful");
        } catch (IOException e) {
            return new HealthCheckResult(false, "TCP connection failed: " + e.getMessage());
        }
    }
    
    private boolean isTtlHealthy(MonitoredModule module) {
        // For TTL, we rely on the module registration service to track heartbeats
        // This is a simplified check - in production, integrate with the registration service
        return module.lastCheckTime != null && 
               Duration.between(module.lastCheckTime, Instant.now()).toSeconds() < 30;
    }
    
    private void notifyHealthChange(String moduleId, ModuleHealthStatus newStatus, ModuleHealthStatus previousStatus) {
        LOG.info("Module {} health changed from {} to {}", 
            moduleId, 
            previousStatus.getIsHealthy() ? "healthy" : "unhealthy",
            newStatus.getIsHealthy() ? "healthy" : "unhealthy");
            
        listeners.forEach(listener -> {
            try {
                listener.onHealthChange(moduleId, newStatus, previousStatus);
            } catch (Exception e) {
                LOG.error("Error notifying health change listener", e);
            }
        });
    }
    
    private ModuleHealthStatus createInitialHealthStatus(ModuleInfo moduleInfo) {
        return ModuleHealthStatus.newBuilder()
            .setServiceId(moduleInfo.getServiceId())
            .setServiceName(moduleInfo.getServiceName())
            .setIsHealthy(true)
            .setHealthDetails("Initial status - not yet checked")
            .build();
    }
    
    private ModuleHealthStatus createUnknownHealthStatus(String moduleId) {
        return ModuleHealthStatus.newBuilder()
            .setServiceId(moduleId)
            .setIsHealthy(false)
            .setHealthDetails("Module not found in monitoring")
            .build();
    }
    
    private ModuleHealthStatus createErrorHealthStatus(ModuleInfo moduleInfo, String error) {
        return ModuleHealthStatus.newBuilder()
            .setServiceId(moduleInfo.getServiceId())
            .setServiceName(moduleInfo.getServiceName())
            .setIsHealthy(false)
            .setHealthDetails("Health check error: " + error)
            .build();
    }
    
    /**
     * Internal class to track monitored modules.
     */
    private static class MonitoredModule {
        final ModuleInfo moduleInfo;
        final HealthCheckType healthCheckType;
        final String healthCheckEndpoint;
        volatile ModuleHealthStatus lastHealthStatus;
        volatile Instant lastCheckTime;
        volatile int consecutiveFailures = 0;
        ManagedChannel grpcChannel;
        
        MonitoredModule(ModuleInfo moduleInfo, HealthCheckType healthCheckType, 
                       String healthCheckEndpoint, ModuleHealthStatus initialStatus) {
            this.moduleInfo = moduleInfo;
            this.healthCheckType = healthCheckType;
            this.healthCheckEndpoint = healthCheckEndpoint;
            this.lastHealthStatus = initialStatus;
        }
    }
    
    /**
     * Result of a health check.
     */
    private static class HealthCheckResult {
        final boolean healthy;
        final String details;
        
        HealthCheckResult(boolean healthy, String details) {
            this.healthy = healthy;
            this.details = details;
        }
    }
}