package com.krickert.search.engine.core.transport.grpc;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.catalog.CatalogService;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Forwards PipeStream messages to gRPC services.
 * Uses service discovery to find the target service.
 */
@Singleton
public class GrpcMessageForwarder implements MessageForwarder {
    
    private static final Logger logger = LoggerFactory.getLogger(GrpcMessageForwarder.class);
    
    private final BusinessOperationsService businessOpsService;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final String clusterName;
    private final boolean testMode;
    
    @Inject
    public GrpcMessageForwarder(
            BusinessOperationsService businessOpsService,
            @Value("${engine.cluster.name:default-cluster}") String clusterName,
            @Value("${engine.test-mode:false}") boolean testMode) {
        this.businessOpsService = businessOpsService;
        this.clusterName = clusterName;
        this.testMode = testMode;
        logger.info("GrpcMessageForwarder initialized with clusterName={}, testMode={}", clusterName, testMode);
    }
    
    @Override
    public Mono<Optional<PipeStream>> forward(PipeStream pipeStream, RouteData routeData) {
        String serviceName = routeData.destinationService();
        String clusterServiceName = clusterName + "-" + serviceName;
        
        logger.debug("Forwarding message {} to gRPC service {} for step {}", 
            pipeStream.getStreamId(), clusterServiceName, routeData.targetStepName());
        
        // Discover service using BusinessOperationsService
        // In test mode, use all instances (not just healthy ones)
        if (testMode) {
            return businessOpsService.getServiceInstances(clusterServiceName)
                .doOnNext(services -> logger.debug("Found {} registered instances for service {}", 
                    services.size(), clusterServiceName))
                .flatMap(services -> {
                    if (services.isEmpty()) {
                        logger.error("No registered instances found for service: {}", clusterServiceName);
                        return Mono.error(new IllegalStateException(
                            "No registered instances found for service: " + clusterServiceName));
                    }
                    
                    // Use first available instance
                    CatalogService service = services.get(0);
                    String address = service.getServiceAddress();
                    int port = service.getServicePort();
                    
                    return createChannelAndForward(address, port, clusterServiceName, pipeStream, routeData);
                });
        } else {
            return businessOpsService.getHealthyServiceInstances(clusterServiceName)
                .doOnNext(services -> logger.debug("Found {} healthy instances for service {}", 
                    services.size(), clusterServiceName))
                .flatMap(services -> {
                    if (services.isEmpty()) {
                        logger.error("No healthy instances found for service: {}. Checking all registered instances...", 
                            clusterServiceName);
                        
                        // Log all registered instances for debugging
                        return businessOpsService.getServiceInstances(clusterServiceName)
                            .flatMap(allServices -> {
                                logger.error("Total registered instances for {}: {}", 
                                    clusterServiceName, allServices.size());
                                allServices.forEach(svc -> 
                                    logger.error("  - Instance: {}:{} (ID: {})", 
                                        svc.getServiceAddress(), svc.getServicePort(), svc.getServiceId()));
                                
                                return Mono.error(new IllegalStateException(
                                    "No healthy instances found for service: " + clusterServiceName + 
                                    " (total registered: " + allServices.size() + ")"));
                            });
                    }
                    
                    // Take first healthy instance
                    ServiceHealth service = services.get(0);
                    String address = service.getService().getAddress();
                    int port = service.getService().getPort();
                    
                    return createChannelAndForward(address, port, clusterServiceName, pipeStream, routeData);
                });
        }
    }
    
    private Mono<Optional<PipeStream>> createChannelAndForward(String address, int port, String clusterServiceName,
                                               PipeStream pipeStream, RouteData routeData) {
        logger.debug("Using service instance at {}:{} for {}", 
            address, port, clusterServiceName);
        
        // Get or create channel
        String channelKey = address + ":" + port;
        ManagedChannel channel = channelCache.computeIfAbsent(channelKey, k -> {
            logger.info("Creating new gRPC channel to {}:{}", address, port);
            return ManagedChannelBuilder.forAddress(address, port)
                .usePlaintext()
                .build();
        });
        
        // Create stub and forward
        return forwardToService(channel, pipeStream, routeData)
            .doOnError(error -> logger.error("Failed to forward message to service {}: {}", 
                clusterServiceName, error.getMessage()));
    }
    
