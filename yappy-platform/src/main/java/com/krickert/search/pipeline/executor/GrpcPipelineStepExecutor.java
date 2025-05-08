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
import lombok.Getter; // For StepExecutionException
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono; // Using Reactor for DiscoveryClient

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
// Guava ListenableFuture integration (often included with grpc-stub)
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors; // For direct execution of callback

/**
 * Handles the execution of gRPC calls to forward PipeStreams to downstream services.
 * It uses service discovery (Consul via DiscoveryClient), manages gRPC channels,
 * and performs asynchronous calls to the target service's PipeStreamEngine.processAsync method.
 * Implements best-effort async handoff.
 */
@Singleton
@Slf4j
public class GrpcPipelineStepExecutor {

    private final DiscoveryClient discoveryClient;
    private final boolean usePlaintext;
    private final Duration discoveryClientTimeout;
    private final int grpcCallDeadlineSeconds;
    private final Map<String, ManagedChannel> managedChannelCache = new ConcurrentHashMap<>();

    // Executor for handling the ListenableFuture callback.
    // Using MoreExecutors.directExecutor() runs the callback on the gRPC thread.
    // Consider a cached thread pool if callbacks might do more work or block.
    private final Executor callbackExecutor = MoreExecutors.directExecutor();
    // private final ExecutorService callbackExecutorService = Executors.newCachedThreadPool(); // Alternative

    @Inject
    public GrpcPipelineStepExecutor(
            DiscoveryClient discoveryClient,
            @Value("${grpc.client.plaintext:true}") boolean usePlaintext,
            @Value("${micronaut.discovery.client.timeout:10s}") Duration discoveryClientTimeout,
            @Value("${grpc.client.call.deadline.seconds:60}") int grpcCallDeadlineSeconds) {
        this.discoveryClient = discoveryClient;
        this.usePlaintext = usePlaintext;
        this.discoveryClientTimeout = discoveryClientTimeout;
        this.grpcCallDeadlineSeconds = grpcCallDeadlineSeconds;
        log.info("Initializing GrpcPipelineStepExecutor: Plaintext={}, DiscoveryTimeout={}, CallDeadline={}s",
                usePlaintext, discoveryClientTimeout, grpcCallDeadlineSeconds);
    }

