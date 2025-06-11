package com.krickert.search.pipeline.grpc.client;

import com.krickert.search.pipeline.engine.exception.GrpcEngineException;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
public class GrpcChannelManager implements AutoCloseable { // Implement AutoCloseable for @PreDestroy
    private static final Logger log = LoggerFactory.getLogger(GrpcChannelManager.class);

    private final DiscoveryClient discoveryClient;
    private final boolean usePlaintext;
    private final Duration discoveryAttemptTimeout;
    private final int discoveryMaxRetries;
    private final long discoveryRetryDelayMs;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> localServicesPorts;

    @Inject
    public GrpcChannelManager(
            DiscoveryClient discoveryClient,
            LocalServicesConfig localServicesConfig,
            @Value("${grpc.client.plaintext:true}") boolean usePlaintext,
            @Value("${grpc.client.discovery.attempt-timeout:5s}") Duration discoveryAttemptTimeout,
            @Value("${grpc.client.discovery.max-retries:3}") int discoveryMaxRetries,
            @Value("${grpc.client.discovery.retry-delay-ms:1000}") long discoveryRetryDelayMs
    ) {
        this.discoveryClient = discoveryClient;
        this.usePlaintext = usePlaintext;
        this.discoveryAttemptTimeout = discoveryAttemptTimeout;
        this.discoveryMaxRetries = discoveryMaxRetries;
        this.discoveryRetryDelayMs = discoveryRetryDelayMs;

        // Get the ports map from the injected config, defaulting to an empty map
        // The LocalServicesConfig itself will handle the null case for its 'ports' field if initialized.
        this.localServicesPorts = localServicesConfig.getPorts();

        log.info("GrpcChannelManager initialized. Plaintext: {}, Localhost first ports: {}",
                this.usePlaintext, this.localServicesPorts.keySet());
    }

    public ManagedChannel getChannel(String serviceName) throws GrpcEngineException {
        // Using computeIfAbsent is generally good, but ensure that the createChannel
        // method doesn't throw checked exceptions not declared by Function,
        // or handle them appropriately. GrpcEngineException is a RuntimeException, so it's fine.
        return channelCache.computeIfAbsent(serviceName, this::createChannelOrThrow);
    }

    // Renamed to clarify it can throw GrpcEngineException
    private ManagedChannel createChannelOrThrow(String serviceName) {
        log.info("Cache miss. Attempting channel creation for service: '{}'", serviceName);

        // Check if serviceName looks like a URI (host:port)
        Optional<ManagedChannel> uriChannel = tryParseAsUri(serviceName);
        if (uriChannel.isPresent()) {
            log.info("Successfully connected to service '{}' using direct URI format.", serviceName);
            return uriChannel.get();
        }

        Optional<ManagedChannel> localChannel = tryLocalhostConnection(serviceName);
        if (localChannel.isPresent()) {
            log.info("Successfully connected to local service '{}' via localhost configuration.", serviceName);
            return localChannel.get();
        }
        log.info("Local service '{}' not found or connection failed via localhost configuration. Proceeding with discovery.", serviceName);

        List<ServiceInstance> instances = discoverServiceInstances(serviceName);

        if (instances.isEmpty()) {
            log.error("No instances found via discovery for service: '{}' after {} retries.", serviceName, discoveryMaxRetries);
            throw new GrpcEngineException("Service not found via discovery after " + discoveryMaxRetries + " retries: " + serviceName);
        }

        // Basic strategy: Use the first discovered instance.
        // More advanced strategies (e.g., round-robin, health checks) could be implemented here.
        ServiceInstance instance = instances.getFirst();
        String host = instance.getHost();
        int port = instance.getPort();
        log.info("Selected discovered instance for service '{}' at {}:{}", serviceName, host, port);

        // Check if the host:port from Consul looks like a URI (e.g., "localhost:50052")
        // If so, try to create a direct channel using the URI format
        String hostPortUri = host + ":" + port;
        Optional<ManagedChannel> discoveredUriChannel = tryParseAsUri(hostPortUri);
        if (discoveredUriChannel.isPresent()) {
            log.info("Successfully connected to service '{}' using discovered URI format: {}", serviceName, hostPortUri);
            return discoveredUriChannel.get();
        }

        return buildManagedChannel(host, port, serviceName);
    }

