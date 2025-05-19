package com.krickert.search.engine.orchestration;

// Protobuf / gRPC Imports

import com.krickert.search.engine.PipeStreamEngineGrpc; // Generated gRPC class for the service
import com.krickert.search.model.PipeStream;           // Protobuf PipeStream model
import com.google.protobuf.Empty;                     // Protobuf Empty
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

// Micronaut Imports
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

// Lombok / Logging Imports
import lombok.Getter; // For StepExecutionException
import lombok.extern.slf4j.Slf4j;

// Reactive Imports
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono; // Using Reactor for DiscoveryClient

// Java / Concurrency Imports
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
import com.google.common.util.concurrent.FutureCallback; // Explicit import
import com.google.common.util.concurrent.MoreExecutors; // For direct execution of callback
import com.krickert.search.engine.exception.StepExecutionException;

/**
 * Handles the execution of gRPC calls to forward PipeStreams to downstream services
 * implementing the PipeStreamEngine interface.
 * Uses service discovery, manages gRPC channels, performs asynchronous calls
 * to processAsync, and implements best-effort async handoff.
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
    // Using MoreExecutors.directExecutor() runs the callback on the gRPC network thread.
    // This is efficient but callbacks MUST NOT block or perform heavy computation.
    // Consider a cached thread pool if callbacks might do more.
    private final Executor callbackExecutor = MoreExecutors.directExecutor();
    // private final ExecutorService callbackExecutorService = Executors.newCachedThreadPool(); // Alternative if needed

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
     * @param targetConsulAppName The Consul application name of the target service.
     * @param streamToForward     The prepared PipeStream (with incremented hop, correct current_pipestep_id set).
     * @return A CompletableFuture<Void> signalling success/failure of the handoff attempt.
     * Completes normally if the call is initiated and acknowledged with Empty.
     * Completes exceptionally if discovery, channel creation, or the gRPC call fails.
     */
    public CompletableFuture<Void> forwardPipeStreamAsync(String targetConsulAppName, PipeStream streamToForward) {
        String streamId = streamToForward.getStreamId();
        // Provide a default if pipestep_id is missing, although sender should always set it.
        String targetStepId = streamToForward.getCurrentPipelineName();

        log.debug("Stream [{}]: Attempting async gRPC forward to service '{}' (target logical step: '{}')",
                streamId, targetConsulAppName, targetStepId);

        CompletableFuture<Void> operationFuture = new CompletableFuture<>();

        try {
            // Step 1: Get or create the ManagedChannel (includes discovery, potentially blocking)
            ManagedChannel channel = getManagedChannel(targetConsulAppName); // Can throw StepExecutionException

            // Step 2: Create an ASYNCHRONOUS stub for the target PipeStreamEngine service
            PipeStreamEngineGrpc.PipeStreamEngineFutureStub asyncStub =
                    PipeStreamEngineGrpc.newFutureStub(channel)
                            .withDeadlineAfter(grpcCallDeadlineSeconds, TimeUnit.SECONDS); // Apply call deadline

            // Step 3: Initiate the asynchronous gRPC call to processAsync
            ListenableFuture<Empty> grpcCallFuture = asyncStub.processAsync(streamToForward);

            // Step 4: Bridge Guava ListenableFuture to Java CompletableFuture using a callback
            Futures.addCallback(grpcCallFuture, new FutureCallback<Empty>() {
                @Override
                public void onSuccess(@javax.annotation.Nullable Empty result) {
                    // Handoff successful: Target service acknowledged receipt by returning Empty.
                    log.debug("Stream [{}]: Successfully received Empty ack from service '{}' for step '{}'",
                            streamId, targetConsulAppName, targetStepId);
                    operationFuture.complete(null); // Signal successful handoff (Void)
                }

                @Override
                public void onFailure(@javax.annotation.Nonnull Throwable t) {
                    // The gRPC call failed *after* successful dispatch attempt
                    // (e.g., network error, deadline exceeded, server error status like UNIMPLEMENTED, UNAVAILABLE)
                    log.error("Stream [{}]: Async gRPC call failed after dispatch to service '{}' for step '{}'. Reason: {}",
                            streamId, targetConsulAppName, targetStepId, t.getMessage());
                    // Complete exceptionally for the caller (PipelineRouter will log this based on our agreement)
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

        return operationFuture; // Return the future representing the handoff attempt
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
                // Common transient gRPC errors that might resolve on retry
                case DEADLINE_EXCEEDED, UNAVAILABLE, INTERNAL, UNKNOWN, RESOURCE_EXHAUSTED -> {
                    log.warn("Encountered potentially retryable gRPC status: {}", e.getStatus());
                    yield true;
                }
                // Usually non-retryable application/config errors or definite failures
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
     * Called by Micronaut when the application context is closing.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down GrpcPipelineStepExecutor and closing {} cached gRPC channels.", managedChannelCache.size());
        // Use a dedicated executor for shutdown tasks to avoid blocking main shutdown thread
        // and allow some parallelization if many channels exist.
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
                    if (!channel.awaitTermination(10, TimeUnit.SECONDS)) { // Wait a reasonable time
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
                    channel.shutdownNow(); // Force shutdown if interrupted
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                } catch (Exception e) {
                    log.error("Unexpected error shutting down channel for service '{}'", serviceName, e);
                    // Attempt shutdownNow again just in case
                    if (!channel.isShutdown()) channel.shutdownNow();
                }
            });
        });

        // Initiate shutdown of the channel shutdown executor and wait for tasks to complete
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

        managedChannelCache.clear(); // Clear the cache after shutdown attempts

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