    /**
     * Asynchronously forwards a PipeStream to the specified target service via gRPC
     * by calling its PipeStreamEngine.processAsync method.
     *
     * @param targetConsulAppName The Consul application name of the target service (obtained from the next step's serviceImplementation).
     * @param streamToForward     The prepared PipeStream (with incremented hop, correct current_pipestep_id set, etc.).
     * @return A CompletableFuture<Void> that:
     * - Completes normally (with null) if the gRPC call is successfully initiated and the target service acknowledges receipt by returning Empty.
     * - Completes exceptionally if the handoff fails at any stage (discovery, channel creation, gRPC call initiation, gRPC call failure, timeout waiting for Empty).
     */
    public CompletableFuture<Void> forwardPipeStreamAsync(String targetConsulAppName, PipeStream streamToForward) {
        String streamId = streamToForward.getStreamId();
        // Use Optional for safer access, provide default if unset (though sender should always set it)
        String targetStepId = streamToForward.hasCurrentPipestepId() ? streamToForward.getCurrentPipestepId() : "[TARGET_STEP_ID_MISSING]";

        log.debug("Stream [{}]: Attempting async gRPC forward to service '{}' (target logical step: '{}')",
                streamId, targetConsulAppName, targetStepId);

        CompletableFuture<Void> operationFuture = new CompletableFuture<>();

        try {
            // Step 1: Get or create the ManagedChannel (includes discovery)
            ManagedChannel channel = getManagedChannel(targetConsulAppName); // Can throw StepExecutionException

            // Step 2: Create an ASYNCHRONOUS stub for the PipeStreamEngine service
            PipeStreamEngineGrpc.PipeStreamEngineFutureStub asyncStub =
                    PipeStreamEngineGrpc.newFutureStub(channel)
                            .withDeadlineAfter(grpcCallDeadlineSeconds, TimeUnit.SECONDS); // Apply call deadline

            // Step 3: Initiate the asynchronous gRPC call
            ListenableFuture<Empty> grpcCallFuture = asyncStub.processAsync(streamToForward);

            // Step 4: Bridge Guava ListenableFuture to Java CompletableFuture
            Futures.addCallback(grpcCallFuture, new com.google.common.util.concurrent.FutureCallback<Empty>() {
                @Override
                public void onSuccess(@javax.annotation.Nullable Empty result) {
                    // Handoff successful: Target service acknowledged receipt.
                    log.debug("Stream [{}]: Successfully received Empty ack from service '{}' for step '{}'",
                            streamId, targetConsulAppName, targetStepId);
                    operationFuture.complete(null); // Signal successful handoff
                }

                @Override
                public void onFailure(@javax.annotation.Nonnull Throwable t) {
                    // The gRPC call failed *after* successful dispatch attempt
                    log.error("Stream [{}]: Async gRPC call failed after dispatch to service '{}' for step '{}'. Reason: {}",
                            streamId, targetConsulAppName, targetStepId, t.getMessage());
                    // Complete exceptionally for the caller (PipelineRouter will log this)
                    operationFuture.completeExceptionally(
                            new StepExecutionException("gRPC call failed post-dispatch to " + targetConsulAppName + " for step " + targetStepId, t, isRetryable(t))
                    );
                }
            }, callbackExecutor); // Execute callback using the chosen executor

        } catch (StepExecutionException e) {
            // Catch specific errors from getManagedChannel (discovery/channel setup)
            log.error("Stream [{}]: Failed to obtain gRPC channel for service '{}'. Reason: {}",
                    streamId, targetConsulAppName, e.getMessage());
            operationFuture.completeExceptionally(e); // Complete exceptionally immediately

        } catch (Exception e) {
            // Catch any other unexpected synchronous errors during setup before the call
            log.error("Stream [{}]: Unexpected synchronous error preparing async forward to service '{}': {}",
                    streamId, targetConsulAppName, e.getMessage(), e);
            operationFuture.completeExceptionally(
                    new StepExecutionException("Unexpected error preparing forward to " + targetConsulAppName, e, false)
            );
        }

        return operationFuture;
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
                // TODO: Implement a load balancing strategy (e.g., round-robin) for production.
                ServiceInstance instance = instances.get(0);
                String host = instance.getHost();
                int port = instance.getPort();
                log.info("Selected instance for service '{}': ID='{}' at {}:{}", appName, instance.getInstanceId().orElse("N/A"), host, port);

                // Build the gRPC channel
                ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
                if (usePlaintext) {
                    builder.usePlaintext();
                    log.debug("Using plaintext connection for service '{}'", appName);
                } else {
                    // TODO: Configure TLS/SSL if usePlaintext is false
                    log.debug("Using secure connection for service '{}'", appName);
                    // Example (requires certs): builder.useTransportSecurity()...
                }
                // TODO: Add other common channel options: keepAlive, interceptors, load balancing policy (if not handled by discovery client directly)
                // builder.keepAliveTime(1, TimeUnit.MINUTES);

                log.info("Successfully created new ManagedChannel for service: '{}'", appName);
                return builder.build();

            } catch (IllegalStateException e) {
                // Handle specific exceptions from Mono.block (e.g., timeout)
                 log.error("Discovery for service '{}' failed (possibly timed out or empty publisher): {}", appName, e.getMessage());
                 // Remove potentially stale cache entry on timeout to force rediscovery
                 managedChannelCache.remove(appName); // Remove on timeout
                 if (e.getMessage() != null && e.getMessage().contains("Timeout on blocking read")) {
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

    /**
     * Determines if a gRPC failure (StatusRuntimeException) is potentially retryable based on its status code.
     * Other exception types are considered non-retryable by this executor.
     *
     * @param t The throwable, expected to be StatusRuntimeException for gRPC errors.
     * @return true if the status code suggests a retry might succeed.
     */
    private boolean isRetryable(Throwable t) {
        if (t instanceof StatusRuntimeException e) {
            return switch (e.getStatus().getCode()) {
                // Common transient gRPC errors
                case DEADLINE_EXCEEDED, UNAVAILABLE, INTERNAL, UNKNOWN, RESOURCE_EXHAUSTED -> {
                    log.warn("Encountered potentially retryable gRPC status: {}", e.getStatus());
                    yield true;
                }
                // Usually non-retryable application/config errors
                default -> {
                    log.warn("Encountered non-retryable gRPC status: {}", e.getStatus());
                    yield false;
                }
            };
        }
        // Non-gRPC exceptions are not considered retryable by this component
        return false;
    }

    /**
     * Gracefully shuts down all cached ManagedChannels and the callback executor.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down GrpcPipelineStepExecutor and closing {} cached gRPC channels.", managedChannelCache.size());
        // Use a dedicated executor for shutdown tasks
        int poolSize = Math.min(managedChannelCache.size(), Runtime.getRuntime().availableProcessors());
        ExecutorService shutdownExecutor = Executors.newFixedThreadPool(poolSize > 0 ? poolSize : 1);

        managedChannelCache.forEach((serviceName, channel) -> {
            shutdownExecutor.submit(() -> {
                if (channel.isShutdown() || channel.isTerminated()) {
                    log.debug("Channel for service '{}' already shut down.", serviceName);
                    return;
                }
                log.info("Shutting down channel for service: '{}'...", serviceName);
                try {
                    channel.shutdown();
                    if (!channel.awaitTermination(10, TimeUnit.SECONDS)) { // Wait longer
                        log.warn("Channel for '{}' did not terminate gracefully after 10 seconds, forcing shutdown.", serviceName);
                        channel.shutdownNow();
                         if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                             log.error("Channel for '{}' failed to terminate even after shutdownNow.", serviceName);
                         }
                    } else {
                         log.info("Channel for '{}' shut down gracefully.", serviceName);
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while shutting down channel for '{}'. Forcing shutdown.", serviceName, e);
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                } catch(Exception e) {
                     log.error("Unexpected error shutting down channel for service '{}'", serviceName, e);
                     if (!channel.isShutdown()) channel.shutdownNow();
                }
            });
        });

        // Initiate shutdown of the executor and wait
        shutdownExecutor.shutdown();
        try {
             if (!shutdownExecutor.awaitTermination(30, TimeUnit.SECONDS)) { // Wait longer overall
                 log.warn("gRPC channel shutdown tasks did not complete within 30 seconds.");
                 shutdownExecutor.shutdownNow();
             } else {
                  log.info("All gRPC channel shutdown tasks completed.");
             }
        } catch (InterruptedException e) {
             shutdownExecutor.shutdownNow();
             Thread.currentThread().interrupt();
        }

        managedChannelCache.clear();

        // Shutdown the callback executor if it's managed here and is an ExecutorService
        if (callbackExecutor instanceof ExecutorService managedExecutor && !managedExecutor.isShutdown()) {
             log.info("Shutting down callback executor service...");
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