    private Mono<Optional<PipeStream>> forwardToService(ManagedChannel channel, PipeStream pipeStream, RouteData routeData) {
        // First get the configuration for this step
        String pipelineName = routeData.targetPipelineName() != null ? 
            routeData.targetPipelineName() : pipeStream.getCurrentPipelineName();
            
        return businessOpsService.getSpecificPipelineConfig(clusterName, pipelineName)
            .flatMap(configOpt -> {
                if (configOpt.isEmpty()) {
                    logger.warn("No pipeline configuration found for {}", pipelineName);
                    return Mono.just(ProcessConfiguration.getDefaultInstance());
                }
                
                var pipelineConfig = configOpt.get();
                var stepConfig = pipelineConfig.pipelineSteps().get(routeData.targetStepName());
                
                if (stepConfig == null || stepConfig.customConfig() == null || 
                    stepConfig.customConfig().jsonConfig() == null) {
                    logger.debug("No custom configuration for step {}", routeData.targetStepName());
                    return Mono.just(ProcessConfiguration.getDefaultInstance());
                }
                
                // Convert the JSON config to ProcessConfiguration
                try {
                    var jsonNode = stepConfig.customConfig().jsonConfig();
                    var structBuilder = com.google.protobuf.Struct.newBuilder();
                    com.google.protobuf.util.JsonFormat.parser()
                        .merge(jsonNode.toString(), structBuilder);
                    
                    return Mono.just(ProcessConfiguration.newBuilder()
                        .setCustomJsonConfig(structBuilder.build())
                        .build());
                } catch (Exception e) {
                    logger.error("Failed to convert configuration for step {}: {}", 
                        routeData.targetStepName(), e.getMessage());
                    return Mono.just(ProcessConfiguration.getDefaultInstance());
                }
            })
            .flatMap(config -> Mono.fromCallable(() -> {
                PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                    PipeStepProcessorGrpc.newBlockingStub(channel);
                
                // Build process request with configuration
                ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(pipeStream.getDocument())
                    .setMetadata(ServiceMetadata.newBuilder()
                        .setPipelineName(pipelineName)
                        .setPipeStepName(routeData.targetStepName())
                        .setStreamId(routeData.streamId())
                        .setCurrentHopNumber(pipeStream.getCurrentHopNumber() + 1)
                        .build())
                    .setConfig(config)  // Include the configuration
                    .build();
                
                // Make the call
                ProcessResponse response = stub.processData(request);
                
                if (!response.getSuccess()) {
                    throw new RuntimeException("Service " + routeData.destinationService() + 
                        " failed to process message: " + response.getErrorDetails());
                }
                
                logger.info("Successfully forwarded message {} to service {}", 
                    pipeStream.getStreamId(), routeData.destinationService());
                
                return response;
            }))
            .map(response -> {
                // If the response has an output document, create a new PipeStream for the next step
                if (response.hasOutputDoc()) {
                    PipeStream nextStream = pipeStream.toBuilder()
                        .setDocument(response.getOutputDoc())
                        .setCurrentHopNumber(pipeStream.getCurrentHopNumber() + 1)
                        .build();
                    return Optional.of(nextStream);
                } else {
                    return Optional.<PipeStream>empty();
                }
            });
    }
    
    @Override
    public boolean canHandle(RouteData.TransportType transportType) {
        return transportType == RouteData.TransportType.GRPC;
    }
    
    @Override
    public RouteData.TransportType getTransportType() {
        return RouteData.TransportType.GRPC;
    }
    
    /**
     * Clean up channels on shutdown
     */
    public void shutdown() {
        channelCache.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while shutting down channel", e);
                Thread.currentThread().interrupt();
            }
        });
        channelCache.clear();
    }
}