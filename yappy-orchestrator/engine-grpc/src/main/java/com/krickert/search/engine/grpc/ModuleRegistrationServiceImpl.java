package com.krickert.search.engine.grpc;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import com.krickert.yappy.registration.api.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * gRPC service implementation for module registration.
 * This service allows modules to register themselves with the engine,
 * and the engine then registers them with Consul for service discovery.
 */
@GrpcService
public class ModuleRegistrationServiceImpl extends ModuleRegistrationServiceGrpc.ModuleRegistrationServiceImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationServiceImpl.class);
    
    private final ConsulClient consulClient;
    
    @Inject
    public ModuleRegistrationServiceImpl(ConsulClient consulClient) {
        this.consulClient = consulClient;
        LOG.info("Initialized ModuleRegistrationService");
    }
    
    @Override
    public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
        LOG.info("Received registration request for module: {} at {}:{}", 
                request.getImplementationId(), request.getHost(), request.getPort());
        
        try {
            // Generate a unique service ID
            String serviceId = generateServiceId(request);
            
            // Create Consul service registration
            NewService service = new NewService();
            service.setId(serviceId);
            service.setName(request.getImplementationId());
            service.setAddress(request.getHost());
            service.setPort(request.getPort());
            
            // Set tags
            List<String> tags = new ArrayList<>();
            tags.add("module");
            tags.add("grpc");
            tags.add("v1");
            
            // Add version tag if provided
            if (request.hasModuleSoftwareVersion()) {
                tags.add("version:" + request.getModuleSoftwareVersion());
            }
            
            // Add custom tags from request
            request.getAdditionalTagsMap().forEach((k, v) -> tags.add(k + ":" + v));
            
            service.setTags(tags);
            
            // Set metadata
            Map<String, String> meta = new HashMap<>();
            meta.put("implementation-id", request.getImplementationId());
            meta.put("instance-name", request.getInstanceServiceName());
            meta.put("grpc-service", "PipeStepProcessor");
            
            // Calculate and store config digest
            String configDigest = "";
            if (!request.getInstanceCustomConfigJson().isEmpty()) {
                configDigest = calculateConfigDigest(request.getInstanceCustomConfigJson());
                meta.put("config-digest", configDigest);
                meta.put("config-json", Base64.getEncoder().encodeToString(
                        request.getInstanceCustomConfigJson().getBytes(StandardCharsets.UTF_8)));
            }
            
            service.setMeta(meta);
            
            // Configure health check based on type
            NewService.Check check = new NewService.Check();
            switch (request.getHealthCheckType()) {
                case HTTP:
                    check.setHttp(String.format("http://%s:%d%s", 
                            request.getHost(), request.getPort(), request.getHealthCheckEndpoint()));
                    check.setInterval("10s");
                    break;
                case GRPC:
                    check.setGrpc(String.format("%s:%d/%s", 
                            request.getHost(), request.getPort(), request.getHealthCheckEndpoint()));
                    check.setInterval("10s");
                    break;
                case TCP:
                    check.setTcp(String.format("%s:%d", request.getHost(), request.getPort()));
                    check.setInterval("10s");
                    break;
                case TTL:
                    check.setTtl("30s");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported health check type: " + request.getHealthCheckType());
            }
            service.setCheck(check);
            
            // Register with Consul
            consulClient.agentServiceRegister(service);
            LOG.info("Successfully registered module {} with Consul as service ID: {}", 
                    request.getImplementationId(), serviceId);
            
            // Build successful response
            RegisterModuleResponse response = RegisterModuleResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module registered successfully")
                    .setRegisteredServiceId(serviceId)
                    .setCalculatedConfigDigest(configDigest)
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            
        } catch (Exception e) {
            LOG.error("Failed to register module: {}", request.getImplementationId(), e);
            
            RegisterModuleResponse response = RegisterModuleResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Registration failed: " + e.getMessage())
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    private String generateServiceId(RegisterModuleRequest request) {
        // Use instance ID hint if provided, otherwise generate one
        if (request.hasInstanceIdHint() && !request.getInstanceIdHint().isEmpty()) {
            return request.getImplementationId() + "-" + request.getInstanceIdHint();
        } else {
            return request.getImplementationId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
    
    private String calculateConfigDigest(String configJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(configJson.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}