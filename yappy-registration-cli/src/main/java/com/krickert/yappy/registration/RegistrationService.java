package com.krickert.yappy.registration;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

@Singleton
public class RegistrationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationService.class);
    
    public void registerModule(String moduleHost, int modulePort, String engineEndpoint,
                             String instanceName, String healthCheckTypeStr, String healthCheckPath,
                             String moduleVersion) {
        
        // Step 1: Connect to module and get its registration info
        LOG.info("Connecting to module at {}:{}", moduleHost, modulePort);
        ManagedChannel moduleChannel = ManagedChannelBuilder
                .forAddress(moduleHost, modulePort)
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub moduleStub = 
                    PipeStepProcessorGrpc.newBlockingStub(moduleChannel);
            
            // Get module registration data
            LOG.info("Getting service registration from module");
            ServiceRegistrationData moduleData = moduleStub.getServiceRegistration(Empty.getDefaultInstance());
            LOG.info("Module name: {}", moduleData.getModuleName());
            LOG.info("Has config schema: {}", moduleData.hasJsonConfigSchema());
            
            // Use module name as instance name if not provided
            if (instanceName == null || instanceName.isEmpty()) {
                instanceName = moduleData.getModuleName() + "-instance";
            }
            
            // Step 2: Connect to engine and register the module
            LOG.info("Connecting to engine at {}", engineEndpoint);
            ManagedChannel engineChannel = ManagedChannelBuilder
                    .forTarget(engineEndpoint)
                    .usePlaintext()
                    .build();
            
            try {
                YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub engineStub =
                        YappyModuleRegistrationServiceGrpc.newBlockingStub(engineChannel);
                
                // Build registration request
                RegisterModuleRequest.Builder requestBuilder = RegisterModuleRequest.newBuilder()
                        .setImplementationId(moduleData.getModuleName())
                        .setInstanceServiceName(instanceName)
                        .setHost(moduleHost)
                        .setPort(modulePort)
                        .setHealthCheckType(parseHealthCheckType(healthCheckTypeStr))
                        .setHealthCheckEndpoint(healthCheckPath);
                
                // Add config schema if available
                if (moduleData.hasJsonConfigSchema()) {
                    requestBuilder.setInstanceCustomConfigJson(moduleData.getJsonConfigSchema());
                }
                
                // Add version if provided
                if (moduleVersion != null && !moduleVersion.isEmpty()) {
                    requestBuilder.setModuleSoftwareVersion(moduleVersion);
                }
                
                RegisterModuleRequest request = requestBuilder.build();
                
                LOG.info("Registering module with engine");
                RegisterModuleResponse response = engineStub.registerModule(request);
                
                if (response.getSuccess()) {
                    LOG.info("Registration successful!");
                    LOG.info("Service ID: {}", response.getRegisteredServiceId());
                    LOG.info("Message: {}", response.getMessage());
                    if (!response.getCalculatedConfigDigest().isEmpty()) {
                        LOG.info("Config digest: {}", response.getCalculatedConfigDigest());
                    }
                } else {
                    LOG.error("Registration failed: {}", response.getMessage());
                    throw new RuntimeException("Registration failed: " + response.getMessage());
                }
                
            } finally {
                engineChannel.shutdown();
                try {
                    engineChannel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    engineChannel.shutdownNow();
                }
            }
            
        } catch (StatusRuntimeException e) {
            LOG.error("gRPC error: {}", e.getStatus());
            throw new RuntimeException("gRPC error: " + e.getStatus(), e);
        } finally {
            moduleChannel.shutdown();
            try {
                moduleChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                moduleChannel.shutdownNow();
            }
        }
    }
    
    private HealthCheckType parseHealthCheckType(String type) {
        return switch (type.toUpperCase()) {
            case "HTTP" -> HealthCheckType.HTTP;
            case "GRPC" -> HealthCheckType.GRPC;
            case "TCP" -> HealthCheckType.TCP;
            case "TTL" -> HealthCheckType.TTL;
            default -> throw new IllegalArgumentException("Invalid health check type: " + type);
        };
    }
}