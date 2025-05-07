package com.krickert.search.pipeline.executor;

import com.krickert.search.engine.PipeStreamEngineGrpc; // Generated gRPC class for the service
import com.krickert.search.model.PipeStream;           // Protobuf PipeStream model
import com.google.protobuf.Empty;                     // Protobuf Empty

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Futures;


@Singleton
@Slf4j
public class GrpcPipelineStepExecutor {

    private final DiscoveryClient discoveryClient;
    private final boolean usePlaintext;
    private final Duration discoveryClientTimeout;
    private final int grpcDeadlineSeconds;
    private final Map<String, ManagedChannel> managedChannelCache = new ConcurrentHashMap<>();
    // Optional: Define an Executor for the ListenableFuture callback
    // private final Executor callbackExecutor = MoreExecutors.directExecutor(); // Run callback directly
    private final Executor callbackExecutor = Executors.newCachedThreadPool(); // Or use a pool

    @Inject
    public GrpcPipelineStepExecutor(
            DiscoveryClient discoveryClient,
            @Value("${grpc.client.plaintext:true}") boolean usePlaintext,
            @Value("${micronaut.discovery.client.timeout:10s}") Duration discoveryClientTimeout, // Renamed property key
            @Value("${grpc.client.call.deadline.seconds:60}") int grpcDeadlineSeconds) {
        this.discoveryClient = discoveryClient;
        this.usePlaintext = usePlaintext;
        this.discoveryClientTimeout = discoveryClientTimeout;
        this.grpcDeadlineSeconds = grpcDeadlineSeconds;
        log.info("Initializing GrpcPipelineStepExecutor: Plaintext={}, DiscoveryTimeout={}, CallDeadline={}s",
                usePlaintext, discoveryClientTimeout, grpcDeadlineSeconds);
    }

    /**
     * Asynchronously forwards a PipeStream to the specified target service via gRPC
     * by calling its PipeStreamEngine.processAsync method.
     *
     * @param targetConsulAppName The Consul application name of the target service.
     * @param streamToForward     The prepared PipeStream to be sent.
     * @return A CompletableFuture<Void> that completes normally if the gRPC call is successfully
     * initiated and acknowledged (receives Empty response), or completes exceptionally
     * if the handoff fails (e.g., service not found, connection error, timeout waiting for ack).
     */
    public CompletableFuture<Void> forwardPipeStreamAsync(String targetConsulAppName, PipeStream streamToForward) {
        String streamId = streamToForward.getStreamId();
        String targetStepId = streamToForward.hasCurrentPipestepId() ? streamToForward.getCurrentPipestepId() : "[Not Set]";

        log.debug("Stream [{}]: Attempting async gRPC forward to service '{}' (target logical step: '{}')",
                streamId, targetConsulAppName, targetStepId);

        CompletableFuture<Void> operationFuture = new CompletableFuture<>();

        try {
            // Step 1: Get the gRPC Channel (potentially blocking for discovery)
            ManagedChannel channel = getManagedChannel(targetConsulAppName); // Can throw StepExecutionException

            // Step 2: Create an ASYNCHRONOUS stub with deadline
            PipeStreamEngineGrpc.PipeStreamEngineFutureStub asyncStub =
                    PipeStreamEngineGrpc.newFutureStub(channel)
                            .withDeadlineAfter(grpcDeadlineSeconds, TimeUnit.SECONDS);

            // Step 3: Make the asynchronous gRPC call
            ListenableFuture<Empty> grpcCallFuture = asyncStub.processAsync(streamToForward);

            // Step 4: Bridge Guava ListenableFuture to CompletableFuture
            Futures.addCallback(grpcCallFuture, new com.google.common.util.concurrent.FutureCallback<Empty>() {
                @Override
                public void onSuccess(@javax.annotation.Nullable Empty result) {
                    // The handoff was successful, the target service acknowledged receipt.
                    log.debug("Stream [{}]: Successfully received Empty ack from service '{}' for step '{}'",
                            streamId, targetConsulAppName, targetStepId);
                    operationFuture.complete(null); // Signal success (Void)
                }

                @Override
                public void onFailure(@javax.annotation.Nonnull Throwable t) {
                    // The gRPC call failed *after* initiation (e.g., network error, deadline exceeded, server error status)
                    log.error("Stream [{}]: Async gRPC call failed after dispatch to service '{}' for step '{}'. Reason: {}",
                            streamId, targetConsulAppName, targetStepId, t.getMessage());
                    // Complete exceptionally. The PipelineRouter (or caller) just logs this based on our agreement.
                    operationFuture.completeExceptionally(
                            new StepExecutionException("gRPC call failed post-dispatch to " + targetConsulAppName + " for step " + targetStepId, t, isRetryable(t))
                    );
                }
            }, callbackExecutor); // Use the specified executor for the callback

        } catch (StepExecutionException e) {
            // Catch specific errors from getManagedChannel (e.g., discovery failure)
            log.error("Stream [{}]: Failed to obtain gRPC channel for service '{}'. Reason: {}",
                    streamId, targetConsulAppName, e.getMessage());
            operationFuture.completeExceptionally(e); // Complete exceptionally immediately

        } catch (Exception e) {
            // Catch any other unexpected synchronous errors during setup
            log.error("Stream [{}]: Unexpected synchronous error preparing async forward to service '{}': {}",
                    streamId, targetConsulAppName, e.getMessage(), e);
            operationFuture.completeExceptionally(
                    new StepExecutionException("Unexpected error preparing forward to " + targetConsulAppName, e, false)
            );
        }

        return operationFuture;
    }

