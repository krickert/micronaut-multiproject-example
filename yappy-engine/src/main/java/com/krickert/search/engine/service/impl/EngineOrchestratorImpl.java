package com.krickert.search.engine.service.impl;

import com.krickert.search.engine.grpc.ModuleRegistrationMetrics;
import com.krickert.search.engine.kafka.KafkaConsumerService;
import com.krickert.search.engine.service.EngineHealthStatus;
import com.krickert.search.engine.service.EngineOrchestrator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of EngineOrchestrator that manages the engine lifecycle.
 */
@Singleton
public class EngineOrchestratorImpl implements EngineOrchestrator {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineOrchestratorImpl.class);
    
    private final ModuleRegistrationMetrics registrationMetrics;
    private final KafkaConsumerService kafkaConsumerService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    @Inject
    public EngineOrchestratorImpl(
            ModuleRegistrationMetrics registrationMetrics,
            KafkaConsumerService kafkaConsumerService) {
        this.registrationMetrics = registrationMetrics;
        this.kafkaConsumerService = kafkaConsumerService;
    }
    
    @Override
    public Mono<Void> start() {
        return Mono.fromRunnable(() -> {
            if (running.compareAndSet(false, true)) {
                LOG.info("Starting Engine Orchestrator...");
                
                // Start Kafka consumer service
                kafkaConsumerService.startConsumers();
                
                // Initialize other services as needed
                
                LOG.info("Engine Orchestrator started successfully");
            } else {
                LOG.warn("Engine Orchestrator is already running");
            }
        });
    }
    
    @Override
    public Mono<Void> stop() {
        return Mono.fromRunnable(() -> {
            if (running.compareAndSet(true, false)) {
                LOG.info("Stopping Engine Orchestrator...");
                
                // Stop Kafka consumer service
                kafkaConsumerService.stopConsumers();
                
                // Shutdown other services as needed
                
                LOG.info("Engine Orchestrator stopped successfully");
            } else {
                LOG.warn("Engine Orchestrator is not running");
            }
        });
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public EngineHealthStatus getHealthStatus() {
        Map<String, EngineHealthStatus.ComponentHealth> componentStatuses = new HashMap<>();
        
        // Check Kafka consumer health
        boolean kafkaHealthy = kafkaConsumerService.isHealthy();
        componentStatuses.put("kafka-consumer", new EngineHealthStatus.ComponentHealth(
            "Kafka Consumer Service",
            kafkaHealthy ? EngineHealthStatus.Status.HEALTHY : EngineHealthStatus.Status.UNHEALTHY,
            kafkaHealthy ? "All consumers running" : "Some consumers are down",
            Map.of("consumerCount", kafkaConsumerService.getActiveConsumerCount())
        ));
        
        // Check module registration service health
        componentStatuses.put("module-registration", new EngineHealthStatus.ComponentHealth(
            "Module Registration Service",
            EngineHealthStatus.Status.HEALTHY,
            "Service is available",
            Map.of("registeredModules", registrationMetrics.getRegisteredModuleCount())
        ));
        
        // Determine overall status
        EngineHealthStatus.Status overallStatus = componentStatuses.values().stream()
            .map(EngineHealthStatus.ComponentHealth::status)
            .reduce(EngineHealthStatus.Status.HEALTHY, (acc, status) -> {
                if (status == EngineHealthStatus.Status.UNHEALTHY) {
                    return EngineHealthStatus.Status.UNHEALTHY;
                } else if (status == EngineHealthStatus.Status.DEGRADED && acc != EngineHealthStatus.Status.UNHEALTHY) {
                    return EngineHealthStatus.Status.DEGRADED;
                }
                return acc;
            });
        
        return new EngineHealthStatus(
            overallStatus,
            componentStatuses,
            Instant.now()
        );
    }
}