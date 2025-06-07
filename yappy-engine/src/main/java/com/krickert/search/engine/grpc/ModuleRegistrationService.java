package com.krickert.search.engine.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.grpc.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.ImmutableRegCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC service for module registration with the engine.
 * Modules call this service to register themselves, allowing the engine
 * to manage them in Consul and route requests appropriately.
 */
@GrpcService
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class ModuleRegistrationService extends ModuleRegistrationGrpc.ModuleRegistrationImplBase {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleRegistrationService.class);
    
    private final ConsulBusinessOperationsService consulService;
    private final Map<String, ModuleInfo> registeredModules = new ConcurrentHashMap<>();
    
    @Inject
    public ModuleRegistrationService(ConsulBusinessOperationsService consulService) {
        this.consulService = consulService;
    }
    
    @Override
    public void registerModule(ModuleInfo request, StreamObserver<RegistrationStatus> responseObserver) {
        log.info("Registering module: {} ({})", request.getServiceName(), request.getServiceId());
        
        try {
            // Create Consul registration
            Registration registration = createConsulRegistration(request);
            
            // Register with Consul
            consulService.registerService(registration);
            
            // Store module info locally
            registeredModules.put(request.getServiceId(), request);
            
            // Build success response
            RegistrationStatus status = RegistrationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module registered successfully")
                    .setRegisteredAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .setConsulServiceId(registration.getId())
                    .build();
            
            responseObserver.onNext(status);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to register module: {}", request.getServiceName(), e);
            
            RegistrationStatus status = RegistrationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Registration failed: " + e.getMessage())
                    .setRegisteredAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .build();
            
            responseObserver.onNext(status);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void unregisterModule(ModuleId request, StreamObserver<UnregistrationStatus> responseObserver) {
        log.info("Unregistering module: {}", request.getServiceId());
        
        try {
            // Remove from Consul
            consulService.deregisterService(request.getServiceId());
            
            // Remove from local storage
            registeredModules.remove(request.getServiceId());
            
            UnregistrationStatus status = UnregistrationStatus.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module unregistered successfully")
                    .setUnregisteredAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .build();
            
            responseObserver.onNext(status);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to unregister module: {}", request.getServiceId(), e);
            
            UnregistrationStatus status = UnregistrationStatus.newBuilder()
                    .setSuccess(false)
                    .setMessage("Unregistration failed: " + e.getMessage())
                    .setUnregisteredAt(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .build();
            
            responseObserver.onNext(status);
            responseObserver.onCompleted();
        }
    }
    
    @Override
    public void heartbeat(ModuleHeartbeat request, StreamObserver<HeartbeatAck> responseObserver) {
        log.debug("Received heartbeat from module: {}", request.getServiceId());
        
        HeartbeatAck ack = HeartbeatAck.newBuilder()
                .setAcknowledged(true)
                .setServerTime(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .setMessage("Heartbeat received")
                .build();
        
        responseObserver.onNext(ack);
        responseObserver.onCompleted();
    }
    
    @Override
    public void getModuleHealth(ModuleId request, StreamObserver<ModuleHealthStatus> responseObserver) {
        log.debug("Getting health status for module: {}", request.getServiceId());
        
        try {
            ModuleInfo moduleInfo = registeredModules.get(request.getServiceId());
            if (moduleInfo == null) {
                throw new IllegalArgumentException("Module not found: " + request.getServiceId());
            }
            
            // Check module health via gRPC health check
            boolean isHealthy = checkModuleHealth(moduleInfo);
            
            ModuleHealthStatus status = ModuleHealthStatus.newBuilder()
                    .setServiceId(request.getServiceId())
                    .setServiceName(moduleInfo.getServiceName())
                    .setIsHealthy(isHealthy)
                    .setLastChecked(Timestamp.newBuilder()
                            .setSeconds(Instant.now().getEpochSecond())
                            .build())
                    .setHealthDetails(isHealthy ? "Module is healthy" : "Module health check failed")
                    .putAllMetadata(moduleInfo.getMetadataMap())
                    .build();
            
            responseObserver.onNext(status);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            log.error("Failed to get module health: {}", request.getServiceId(), e);
            responseObserver.onError(e);
        }
    }
    
    @Override
    public void listModules(Empty request, StreamObserver<ModuleList> responseObserver) {
        log.debug("Listing all registered modules");
        
        ModuleList list = ModuleList.newBuilder()
                .addAllModules(registeredModules.values())
                .setAsOf(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .build())
                .build();
        
        responseObserver.onNext(list);
        responseObserver.onCompleted();
    }
    
    private Registration createConsulRegistration(ModuleInfo moduleInfo) {
        // Create health check
        ImmutableRegCheck healthCheck = ImmutableRegCheck.builder()
                .grpc(moduleInfo.getHost() + ":" + moduleInfo.getPort())
                .interval("10s")
                .timeout("5s")
                .deregisterCriticalServiceAfter("60s")
                .build();
        
        return ImmutableRegistration.builder()
                .id(moduleInfo.getServiceId())
                .name(moduleInfo.getServiceName())
                .address(moduleInfo.getHost())
                .port(moduleInfo.getPort())
                .tags(moduleInfo.getTagsList())
                .meta(moduleInfo.getMetadataMap())
                .check(healthCheck)
                .build();
    }
    
    private boolean checkModuleHealth(ModuleInfo moduleInfo) {
        // TODO: Implement actual gRPC health check
        // For now, return true
        return true;
    }
}