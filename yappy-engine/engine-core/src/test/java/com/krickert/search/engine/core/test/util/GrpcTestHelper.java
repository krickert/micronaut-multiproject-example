package com.krickert.search.engine.core.test.util;

import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for testing gRPC services.
 * Provides utilities for health checks, service calls, and channel management.
 */
public class GrpcTestHelper {
    
    private static final Logger LOG = LoggerFactory.getLogger(GrpcTestHelper.class);
    
    private final String host;
    private final int port;
    private final ManagedChannel channel;
    
    public GrpcTestHelper(String host, int port) {
        this.host = host;
        this.port = port;
        this.channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
    }
    
    /**
     * Checks if the gRPC service is healthy
     */
    public boolean isHealthy() {
        return isHealthy("");
    }
    
    /**
     * Checks if a specific gRPC service is healthy
     */
    public boolean isHealthy(String serviceName) {
        try {
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
            HealthCheckRequest request = HealthCheckRequest.newBuilder()
                    .setService(serviceName)
                    .build();
            
            HealthCheckResponse response = healthStub.check(request);
            boolean isServing = response.getStatus() == HealthCheckResponse.ServingStatus.SERVING;
            
            LOG.debug("Health check for {}:{} (service: {}): {}", 
                    host, port, serviceName.isEmpty() ? "default" : serviceName, 
                    isServing ? "SERVING" : response.getStatus());
            
            return isServing;
        } catch (StatusRuntimeException e) {
            LOG.debug("Health check failed for {}:{}: {}", host, port, e.getMessage());
            return false;
        }
    }
    
    /**
     * Waits for the service to become healthy
     */
    public boolean waitForHealthy(long timeout, TimeUnit unit) {
        LOG.info("Waiting for gRPC service at {}:{} to become healthy...", host, port);
        
        long startTime = System.currentTimeMillis();
        long timeoutMillis = unit.toMillis(timeout);
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (isHealthy()) {
                LOG.info("gRPC service at {}:{} is healthy", host, port);
                return true;
            }
            
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        LOG.error("Timeout waiting for gRPC service at {}:{} to become healthy", host, port);
        return false;
    }
    
    /**
     * Creates a PipeStepProcessor client
     */
    public PipeStepProcessorGrpc.PipeStepProcessorBlockingStub createProcessorClient() {
        return PipeStepProcessorGrpc.newBlockingStub(channel);
    }
    
    /**
     * Processes a single PipeStream through a processor service
     */
    public ProcessResponse processSingle(ProcessRequest input) {
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = createProcessorClient();
            return stub.processData(input);
        } catch (StatusRuntimeException e) {
            LOG.error("Failed to process request: {}", e.getMessage());
            throw new RuntimeException("Processing failed", e);
        }
    }
    
    /**
     * Processes multiple requests sequentially
     */
    public List<ProcessResponse> processBatch(List<ProcessRequest> requests) {
        List<ProcessResponse> responses = new ArrayList<>();
        for (ProcessRequest request : requests) {
            responses.add(processSingle(request));
        }
        return responses;
    }
    
    /**
     * Gets the managed channel for custom operations
     */
    public ManagedChannel getChannel() {
        return channel;
    }
    
    /**
     * Closes the gRPC channel
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
     * Creates a helper for a module using test resource properties
     */
    public static GrpcTestHelper forModule(String moduleName, TestResourcesManager resourcesManager) {
        TestResourcesManager.ContainerInfo info = resourcesManager.getContainerInfo(moduleName);
        return new GrpcTestHelper(info.getHost(), info.getPort());
    }
    
    /**
     * Utility to test health check
     */
    public boolean testHealth() {
        try {
            return isHealthy();
        } catch (Exception e) {
            LOG.error("Health test failed", e);
            return false;
        }
    }
}