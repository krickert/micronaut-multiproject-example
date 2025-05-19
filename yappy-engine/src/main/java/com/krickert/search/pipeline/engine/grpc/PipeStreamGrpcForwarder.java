// GrpcForwarder.java
package com.krickert.search.pipeline.engine.grpc;

import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.exception.GrpcEngineException;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Maps.newConcurrentMap;

@Singleton
@Getter
public class PipeStreamGrpcForwarder {
    private final static Logger log = LoggerFactory.getLogger(PipeStreamGrpcForwarder.class);

    // For demonstration we create a single stub.
    // In practice, you may want to select the stub based on route.getDestination().
    private final boolean usePlaintext;
    private final Map<String, ManagedChannel> managedChannelCache = newConcurrentMap();

    private final DiscoveryClient discoveryClient;
    private final Duration discoveryClientTimeout;


    @Inject
    public PipeStreamGrpcForwarder(@Value("${grpc.client.plaintext:true}") boolean usePlaintext,
        DiscoveryClient discoveryClient,
        @Value("${micronaut.discovery.client.timeout:10s}") Duration discoveryClientTimeout) {

        this.usePlaintext = usePlaintext;
        this.discoveryClient = discoveryClient;
        this.discoveryClientTimeout = discoveryClientTimeout;
    }

    public void forwardToGrpc(PipeStream.Builder pipeBuilder, RouteData route) {
        // In a real-world scenario, use route.getDestination() to choose the correct stub.
        // Here we simply call the forward method and ignore the response.
        log.debug("Forwarding to gRPC service: {}", route.destination());
        pipeBuilder.setCurrentPipelineName(route.targetPipeline());
        pipeBuilder.setTargetStepName(route.nextTargetStep());
        pipeBuilder.setStreamId(route.streamId());
        PipeStream pipe = pipeBuilder.build();
        ManagedChannel channel = getManagedChannel(route.destination());
        PipeStreamEngineGrpc.PipeStreamEngineBlockingStub stub = PipeStreamEngineGrpc.newBlockingStub(channel);
        stub.processAsync(pipe);
    }

    @Builder
    public record RouteData(
            String targetPipeline,
            String nextTargetStep,
            String destination,
            String streamId,
            @Value("${grpc.client.plaintext:true}") boolean usePlainText) {

    }
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down gRPC channels. Number of channels: {}", managedChannelCache.size());
        managedChannelCache.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
                log.info("Channel {} shut down.", channel.toString());
            } catch (InterruptedException e) {
                log.warn("Interrupted while shutting down channel {}. Forcing shutdown.", channel.toString());
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        managedChannelCache.clear();
        log.info("All gRPC channels processed for shutdown.");
    }

    private ManagedChannel getManagedChannel(String consulAppName) throws GrpcEngineException {
        return managedChannelCache.computeIfAbsent(consulAppName, appName -> {
            log.info("Cache miss. Attempting discovery and channel creation for service: '{}'", appName);
            try {
                Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(appName);
                List<ServiceInstance> instances = Mono.from(instancesPublisher)
                        .block(this.discoveryClientTimeout);

                if (instances == null || instances.isEmpty()) {
                    log.error("No instances found via discovery for service: '{}' within {} timeout.", appName, this.discoveryClientTimeout);
                    throw new GrpcEngineException("Service not found via discovery: " + appName);
                }

                ServiceInstance instance = instances.get(0); // Basic: Use first instance
                String host = instance.getHost();
                int port = instance.getPort();
                String instanceId = instance.getInstanceId().orElse(appName + ":N/A");
                log.info("Selected instance for service '{}': ID='{}' at {}:{}", appName, instanceId, host, port);

                ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
                if (usePlaintext) {
                    builder.usePlaintext();
                    log.debug("Using plaintext connection for service '{}'", appName);
                } else {
                    log.debug("Using secure connection (TLS/SSL) for service '{}'", appName);
                    // Example: builder.useTransportSecurity();
                }
                // Consider adding other channel options like keepAliveTime, interceptors etc.
                // builder.keepAliveTime(1, java.util.concurrent.TimeUnit.MINUTES);

                log.info("Successfully created new ManagedChannel for service: '{}'", appName);
                return builder.build();

            } catch (IllegalStateException e) {
                log.error("Discovery for service '{}' failed (possibly timed out or empty publisher): {}", appName, e.getMessage());
                managedChannelCache.remove(appName); // Evict on this type of error
                if (e.getMessage() != null && (e.getMessage().contains("Timeout on blocking read") || e.getMessage().contains("Did not observe any item or terminal signal"))) {
                    throw new GrpcEngineException("Timeout waiting for service discovery for " + appName, e);
                }
                throw new GrpcEngineException("Service discovery for " + appName + " yielded no instances or failed.", e);
            } catch (GrpcEngineException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Failed to discover instances or create ManagedChannel for service '{}': {}", appName, e.getMessage(), e);
                managedChannelCache.remove(appName); // Evict on this type of error
                throw new GrpcEngineException("Failed to establish channel for service " + appName + ": " + e.getMessage(), e);
            }
        });
    }

}
