package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import com.google.common.net.HostAndPort;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.NotRegisteredException;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for module integration tests.
 * Manages Consul registration/cleanup and gRPC server lifecycle.
 */
@Singleton
public class ModuleTestHelper {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleTestHelper.class);
    
    @Inject
    private ConsulBusinessOperationsService consulService;
    
    @Property(name = "consul.client.host")
    private String consulHost;
    
    @Property(name = "consul.client.port")
    private int consulPort;
    
    @Property(name = "app.config.consul.key-prefixes.pipeline-clusters", defaultValue = "pipeline-configs/clusters")
    private String pipelineClusterPrefix;
    
    private final Map<String, Server> grpcServers = new ConcurrentHashMap<>();
    private final Map<String, String> registeredServices = new ConcurrentHashMap<>();
    private Consul consul;
    
    public void initialize() {
        if (consul == null) {
            consul = Consul.builder()
                    .withHostAndPort(HostAndPort.fromParts(consulHost, consulPort))
                    .build();
        }
    }
    
    /**
     * Clean up all test data from Consul
     */
    public void cleanupAllTestData() {
        log.info("Cleaning up all test data from Consul");
        
        // Clean up registered services
        registeredServices.forEach((serviceId, serviceName) -> {
            try {
                consul.agentClient().deregister(serviceId);
                log.info("Deregistered service: {} ({})", serviceName, serviceId);
            } catch (Exception e) {
                log.warn("Failed to deregister service {}: {}", serviceId, e.getMessage());
            }
        });
        registeredServices.clear();
        
        // Clean up gRPC servers
        grpcServers.forEach((name, server) -> {
            try {
                server.shutdown();
                server.awaitTermination(5, TimeUnit.SECONDS);
                log.info("Shut down gRPC server: {}", name);
            } catch (Exception e) {
                log.warn("Failed to shutdown gRPC server {}: {}", name, e.getMessage());
            }
        });
        grpcServers.clear();
        
        // Clean up test pipeline configs
        try {
            List<String> keys = consul.keyValueClient().getKeys(pipelineClusterPrefix);
            if (keys != null) {
                keys.stream()
                    .filter(key -> key.contains("test-"))
                    .forEach(key -> {
                        try {
                            consul.keyValueClient().deleteKey(key);
                            log.info("Deleted test config: {}", key);
                        } catch (Exception e) {
                            log.warn("Failed to delete key {}: {}", key, e.getMessage());
                        }
                    });
            }
        } catch (Exception e) {
            log.warn("Failed to clean up pipeline configs: {}", e.getMessage());
        }
    }
    
    /**
     * Register a test module in Consul
     */
    public RegisteredModule registerTestModule(String serviceName, String pipeStepName, 
                                               io.grpc.BindableService grpcService,
                                               List<String> tags) throws IOException {
        initialize();
        
        // Find available port
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        
        // Start gRPC server
        Server grpcServer = ServerBuilder
                .forPort(port)
                .addService(grpcService)
                .build()
                .start();
        
        grpcServers.put(serviceName, grpcServer);
        
        // Register in Consul
        String serviceId = serviceName + "-" + UUID.randomUUID();
        
        List<String> allTags = new ArrayList<>(tags);
        if (!allTags.contains("yappy-module")) {
            allTags.add("yappy-module");
        }
        
        Registration registration = ImmutableRegistration.builder()
                .id(serviceId)
                .name(serviceName)
                .address("localhost")
                .port(port)
                .addAllTags(allTags)
                .putMeta("grpc-port", String.valueOf(port))
                .putMeta("pipe-step-name", pipeStepName)
                .check(Registration.RegCheck.ttl(10L))
                .build();
        
        consul.agentClient().register(registration);
        try {
            consul.agentClient().pass(serviceId); // Mark as healthy
        } catch (NotRegisteredException e) {
            log.warn("Could not mark service as healthy immediately: {}", e.getMessage());
        }
        
        registeredServices.put(serviceId, serviceName);
        
        log.info("Registered test module: {} ({}), port: {}", serviceName, serviceId, port);
        
        return new RegisteredModule(serviceName, serviceId, port, grpcServer);
    }
    
    
    /**
     * Wait for service to be discovered
     */
    public void waitForServiceDiscovery(String serviceName, long timeoutMs) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                var services = consulService.listServices().block();
                if (services != null && services.containsKey(serviceName)) {
                    log.info("Service {} discovered", serviceName);
                    return;
                }
            } catch (Exception e) {
                // Ignore and retry
            }
            Thread.sleep(500);
        }
        
        throw new RuntimeException("Service " + serviceName + " not discovered within timeout");
    }
    
    /**
     * Mark a service as unhealthy
     */
    public void markServiceUnhealthy(String serviceId) {
        try {
            consul.agentClient().fail(serviceId);
            log.info("Marked service {} as unhealthy", serviceId);
        } catch (NotRegisteredException e) {
            log.warn("Service {} not registered: {}", serviceId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to mark service {} as unhealthy: {}", serviceId, e.getMessage());
        }
    }
    
    /**
     * Mark a service as healthy
     */
    public void markServiceHealthy(String serviceId) {
        try {
            consul.agentClient().pass(serviceId);
            log.info("Marked service {} as healthy", serviceId);
        } catch (NotRegisteredException e) {
            log.warn("Service {} not registered: {}", serviceId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to mark service {} as healthy: {}", serviceId, e.getMessage());
        }
    }
    
    public static class RegisteredModule {
        private final String serviceName;
        private final String serviceId;
        private final int port;
        private final Server grpcServer;
        
        public RegisteredModule(String serviceName, String serviceId, int port, Server grpcServer) {
            this.serviceName = serviceName;
            this.serviceId = serviceId;
            this.port = port;
            this.grpcServer = grpcServer;
        }
        
        public String getServiceName() { return serviceName; }
        public String getServiceId() { return serviceId; }
        public int getPort() { return port; }
        public Server getGrpcServer() { return grpcServer; }
    }
}