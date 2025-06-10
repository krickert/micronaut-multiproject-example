package com.krickert.search.pipeline.step.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.yappy.registration.api.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of YappyModuleRegistrationService for simpler deployments
 * without Consul. This is useful for:
 * - Local development
 * - Testing
 * - Single-node deployments
 * - Environments where Consul is not available
 * 
 * Note: This implementation does NOT provide:
 * - Persistence across restarts
 * - Multi-node coordination
 * - Health checking beyond in-memory status
 */
@Singleton
@GrpcService
@Replaces(YappyModuleRegistrationServiceImpl.class)
@Requires(property = "consul.client.enabled", value = "false")
public class InMemoryModuleRegistrationService extends YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryModuleRegistrationService.class);
    
    // In-memory storage for registered modules
    private final Map<String, ModuleRegistration> registeredModules = new ConcurrentHashMap<>();
    private final Map<String, ModuleInstance> moduleInstances = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    
    @Inject
    public InMemoryModuleRegistrationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        LOG.info("Initialized InMemoryModuleRegistrationService (non-Consul mode)");
    }
    
    @Override
    public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
        try {
            LOG.info("Registering module: implementation={}, instance={}, host={}:{}", 
                    request.getImplementationId(), 
                    request.getInstanceServiceName(),
                    request.getHost(), 
                    request.getPort());
            
            // Generate service ID
            String serviceId = generateServiceId(request);
            
            // Calculate config digest if config provided
            String configDigest = "";
            String canonicalJson = "";
            if (!request.getInstanceCustomConfigJson().isEmpty()) {
                try {
                    // Parse and re-serialize to get canonical form
                    Object parsed = objectMapper.readValue(request.getInstanceCustomConfigJson(), Object.class);
                    canonicalJson = objectMapper.writeValueAsString(parsed);
                    configDigest = calculateDigest(canonicalJson);
                } catch (Exception e) {
                    LOG.warn("Failed to parse custom config JSON, using raw string", e);
                    canonicalJson = request.getInstanceCustomConfigJson();
                    configDigest = calculateDigest(canonicalJson);
                }
            }
            
            // Create module registration
            ModuleRegistration registration = new ModuleRegistration(
                    request.getImplementationId(),
                    request.getHost(),
                    request.getPort(),
                    request.getHealthCheckType(),
                    request.getHealthCheckEndpoint(),
                    configDigest,
                    request.hasModuleSoftwareVersion() ? request.getModuleSoftwareVersion() : "unknown",
                    Instant.now()
            );
            
            // Create module instance
            ModuleInstance instance = new ModuleInstance(
                    serviceId,
                    request.getInstanceServiceName(),
                    request.getImplementationId(),
                    registration,
                    Instant.now(),
                    "healthy" // Assume healthy on registration
            );
            
            // Store in memory
            registeredModules.put(request.getImplementationId(), registration);
            moduleInstances.put(serviceId, instance);
            
            // Build response
            RegisterModuleResponse.Builder responseBuilder = RegisterModuleResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module registered successfully in in-memory registry")
                    .setRegisteredServiceId(serviceId)
                    .setCalculatedConfigDigest(configDigest);
            
            if (!canonicalJson.isEmpty()) {
                responseBuilder.setCanonicalConfigJsonBase64(
                        Base64.getEncoder().encodeToString(canonicalJson.getBytes(StandardCharsets.UTF_8))
                );
            }
            
            RegisterModuleResponse response = responseBuilder.build();
            
            LOG.info("Successfully registered module {} with service ID: {}", 
                    request.getImplementationId(), serviceId);
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.error("Failed to register module", e);
            
            RegisterModuleResponse errorResponse = RegisterModuleResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Registration failed: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(errorResponse);
            responseObserver.onCompleted();
        }
    }
    
    /**
     * Get all registered modules (for testing/debugging).
     */
    public Map<String, ModuleRegistration> getRegisteredModules() {
        return new ConcurrentHashMap<>(registeredModules);
    }
    
    /**
     * Get all module instances (for testing/debugging).
     */
    public Map<String, ModuleInstance> getModuleInstances() {
        return new ConcurrentHashMap<>(moduleInstances);
    }
    
    /**
     * Find module instances by implementation ID.
     */
    public Map<String, ModuleInstance> findInstancesByImplementation(String implementationId) {
        Map<String, ModuleInstance> result = new ConcurrentHashMap<>();
        moduleInstances.forEach((serviceId, instance) -> {
            if (instance.implementationId().equals(implementationId)) {
                result.put(serviceId, instance);
            }
        });
        return result;
    }
    
    /**
     * Unregister a module instance (not part of gRPC API yet).
     */
    public boolean unregisterInstance(String serviceId) {
        ModuleInstance removed = moduleInstances.remove(serviceId);
        if (removed != null) {
            LOG.info("Unregistered module instance: {}", serviceId);
            
            // Check if this was the last instance of this implementation
            boolean hasOtherInstances = moduleInstances.values().stream()
                    .anyMatch(instance -> instance.implementationId().equals(removed.implementationId()));
            
            if (!hasOtherInstances) {
                registeredModules.remove(removed.implementationId());
                LOG.info("Removed module registration for: {}", removed.implementationId());
            }
            
            return true;
        }
        return false;
    }
    
    /**
     * Clear all registrations (useful for testing).
     */
    public void clearAll() {
        registeredModules.clear();
        moduleInstances.clear();
        LOG.info("Cleared all module registrations");
    }
    
    private String generateServiceId(RegisterModuleRequest request) {
        // Generate a unique service ID based on the request
        return String.format("%s-%s-%s-%d-%s",
                request.getImplementationId(),
                request.getInstanceServiceName(),
                request.getHost().replace(".", "-"),
                request.getPort(),
                UUID.randomUUID().toString().substring(0, 8)
        );
    }
    
    private String calculateDigest(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
    
    /**
     * Internal record for module registration data.
     */
    public record ModuleRegistration(
            String implementationId,
            String host,
            int port,
            HealthCheckType healthCheckType,
            String healthCheckEndpoint,
            String configDigest,
            String version,
            Instant registeredAt
    ) {}
    
    /**
     * Internal record for module instance data.
     */
    public record ModuleInstance(
            String serviceId,
            String instanceName,
            String implementationId,
            ModuleRegistration registration,
            Instant createdAt,
            String status
    ) {}
}