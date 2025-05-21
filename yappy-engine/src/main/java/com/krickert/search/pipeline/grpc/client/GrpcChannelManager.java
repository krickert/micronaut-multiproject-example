package com.krickert.search.pipeline.grpc.client; // Or a suitable common package

import com.krickert.search.pipeline.engine.exception.GrpcEngineException; // Assuming this is a general gRPC exception
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
public class GrpcChannelManager {
    private static final Logger log = LoggerFactory.getLogger(GrpcChannelManager.class);

    private final DiscoveryClient discoveryClient;
    private final boolean usePlaintext;
    private final Duration discoveryAttemptTimeout;
    private final int discoveryMaxRetries;
    private final long discoveryRetryDelayMs;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    // Configuration for "localhost first" (example)
    private final Map<String, Integer> localServicesPorts; // e.g., {"echo-module-local": 50052}

    @Inject
    public GrpcChannelManager(
            DiscoveryClient discoveryClient,
            @Value("${grpc.client.plaintext:true}") boolean usePlaintext,
            @Value("${grpc.client.discovery.attempt-timeout:5s}") Duration discoveryAttemptTimeout,
            @Value("${grpc.client.discovery.max-retries:3}") int discoveryMaxRetries,
            @Value("${grpc.client.discovery.retry-delay-ms:1000}") long discoveryRetryDelayMs,
            // Inject local service port configurations if you have them in application.yml
            // For a more dynamic approach, this could come from DynamicConfigurationManager
            @Value("#{${local.services.ports:{}}}") Map<String, Integer> localServicesPorts 
    ) {
        this.discoveryClient = discoveryClient;
        this.usePlaintext = usePlaintext;
        this.discoveryAttemptTimeout = discoveryAttemptTimeout;
        this.discoveryMaxRetries = discoveryMaxRetries;
        this.discoveryRetryDelayMs = discoveryRetryDelayMs;
        this.localServicesPorts = localServicesPorts != null ? localServicesPorts : Map.of();
        log.info("GrpcChannelManager initialized. Localhost first ports: {}", this.localServicesPorts);
    }

    public ManagedChannel getChannel(String serviceName) throws GrpcEngineException {
        return channelCache.computeIfAbsent(serviceName, this::createChannel);
    }

    private ManagedChannel createChannel(String serviceName) {
        log.info("Cache miss. Attempting channel creation for service: '{}'", serviceName);

        // 1. "Localhost first" attempt
        Optional<ManagedChannel> localChannel = tryLocalhostConnection(serviceName);
        if (localChannel.isPresent()) {
            log.info("Successfully connected to local service '{}'", serviceName);
            return localChannel.get();
        }
        log.info("Local service '{}' not found or connection failed. Proceeding with discovery.", serviceName);

        // 2. Discovery attempt (adapted from your robust PipeStreamGrpcForwarder logic)
        List<ServiceInstance> instances = null;
        for (int attempt = 1; attempt <= discoveryMaxRetries; attempt++) {
            log.info("Attempting discovery for service: '{}', attempt {}/{}", serviceName, attempt, discoveryMaxRetries);
            Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(serviceName);
            try {
                instances = Mono.from(instancesPublisher).block(discoveryAttemptTimeout);
            } catch (IllegalStateException e) {
                log.warn("Discovery attempt {} for service '{}' failed (possibly timeout or empty publisher): {}", attempt, serviceName, e.getMessage());
            }

            if (instances != null && !instances.isEmpty()) {
                log.info("Successfully discovered service '{}' on attempt {}/{}. Instances found: {}", serviceName, attempt, discoveryMaxRetries, instances.size());
                break; 
            }

            log.warn("Discovery attempt {}/{} for service '{}' failed or returned no instances.", attempt, discoveryMaxRetries, serviceName);
            if (attempt < discoveryMaxRetries) {
                try {
                    log.info("Waiting {}ms before next discovery attempt for service '{}'.", discoveryRetryDelayMs, serviceName);
                    TimeUnit.MILLISECONDS.sleep(discoveryRetryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during discovery retry delay for service '{}'.", serviceName);
                    throw new GrpcEngineException("Interrupted during discovery retry for " + serviceName, e);
                }
            }
        }

        if (instances == null || instances.isEmpty()) {
            log.error("No instances found via discovery for service: '{}' after {} retries.", serviceName, discoveryMaxRetries);
            throw new GrpcEngineException("Service not found via discovery after " + discoveryMaxRetries + " retries: " + serviceName);
        }

        ServiceInstance instance = instances.getFirst(); // Basic: Use first instance
        String host = instance.getHost();
        int port = instance.getPort();
        log.info("Selected discovered instance for service '{}' at {}:{}", serviceName, host, port);

        return buildManagedChannel(host, port, serviceName);
    }
    
    private Optional<ManagedChannel> tryLocalhostConnection(String serviceName) {
        Integer localPort = localServicesPorts.get(serviceName);
        if (localPort == null) {
            // Check for a conventional name if not directly in map, e.g. if serviceName is "echo-module"
            // and local config is "echo-module-local"
            localPort = localServicesPorts.get(serviceName + "-local"); 
        }

        if (localPort != null) {
            log.info("Attempting localhost connection for service '{}' on port {}", serviceName, localPort);
            try {
                // Perform a quick check if the port is connectable, or just build the channel
                // For simplicity, just building the channel. A more robust check might try a quick connect/ping.
                return Optional.of(buildManagedChannel("localhost", localPort, serviceName + " (localhost)"));
            } catch (Exception e) {
                log.warn("Localhost connection attempt for '{}' on port {} failed: {}", serviceName, localPort, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private ManagedChannel buildManagedChannel(String host, int port, String logContextServiceName) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
        if (usePlaintext) {
            builder.usePlaintext();
            log.debug("Using plaintext connection for service '{}'", logContextServiceName);
        } else {
            log.debug("Using secure connection (TLS/SSL) for service '{}'", logContextServiceName);
        }
        ManagedChannel channel = builder.build();
        log.info("Successfully created ManagedChannel for service: '{}' at {}:{}", logContextServiceName, host, port);
        return channel;
    }

    @PreDestroy
    public void shutdownAllChannels() {
        log.info("Shutting down all cached gRPC channels. Number of channels: {}", channelCache.size());
        channelCache.values().forEach(channel -> {
            try {
                log.info("Shutting down channel: {}", channel);
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("Interrupted while shutting down channel {}. Forcing shutdown.", channel);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        channelCache.clear();
        log.info("All cached gRPC channels processed for shutdown.");
    }
}