    private List<ServiceInstance> discoverServiceInstances(String serviceName) {
        List<ServiceInstance> instances = Collections.emptyList();
        for (int attempt = 1; attempt <= discoveryMaxRetries; attempt++) {
            log.info("Attempting discovery for service: '{}', attempt {}/{}", serviceName, attempt, discoveryMaxRetries);
            Publisher<List<ServiceInstance>> instancesPublisher = discoveryClient.getInstances(serviceName);
            try {
                // It's generally safer to handle potential null from block() if the publisher can complete empty.
                instances = Mono.from(instancesPublisher)
                        .blockOptional(discoveryAttemptTimeout)
                        .orElse(Collections.emptyList());
            } catch (IllegalStateException e) { // Often indicates timeout or other reactive stream error
                log.warn("Discovery attempt {} for service '{}' failed (possibly timeout or empty publisher): {}", attempt, serviceName, e.getMessage());
            } catch (Exception e) { // Catch other potential runtime exceptions from blockOptional
                log.warn("Unexpected error during discovery attempt {} for service '{}': {}", attempt, serviceName, e.getMessage(), e);
            }


            if (!instances.isEmpty()) {
                log.info("Successfully discovered service '{}' on attempt {}/{}. Instances found: {}", serviceName, attempt, discoveryMaxRetries, instances.size());
                return instances; // Return as soon as instances are found
            }

            log.warn("Discovery attempt {}/{} for service '{}' failed or returned no instances.", attempt, discoveryMaxRetries, serviceName);
            if (attempt < discoveryMaxRetries) {
                try {
                    log.info("Waiting {}ms before next discovery attempt for service '{}'.", discoveryRetryDelayMs, serviceName);
                    TimeUnit.MILLISECONDS.sleep(discoveryRetryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted during discovery retry delay for service '{}'.", serviceName);
                    // Propagate as a GrpcEngineException or handle as appropriate for your application
                    throw new GrpcEngineException("Interrupted during discovery retry for " + serviceName, e);
                }
            }
        }
        return instances; // Will be empty if all retries fail
    }

    /**
     * Try to parse the service name as a URI (host:port) and create a ManagedChannel if it's in the correct format.
     * This is useful for container-to-container communication where Consul returns "localhost" as the service address.
     */
    private Optional<ManagedChannel> tryParseAsUri(String serviceName) {
        // Check if the service name contains a colon, which would indicate a host:port format
        if (serviceName.contains(":")) {
            try {
                String[] parts = serviceName.split(":");
                if (parts.length == 2) {
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    log.info("Service name '{}' appears to be in URI format. Parsing as host='{}', port={}",
                            serviceName, host, port);
                    return Optional.of(buildManagedChannel(host, port, serviceName + " (direct URI)"));
                }
            } catch (NumberFormatException e) {
                log.warn("Service name '{}' contains a colon but could not be parsed as host:port: {}",
                        serviceName, e.getMessage());
            } catch (Exception e) {
                log.warn("Unexpected error parsing service name '{}' as URI: {}",
                        serviceName, e.getMessage(), e);
            }
        }
        return Optional.empty();
    }

    private Optional<ManagedChannel> tryLocalhostConnection(String serviceName) {
        String effectiveServiceNameKey = serviceName;
        Integer localPort = localServicesPorts.get(effectiveServiceNameKey);

        if (localPort == null) {
            // Convention: try with "-local" suffix
            effectiveServiceNameKey = serviceName + "-local";
            localPort = localServicesPorts.get(effectiveServiceNameKey);
        }

        if (localPort != null) {
            log.info("Found localhost configuration for service '{}' (using key '{}') on port {}. Attempting connection.",
                    serviceName, effectiveServiceNameKey, localPort);
            try {
                return Optional.of(buildManagedChannel("localhost", localPort, serviceName + " (localhost)"));
            } catch (Exception e) {
                // Log the exception details for better debugging
                log.warn("Localhost connection attempt for service '{}' (key '{}', port {}) failed: {}",
                        serviceName, effectiveServiceNameKey, localPort, e.getMessage(), e);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private ManagedChannel buildManagedChannel(String host, int port, String logContextServiceName) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
        if (usePlaintext) {
            builder.usePlaintext();
            log.debug("Using plaintext connection for service '{}' at {}:{}", logContextServiceName, host, port);
        } else {
            // Add TLS/SSL configuration here if needed
            log.debug("Using secure connection (TLS/SSL) for service '{}' at {}:{}", logContextServiceName, host, port);
        }
        // Consider adding other default channel configurations:
        // .loadBalancingPolicy("round_robin") // If multiple addresses resolved by discovery
        // .intercept(...) // For common interceptors like logging, metrics, auth
        // .keepAliveTime(1, TimeUnit.MINUTES)
        // .keepAliveTimeout(20, TimeUnit.SECONDS)
        // .enableRetry() // If you want gRPC built-in retries (requires service config)

        ManagedChannel channel = builder.build();
        log.info("Successfully created ManagedChannel for service: '{}' targeting {}:{}", logContextServiceName, host, port);
        return channel;
    }

    @PreDestroy // This annotation ensures the method is called on context shutdown
    @Override    // From AutoCloseable
    public void close() { // Renamed for clarity and to match AutoCloseable
        log.info("Shutting down all cached gRPC channels. Number of channels: {}", channelCache.size());
        for (Map.Entry<String, ManagedChannel> entry : channelCache.entrySet()) {
            ManagedChannel channel = entry.getValue();
            String serviceKey = entry.getKey();
            try {
                log.info("Shutting down channel for service key '{}': {}", serviceKey, channel);
                if (!channel.isTerminated()) {
                    channel.shutdown();
                    if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Channel for service key '{}' did not terminate in 5 seconds. Forcing shutdown.", serviceKey);
                        channel.shutdownNow();
                        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                            log.error("Channel for service key '{}' failed to terminate even after force shutdown.", serviceKey);
                        }
                    }
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while shutting down channel for service key '{}'. Forcing shutdown.", serviceKey);
                if (!channel.isTerminated()) {
                    channel.shutdownNow();
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Unexpected error shutting down channel for service key '{}': {}", serviceKey, e.getMessage(), e);
            }
        }
        channelCache.clear();
        log.info("All cached gRPC channels processed for shutdown.");
    }
}
