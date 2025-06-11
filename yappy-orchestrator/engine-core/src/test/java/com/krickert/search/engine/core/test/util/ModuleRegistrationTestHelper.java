package com.krickert.search.engine.core.test.util;

import com.google.protobuf.Empty;
import com.krickert.search.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test helper for module registration operations.
 * Simplifies registration and deregistration of modules during tests.
 */
public class ModuleRegistrationTestHelper {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationTestHelper.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    private final String registrationHost;
    private final int registrationPort;
    private final ManagedChannel channel;
    private final ModuleRegistrationGrpc.ModuleRegistrationBlockingStub registrationStub;
    
    public ModuleRegistrationTestHelper(String registrationHost, int registrationPort) {
        this.registrationHost = registrationHost;
        this.registrationPort = registrationPort;
        this.channel = ManagedChannelBuilder
                .forAddress(registrationHost, registrationPort)
                .usePlaintext()
                .build();
        this.registrationStub = ModuleRegistrationGrpc.newBlockingStub(channel);
    }
    
    /**
     * Registers a module with the given configuration
     */
    public RegistrationStatus registerModule(String serviceName, String serviceId, 
                                           String host, int port,
                                           Map<String, String> metadata) {
        LOG.info("Registering module: {} ({})", serviceName, serviceId);
        
        ModuleInfo.Builder builder = ModuleInfo.newBuilder()
                .setServiceName(serviceName)
                .setServiceId(serviceId)
                .setHost(host)
                .setPort(port);
        
        if (metadata != null) {
            builder.putAllMetadata(metadata);
        }
        
        try {
            RegistrationStatus response = registrationStub.registerModule(builder.build());
            LOG.info("Successfully registered module: {} with status: {}", 
                    serviceName, response.getMessage());
            return response;
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to register module: {}", serviceName, e);
            throw new RuntimeException("Module registration failed", e);
        }
    }
    
    /**
     * Deregisters a module by ID
     */
    public void deregisterModule(String serviceId) {
        LOG.info("Deregistering module with ID: {}", serviceId);
        
        ModuleId moduleId = ModuleId.newBuilder()
                .setServiceId(serviceId)
                .build();
        
        try {
            UnregistrationStatus status = registrationStub.unregisterModule(moduleId);
            LOG.info("Successfully deregistered module: {} - {}", serviceId, status.getMessage());
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to deregister module: {}", serviceId, e);
            throw new RuntimeException("Module deregistration failed", e);
        }
    }
    
    /**
     * Gets all registered modules
     */
    public List<ModuleInfo> getAllModules() {
        try {
            ModuleList response = registrationStub.listModules(Empty.getDefaultInstance());
            LOG.info("Found {} registered modules", response.getModulesCount());
            return response.getModulesList();
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to get modules", e);
            throw new RuntimeException("Failed to get modules", e);
        }
    }
    
    /**
     * Convenience method to register a standard processor module
     */
    public RegistrationStatus registerProcessorModule(String moduleName, String host, int port) {
        String serviceId = moduleName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return registerModule(moduleName, serviceId, host, port, 
                Map.of("type", "processor"));
    }
    
    /**
     * Convenience method to register a sink module
     */
    public RegistrationStatus registerSinkModule(String moduleName, String host, int port) {
        String serviceId = moduleName + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return registerModule(moduleName, serviceId, host, port,
                Map.of("type", "sink"));
    }
    
    /**
     * Cleanup - closes the gRPC channel
     */
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.warn("Channel shutdown interrupted", e);
            channel.shutdownNow();
        }
    }
    
    /**
     * Creates a sample module configuration for testing
     */
    public static ModuleInfo createSampleModuleInfo(String serviceName, String serviceId) {
        return ModuleInfo.newBuilder()
                .setServiceName(serviceName)
                .setServiceId(serviceId)
                .setHost("localhost")
                .setPort(50051)
                .putMetadata("version", "1.0.0")
                .putMetadata("type", "processor")
                .build();
    }
}