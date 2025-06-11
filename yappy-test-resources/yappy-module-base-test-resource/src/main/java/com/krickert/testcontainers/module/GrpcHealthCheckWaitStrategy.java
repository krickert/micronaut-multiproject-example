package com.krickert.testcontainers.module;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import org.rnorth.ducttape.TimeoutException;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Wait strategy that checks gRPC health service.
 * Waits until the gRPC health check returns SERVING status.
 */
public class GrpcHealthCheckWaitStrategy extends AbstractWaitStrategy {
    
    private String serviceName = ""; // Empty string checks overall health
    private Duration timeout = Duration.ofSeconds(60);
    
    /**
     * Sets the service name to check. Empty string checks overall health.
     */
    public GrpcHealthCheckWaitStrategy forService(String serviceName) {
        this.serviceName = serviceName != null ? serviceName : "";
        return this;
    }
    
    /**
     * Sets the startup timeout
     */
    public GrpcHealthCheckWaitStrategy withStartupTimeout(Duration timeout) {
        this.timeout = timeout;
        this.startupTimeout = timeout;
        return this;
    }
    
    @Override
    protected void waitUntilReady() {
        final String host = waitStrategyTarget.getHost();
        final Integer port = waitStrategyTarget.getMappedPort(50051);
        
        if (port == null) {
            throw new IllegalStateException("gRPC port 50051 is not mapped");
        }
        
        final long timeoutMillis = timeout.toMillis();
        
        try {
            Unreliables.retryUntilSuccess((int) timeout.getSeconds(), TimeUnit.SECONDS, () -> {
                ManagedChannel channel = null;
                try {
                    channel = ManagedChannelBuilder
                            .forAddress(host, port)
                            .usePlaintext()
                            .build();
                    
                    HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
                    
                    // Set a deadline for the health check call
                    HealthCheckResponse response = healthStub
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .check(HealthCheckRequest.newBuilder()
                                    .setService(serviceName)
                                    .build());
                    
                    if (response.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
                        throw new RuntimeException("Health check returned: " + response.getStatus());
                    }
                    
                    return response;
                    
                } catch (StatusRuntimeException e) {
                    throw new RuntimeException("gRPC health check failed: " + e.getStatus(), e);
                } finally {
                    if (channel != null) {
                        channel.shutdown();
                        try {
                            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                                channel.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            channel.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
        } catch (TimeoutException e) {
            throw new RuntimeException(
                String.format("Timed out waiting for gRPC health check on %s:%d after %d seconds",
                    host, port, timeout.getSeconds()), e);
        }
    }
}