    // getManagedChannel, isRetryable, shutdown methods remain the same as previous version
    // ... (Include the code for these methods from the previous response) ...

    private ManagedChannel getManagedChannel(String consulAppName) throws StepExecutionException {
         return managedChannelCache.computeIfAbsent(consulAppName, appName -> {
            log.info("Cache miss. Attempting to create new ManagedChannel for service: '{}'", appName);
            try {
                Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(appName);
                List<ServiceInstance> instances = Mono.from(instancesPublisher).block(this.discoveryClientTimeout);

                if (instances == null || instances.isEmpty()) {
                    log.error("No instances found via discovery for service: '{}' within {} timeout.", appName, this.discoveryClientTimeout);
                    throw new StepExecutionException("Service not found via discovery: " + appName, true); // Retryable
                }

                // Basic: use first instance. Implement load balancing for production.
                ServiceInstance instance = instances.get(0);
                String host = instance.getHost();
                int port = instance.getPort();
                log.info("Selected instance for service '{}': {} at {}:{}", appName, instance.getId(), host, port);

                ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
                if (usePlaintext) {
                    builder.usePlaintext();
                }
                // Add other channel options: keepAlive, interceptors, etc.
                return builder.build();

            } catch (IllegalStateException e) {
                 log.error("Discovery for service '{}' failed (possibly timed out or empty publisher): {}", appName, e.getMessage());
                 if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
                    throw new StepExecutionException("Timeout waiting for service discovery for " + appName, e, true);
                 }
                 throw new StepExecutionException("Service discovery for " + appName + " yielded no instances or failed.", e, true);
            } catch (RuntimeException e) {
                // Catch other runtime exceptions (e.g., from discovery client internals)
                log.error("Failed to discover instances or create ManagedChannel for service '{}': {}", appName, e.getMessage(), e);
                throw new StepExecutionException("Failed to establish channel for service " + appName + ": " + e.getMessage(), e, true);
            }
        });
    }

     private boolean isRetryable(Throwable t) {
        if (t instanceof StatusRuntimeException e) {
            return switch (e.getStatus().getCode()) {
                // Potentially retryable gRPC status codes
                case DEADLINE_EXCEEDED, UNAVAILABLE, INTERNAL, UNKNOWN, RESOURCE_EXHAUSTED -> true;
                // Usually non-retryable codes
                default -> false;
            };
        }
        // Non-gRPC specific errors are generally not considered retryable by the executor itself
        return false;
    }

     @PreDestroy
    public void shutdown() {
        log.info("Shutting down GrpcPipelineStepExecutor and closing {} cached gRPC channels.", managedChannelCache.size());
        // Use an executor to parallelize shutdown slightly
        ExecutorService shutdownExecutor = Executors.newFixedThreadPool(Math.min(managedChannelCache.size(), 5));
        managedChannelCache.forEach((serviceName, channel) -> {
            shutdownExecutor.submit(() -> {
                log.info("Shutting down channel for service: '{}'", serviceName);
                try {
                    if (!channel.isShutdown()) {
                        channel.shutdown();
                        if (!channel.awaitTermination(10, TimeUnit.SECONDS)) { // Increased timeout
                            log.warn("Channel for '{}' did not terminate gracefully after 10 seconds, forcing shutdown.", serviceName);
                            channel.shutdownNow();
                        } else {
                             log.info("Channel for '{}' shut down gracefully.", serviceName);
                        }
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while shutting down channel for '{}'. Forcing shutdown.", serviceName, e);
                    if (!channel.isShutdown()) channel.shutdownNow(); // Ensure shutdownNow is called if not already
                    Thread.currentThread().interrupt();
                } catch(Exception e) {
                     log.error("Error shutting down channel for {}", serviceName, e);
                     if (!channel.isShutdown()) channel.shutdownNow();
                }
            });
        });
        shutdownExecutor.shutdown();
        try {
             if (!shutdownExecutor.awaitTermination(20, TimeUnit.SECONDS)) {
                 log.warn("Channel shutdown tasks did not complete within 20 seconds.");
                 shutdownExecutor.shutdownNow();
             }
        } catch (InterruptedException e) {
             shutdownExecutor.shutdownNow();
             Thread.currentThread().interrupt();
        }
        managedChannelCache.clear();
        // Shutdown the callback executor if it's managed here
        if (callbackExecutor instanceof ExecutorService managedExecutor && !managedExecutor.isShutdown()) {
             log.info("Shutting down callback executor service.");
             managedExecutor.shutdown();
             try {
                 if (!managedExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                     managedExecutor.shutdownNow();
                 }
             } catch (InterruptedException e) {
                 managedExecutor.shutdownNow();
                 Thread.currentThread().interrupt();
             }
         }
        log.info("GrpcPipelineStepExecutor shutdown complete.");
    }


}

