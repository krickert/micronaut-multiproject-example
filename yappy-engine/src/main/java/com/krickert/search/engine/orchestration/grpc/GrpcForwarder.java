// GrpcForwarder.java
package com.krickert.search.engine.orchestration.grpc;

import com.krickert.search.engine.exception.StepExecutionException;
import com.krickert.search.model.PipeStream;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Singleton
@Slf4j
@Getter
public class GrpcForwarder {

    // For demonstration we create a single stub.
    // In practice, you may want to select the stub based on route.getDestination().
    private final ManagedChannel channel;
    private final boolean usePlaintext;

    @Inject
    public GrpcForwarder(@Value("${grpc.client.plaintext:true}") boolean usePlaintext,
                         ) {
        this.usePlaintext = usePlaintext;
        log.info("Initializing GrpcForwarder with plaintext: {}", usePlaintext);

        // Create a channel to the destination gRPC service (address could be externalized to config)
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress("localhost", 50051);

        if (usePlaintext) {
            builder.usePlaintext();
        }

        this.channel = builder.build();
        //TODO: use the micronaut managed channel factory here instead
        //this.stub = PipelineServiceGrpc.newBlockingStub(channel);
    }

    public void forwardToGrpc(PipeStream pipe) {
        // In a real-world scenario, use route.getDestination() to choose the correct stub.
        // Here we simply call the forward method and ignore the response.
        log.debug("Forwarding to gRPC service: {}", );
        //noinspection ResultOfMethodCallIgnored
        stub.forward(pipe);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down gRPC channel");
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Error shutting down gRPC channel", e);
                Thread.currentThread().interrupt();
            } finally {
                if (!channel.isTerminated()) {
                    channel.shutdownNow();
                }
            }
        }
    }

    /**
     * Retrieves or creates a ManagedChannel for a remote gRPC service using DiscoveryClient.
     * Handles caching and potential discovery failures. This method blocks for discovery.
     *
     * @param consulAppName The Consul application name of the service.
     * @return A ManagedChannel instance ready for use.
     * @throws StepExecutionException if discovery fails, no instances are found, or channel cannot be created.
     */
    private ManagedChannel getManagedChannel(String consulAppName) throws StepExecutionException {
        // Compute if absent ensures thread-safe creation and retrieval
        return managedChannelCache.computeIfAbsent(consulAppName, appName -> {
            log.info("Cache miss. Attempting discovery and channel creation for service: '{}'", appName);
            try {
                // Use DiscoveryClient reactively and block for the result with timeout
                Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(appName);
                List<ServiceInstance> instances = Mono.from(instancesPublisher)
                        .block(this.discoveryClientTimeout); // Block until discovery completes or times out

                if (instances == null || instances.isEmpty()) {
                    log.error("No instances found via discovery for service: '{}' within {} timeout.", appName, this.discoveryClientTimeout);
                    // Throw specific exception indicating service not found
                    throw new StepExecutionException("Service not found via discovery: " + appName, true); // Retryable = true
                }

                // Basic Strategy: Use the first available instance.
                // TODO: Implement a load balancing strategy (e.g., round-robin, random) for production.
                ServiceInstance instance = instances.get(0);
                String host = instance.getHost();
                int port = instance.getPort();
                // Use service ID from discovery if available, otherwise use appName
                String instanceId = instance.getInstanceId().orElse(appName + ":N/A");
                log.info("Selected instance for service '{}': ID='{}' at {}:{}", appName, instanceId, host, port);

                // Build the gRPC channel
                ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
                if (usePlaintext) {
                    builder.usePlaintext();
                    log.debug("Using plaintext connection for service '{}'", appName);
                } else {
                    // TODO: Configure TLS/SSL if usePlaintext is false (e.g., using default system certs or providing custom ones)
                    log.debug("Using secure connection (TLS/SSL) for service '{}'", appName);
                    // Example: builder.useTransportSecurity(); // Uses system default CAs
                }
                // TODO: Add other common channel options as needed:
                // builder.keepAliveTime(1, TimeUnit.MINUTES);
                // builder.intercept(...); // For tracing, metrics, auth etc.
                // builder.defaultLoadBalancingPolicy("round_robin"); // If needed and not handled by discovery

                log.info("Successfully created new ManagedChannel for service: '{}'", appName);
                return builder.build();

            } catch (IllegalStateException e) {
                // Handle specific exceptions from Mono.block (e.g., timeout)
                log.error("Discovery for service '{}' failed (possibly timed out or empty publisher): {}", appName, e.getMessage());
                // Remove potentially stale cache entry on timeout to force rediscovery attempt next time
                managedChannelCache.remove(appName);
                // Check if the message indicates a timeout specifically
                if (e.getMessage() != null && (e.getMessage().contains("Timeout on blocking read") || e.getMessage().contains("Did not observe any item or terminal signal"))) {
                    throw new StepExecutionException("Timeout waiting for service discovery for " + appName, e, true);
                }
                throw new StepExecutionException("Service discovery for " + appName + " yielded no instances or failed.", e, true);
            } catch (StepExecutionException e) {
                // Re-throw exceptions specifically thrown above (like service not found)
                throw e;
            } catch (RuntimeException e) {
                // Catch other potential runtime exceptions during discovery or channel build
                log.error("Failed to discover instances or create ManagedChannel for service '{}': {}", appName, e.getMessage(), e);
                // Remove potentially bad entry from cache if creation failed mid-way
                managedChannelCache.remove(appName);
                throw new StepExecutionException("Failed to establish channel for service " + appName + ": " + e.getMessage(), e, true);
            }
        });
    }
}
