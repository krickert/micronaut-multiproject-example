// GrpcForwarder.java
package com.krickert.search.engine.orchestration.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.PipeStreamEngineGrpc; // Assuming this is your generated gRPC service
//import com.krickert.search.engine.PipeStreamProtos; // Assuming PipeStream is a protobuf message
// ^^^ Or your actual PipeStream protobuf generated class if it's different
import com.krickert.search.engine.exception.StepExecutionException;
// import com.krickert.search.model.PipeStream; // This seems to be a domain model, you'll need to convert it to the protobuf type
import com.krickert.search.model.PipeStream;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap; // Prefer this more specific import
// import java.util.concurrent.TimeUnit; // Not directly used, but good for other channel options

import static com.google.common.collect.Maps.newConcurrentMap;


@Singleton
@Slf4j
@Getter
public class GrpcForwarder {
    private final boolean usePlaintext;
    private final Map<String, ManagedChannel> managedChannelCache = newConcurrentMap();

    private final DiscoveryClient discoveryClient;
    private final Duration discoveryClientTimeout;

    @Inject
    public GrpcForwarder(@Value("${grpc.client.plaintext:true}") boolean usePlaintext,
                         DiscoveryClient discoveryClient,
                         @Value("${micronaut.discovery.client.timeout:10s}") Duration discoveryClientTimeout) {
        this.usePlaintext = usePlaintext;
        this.discoveryClient = discoveryClient;
        this.discoveryClientTimeout = discoveryClientTimeout;
        log.info("Initializing GrpcForwarder with plaintext: {}", usePlaintext);
        // The ManagedChannelBuilder specific to "localhost:50051" was not used and
        // is correctly handled within getManagedChannel. So, it's removed from here.
    }

    /**
     * Forwards the PipeStream to the target gRPC service asynchronously ("fire and forget").
     * The method returns immediately and does not wait for the gRPC call to complete or for a response.
     * Errors during the gRPC call itself will be logged via the StreamObserver.
     * Note: The initial discovery of the service (if not cached) is blocking.
     *
     * @param pipeStreamProto The PipeStream protobuf message to send.
     * @param pipeStepName  The name of the target pipestream service (used for discovery and channel caching).
     */
    public void forwardToGrpc(PipeStream pipeStreamProto, String pipeStepName) {
        // Your original code used a domain model `com.krickert.search.model.PipeStream pipe`.
        // For gRPC, you need to send the protobuf generated message.
        // I'm assuming you have a conversion step before this method is called,
        // or you'd convert it here. For this example, I've changed the parameter type
        // to `PipeStreamProtos.PipeStream pipeStreamProto` (adjust if your proto name is different).

        log.debug("Forwarding PipeStream to gRPC service '{}' for pipeline: {}", pipeStepName, pipeStreamProto.getCurrentPipelineName());
        try {
            ManagedChannel channel = getManagedChannel(pipeStepName); // This can block on cache miss

            // Use the asynchronous stub
            PipeStreamEngineGrpc.PipeStreamEngineStub asyncStub = PipeStreamEngineGrpc.newStub(channel);

            // Make the asynchronous call.
            // For "fire and forget", we provide a StreamObserver that logs errors
            // but doesn't do much else. The calling thread of forwardToGrpc will not block here.
            asyncStub.processAsync(pipeStreamProto, new StreamObserver<Empty>() {
                @Override
                public void onNext(Empty value) {
                    // In a unary call, onNext is called once with the response.
                    // For "fire and forget", we don't typically expect a meaningful response other than Empty.
                    // If the server indeed responds with Empty, this will be called.
                    log.trace("gRPC call for pipeline '{}' acknowledged by service '{}'.", pipeStreamProto.getCurrentPipelineName(), pipeStepName);
                }

                @Override
                public void onError(Throwable t) {
                    // The gRPC call failed.
                    log.error("Error forwarding PipeStream for pipeline '{}' to service '{}': {}",
                            pipeStreamProto.getCurrentPipelineName(), pipeStepName, t.getMessage(), t);
                    // Depending on requirements, you might want to:
                    // - Implement retry logic (though gRPC itself might have some retry capabilities configured on the channel)
                    // - Send to a dead-letter queue
                    // - Increment error metrics
                }

                @Override
                public void onCompleted() {
                    // The gRPC call completed successfully from the server's perspective.
                    log.debug("gRPC call for pipeline '{}' completed by service '{}'.", pipeStreamProto.getCurrentPipelineName(), pipeStepName);
                }
            });
            log.debug("Asynchronous gRPC call initiated for pipeline '{}' to service '{}'.", pipeStreamProto.getCurrentPipelineName(), pipeStepName);

        } catch (StepExecutionException e) {
            // This exception comes from getManagedChannel (e.g., discovery failure)
            log.error("Failed to get managed channel for service '{}' for pipeline '{}': {}",
                    pipeStepName, pipeStreamProto.getCurrentPipelineName(), e.getMessage(), e);
            // Handle channel acquisition failure (e.g., re-throw, specific metrics)
            // This is a synchronous failure before the async gRPC call could be made.
            throw e; // Or handle as appropriate for your application
        } catch (Exception e) {
            // Catch any other unexpected synchronous errors during setup
            log.error("Unexpected error preparing to forward PipeStream for pipeline '{}' to service '{}': {}",
                    pipeStreamProto.getCurrentPipelineName(), pipeStepName, e.getMessage(), e);
            // This is also a synchronous failure.
            throw new RuntimeException("Failed to initiate gRPC call for " + pipeStepName, e); // Or handle as appropriate
        }
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

    private ManagedChannel getManagedChannel(String consulAppName) throws StepExecutionException {
        return managedChannelCache.computeIfAbsent(consulAppName, appName -> {
            log.info("Cache miss. Attempting discovery and channel creation for service: '{}'", appName);
            try {
                Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(appName);
                List<ServiceInstance> instances = Mono.from(instancesPublisher)
                        .block(this.discoveryClientTimeout);

                if (instances == null || instances.isEmpty()) {
                    log.error("No instances found via discovery for service: '{}' within {} timeout.", appName, this.discoveryClientTimeout);
                    throw new StepExecutionException("Service not found via discovery: " + appName, true);
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
                    throw new StepExecutionException("Timeout waiting for service discovery for " + appName, e, true);
                }
                throw new StepExecutionException("Service discovery for " + appName + " yielded no instances or failed.", e, true);
            } catch (StepExecutionException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Failed to discover instances or create ManagedChannel for service '{}': {}", appName, e.getMessage(), e);
                managedChannelCache.remove(appName); // Evict on this type of error
                throw new StepExecutionException("Failed to establish channel for service " + appName + ": " + e.getMessage(), e, true);
            }
        });
    }
}