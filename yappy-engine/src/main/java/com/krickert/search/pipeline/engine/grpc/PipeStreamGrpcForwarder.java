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
        stub.processPipeAsync(pipe);
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

    public ManagedChannel getManagedChannel(String consulAppName) throws GrpcEngineException {
        return managedChannelCache.computeIfAbsent(consulAppName, appName -> {
            log.info("Cache miss. Attempting discovery and channel creation for service: '{}'", appName);
            try {
                List<ServiceInstance> instances = null;
                int maxRetries = 3; // Number of retry attempts
                Duration retryAttemptTimeout = Duration.ofSeconds(5); // Timeout for each individual discovery attempt
                long retryDelayMs = 1000; // Delay between retries

                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    log.info("Attempting discovery for service: '{}', attempt {}/{}", appName, attempt, maxRetries);
                    Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(appName);
                    try {
                        instances = Mono.from(instancesPublisher)
                                .block(retryAttemptTimeout); // Use a shorter timeout for each attempt
                    } catch (IllegalStateException e) {
                        // This can happen if the publisher is empty or times out
                        log.warn("Discovery attempt {} for service '{}' failed with IllegalStateException (possibly timeout or empty publisher): {}", attempt, appName, e.getMessage());
                    }

                    if (instances != null && !instances.isEmpty()) {
                        log.info("Successfully discovered service '{}' on attempt {}/{}. Instances found: {}", appName, attempt, maxRetries, instances.size());
                        break; // Success
                    }

                    log.warn("Discovery attempt {}/{} for service '{}' failed or returned no instances.", attempt, maxRetries, appName);
                    if (attempt < maxRetries) {
                        try {
                            log.info("Waiting {}ms before next discovery attempt for service '{}'.", retryDelayMs, appName);
                            TimeUnit.MILLISECONDS.sleep(retryDelayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Interrupted during discovery retry delay for service '{}'.", appName);
                            throw new GrpcEngineException("Interrupted during discovery retry for " + appName, e);
                        }
                    }
                }

                if (instances == null || instances.isEmpty()) {
                    log.error("No instances found via discovery for service: '{}' after {} retries and within overall timeout of {}.",
                            appName, maxRetries, this.discoveryClientTimeout); // this.discoveryClientTimeout is still the overall conceptual limit
                    throw new GrpcEngineException("Service not found via discovery after " + maxRetries + " retries: " + appName);
                }

                ServiceInstance instance = instances.getFirst(); // Basic: Use first instance
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
                }
                log.info("Successfully created new ManagedChannel for service: '{}'", appName);
                return builder.build();

            } catch (IllegalStateException e) {
                log.error("Discovery for service '{}' failed (possibly timed out or empty publisher during retries): {}", appName, e.getMessage());
                // managedChannelCache.remove(appName); // Consider eviction strategy
                if (e.getMessage() != null && (e.getMessage().contains("Timeout on blocking read") || e.getMessage().contains("Did not observe any item or terminal signal"))) {
                    throw new GrpcEngineException("Timeout waiting for service discovery for " + appName, e);
                }
                throw new GrpcEngineException("Service discovery for " + appName + " yielded no instances or failed after retries.", e);
            } catch (GrpcEngineException e) {
                // managedChannelCache.remove(appName); // Consider eviction strategy
                throw e;
            } catch (RuntimeException e) {
                log.error("Failed to discover instances or create ManagedChannel for service '{}': {}", appName, e.getMessage(), e);
                // managedChannelCache.remove(appName); // Consider eviction strategy
                throw new GrpcEngineException("Failed to establish channel for service " + appName + ": " + e.getMessage(), e);
            }
        });
    }

}
