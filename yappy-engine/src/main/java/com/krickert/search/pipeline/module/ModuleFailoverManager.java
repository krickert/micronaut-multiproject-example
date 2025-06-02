package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages failover for modules when they become unhealthy.
 * Implements circuit breaker pattern to prevent excessive failover attempts.
 */
@Singleton
@Requires(property = "yappy.module.failover.enabled", value = "true", defaultValue = "true")
public class ModuleFailoverManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleFailoverManager.class);
    
    private final ModuleDiscoveryService discoveryService;
    private final ConsulBusinessOperationsService consulService;
    private final GrpcChannelManager channelManager;
    private final ModuleHealthMonitor healthMonitor;
    
    private final int maxFailoverAttempts;
    private final Duration circuitBreakerResetTime;
    
    // Track failover state per module
    private final Map<String, FailoverState> failoverStates = new ConcurrentHashMap<>();
    private final Set<String> automaticFailoverEnabled = ConcurrentHashMap.newKeySet();
    
    @Inject
    public ModuleFailoverManager(
            ModuleDiscoveryService discoveryService,
            ConsulBusinessOperationsService consulService,
            GrpcChannelManager channelManager,
            ModuleHealthMonitor healthMonitor,
            @Property(name = "yappy.module.failover.max-attempts", defaultValue = "3") int maxFailoverAttempts,
            @Property(name = "yappy.module.failover.circuit-breaker-reset", defaultValue = "60s") Duration circuitBreakerResetTime) {
        this.discoveryService = discoveryService;
        this.consulService = consulService;
        this.channelManager = channelManager;
        this.healthMonitor = healthMonitor;
        this.maxFailoverAttempts = maxFailoverAttempts;
        this.circuitBreakerResetTime = circuitBreakerResetTime;
    }
    
    /**
     * Enables automatic failover for a module.
     */
    public void enableAutomaticFailover(String moduleName) {
        automaticFailoverEnabled.add(moduleName);
        LOG.info("Enabled automatic failover for module: {}", moduleName);
    }
    
    /**
     * Disables automatic failover for a module.
     */
    public void disableAutomaticFailover(String moduleName) {
        automaticFailoverEnabled.remove(moduleName);
        LOG.info("Disabled automatic failover for module: {}", moduleName);
    }
    
    /**
     * Performs failover for a module to a healthy instance.
     * 
     * @return true if failover was successful, false otherwise
     */
    public boolean performFailover(String moduleName) {
        FailoverState state = failoverStates.computeIfAbsent(moduleName, k -> new FailoverState());
        
        // Check circuit breaker
        if (state.isCircuitBreakerOpen(circuitBreakerResetTime)) {
            LOG.warn("Circuit breaker is open for module {}, rejecting failover attempt", moduleName);
            return false;
        }
        
        LOG.info("Attempting failover for module: {}", moduleName);
        
        try {
            // Get healthy instances from Consul
            return consulService.getHealthyServiceInstances(moduleName)
                    .flatMap(instances -> {
                        if (instances.isEmpty()) {
                            LOG.error("No healthy instances available for module {}", moduleName);
                            state.recordFailedAttempt();
                            return Mono.just(false);
                        }
                        
                        // Try each instance until we find a working one
                        for (ServiceHealth instance : instances) {
                            String address = instance.getService().getAddress();
                            int port = instance.getService().getPort();
                            String instanceId = instance.getService().getId();
                            
                            LOG.info("Trying failover to instance {} at {}:{}", instanceId, address, port);
                            
                            try {
                                // Create new channel for this instance
                                ManagedChannel newChannel = io.grpc.ManagedChannelBuilder
                                        .forAddress(address, port)
                                        .usePlaintext()
                                        .build();
                                
                                // Update channel manager
                                channelManager.updateChannel(moduleName, newChannel);
                                
                                // Test the new instance
                                Mono<ModuleHealthMonitor.HealthCheckResult> healthCheck = 
                                        healthMonitor.checkModuleHealth(moduleName);
                                
                                ModuleHealthMonitor.HealthCheckResult result = healthCheck.block(Duration.ofSeconds(5));
                                
                                if (result != null && result.isHealthy()) {
                                    LOG.info("Failover successful to instance {} for module {}", instanceId, moduleName);
                                    state.recordSuccessfulFailover();
                                    
                                    // Update module status
                                    discoveryService.updateModuleHealth(moduleName, true, Instant.now());
                                    
                                    return Mono.just(true);
                                }
                            } catch (Exception e) {
                                LOG.warn("Failed to failover to instance {} for module {}: {}", 
                                        instanceId, moduleName, e.getMessage());
                            }
                        }
                        
                        LOG.error("All failover attempts failed for module {}", moduleName);
                        state.recordFailedAttempt();
                        return Mono.just(false);
                    })
                    .onErrorResume(error -> {
                        LOG.error("Error during failover for module {}: {}", moduleName, error.getMessage());
                        state.recordFailedAttempt();
                        return Mono.just(false);
                    })
                    .block();
                    
        } catch (Exception e) {
            LOG.error("Unexpected error during failover for module {}", moduleName, e);
            state.recordFailedAttempt();
            return false;
        }
    }
    
    /**
     * Checks if a module has failed over.
     */
    public boolean hasFailedOver(String moduleName) {
        FailoverState state = failoverStates.get(moduleName);
        return state != null && state.hasFailedOver();
    }
    
    /**
     * Checks if the circuit breaker is open for a module.
     */
    public boolean isCircuitBreakerOpen(String moduleName) {
        FailoverState state = failoverStates.get(moduleName);
        return state != null && state.isCircuitBreakerOpen(circuitBreakerResetTime);
    }
    
    /**
     * Resets the failover state for a module.
     */
    public void resetFailoverState(String moduleName) {
        failoverStates.remove(moduleName);
        LOG.info("Reset failover state for module: {}", moduleName);
    }
    
    @PreDestroy
    public void shutdown() {
        LOG.info("Shutting down module failover manager");
        failoverStates.clear();
        automaticFailoverEnabled.clear();
    }
    
    /**
     * Tracks failover state for a module.
     */
    private class FailoverState {
        private final AtomicInteger failedAttempts = new AtomicInteger(0);
        private volatile Instant lastFailureTime;
        private volatile boolean hasFailedOver = false;
        
        void recordFailedAttempt() {
            failedAttempts.incrementAndGet();
            lastFailureTime = Instant.now();
        }
        
        void recordSuccessfulFailover() {
            hasFailedOver = true;
            failedAttempts.set(0); // Reset failed attempts on success
        }
        
        boolean hasFailedOver() {
            return hasFailedOver;
        }
        
        boolean isCircuitBreakerOpen(Duration resetTime) {
            if (failedAttempts.get() < maxFailoverAttempts) {
                return false;
            }
            
            // Check if enough time has passed to reset
            if (lastFailureTime != null) {
                Duration timeSinceLastFailure = Duration.between(lastFailureTime, Instant.now());
                if (timeSinceLastFailure.compareTo(resetTime) > 0) {
                    // Reset circuit breaker
                    failedAttempts.set(0);
                    return false;
                }
            }
            
            return true;
        }
    }
}