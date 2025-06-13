package com.krickert.search.engine.core.routing;

import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import com.krickert.search.config.consul.service.BusinessOperationsService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of Router that delegates to appropriate MessageForwarders
 * based on transport type.
 */
@Singleton
public class DefaultRouter implements Router {
    
    private static final Logger logger = LoggerFactory.getLogger(DefaultRouter.class);
    
    private final Map<RouteData.TransportType, MessageForwarder> forwarders;
    private final RoutingStrategy routingStrategy;
    private final BusinessOperationsService businessOpsService;
    private final String clusterName;
    
    @Inject
    public DefaultRouter(List<MessageForwarder> forwarderList, 
                        RoutingStrategy routingStrategy,
                        BusinessOperationsService businessOpsService,
                        @Value("${app.config.cluster-name}") String clusterName) {
        this.forwarders = forwarderList.stream()
                .collect(Collectors.toMap(
                        MessageForwarder::getTransportType,
                        forwarder -> forwarder,
                        (existing, replacement) -> {
                            logger.warn("Duplicate forwarder for transport type {}. Using the last one.", 
                                    replacement.getTransportType());
                            return replacement;
                        }
                ));
        this.routingStrategy = routingStrategy;
        this.businessOpsService = businessOpsService;
        this.clusterName = clusterName;
        
        logger.info("Initialized router with {} forwarders: {}", 
                forwarders.size(), 
                forwarders.keySet());
    }
    
    @Override
    public Mono<Void> route(PipeStream pipeStream, RouteData routeData) {
        logger.debug("Routing message {} via {} to {}", 
                pipeStream.getStreamId(), 
                routeData.transportType(),
                routeData.destinationService());
        
        MessageForwarder forwarder = forwarders.get(routeData.transportType());
        if (forwarder == null) {
            return Mono.error(new IllegalStateException(
                    "No forwarder available for transport type: " + routeData.transportType()));
        }
        
        return forwarder.forward(pipeStream, routeData)
                .doOnNext(optionalStream -> {
                    if (optionalStream.isPresent()) {
                        logger.debug("Received processed stream from {}, continuing pipeline", 
                            routeData.destinationService());
                    } else {
                        logger.debug("No processed stream returned from {}", 
                            routeData.destinationService());
                    }
                })
                .flatMap(optionalStream -> {
                    if (optionalStream.isPresent()) {
                        // We have a processed stream, need to continue the pipeline
                        // But we need access to pipeline configuration to know outputs
                        return continueToNextSteps(optionalStream.get(), routeData);
                    }
                    return Mono.empty();
                })
                .doOnSuccess(v -> logger.debug("Successfully routed message {} to {}", 
                        pipeStream.getStreamId(), 
                        routeData.destinationService()))
                .doOnError(e -> logger.error("Failed to route message {} to {}: {}", 
                        pipeStream.getStreamId(), 
                        routeData.destinationService(), 
                        e.getMessage()));
    }
    
    @Override
    public Mono<Void> route(PipeStream pipeStream) {
        logger.debug("Determining route for message {} at step {}", 
                pipeStream.getStreamId(), 
                pipeStream.getTargetStepName());
        
        return routingStrategy.determineRoute(pipeStream)
                .flatMap(routeData -> route(pipeStream, routeData));
    }
    
    private Mono<Void> continueToNextSteps(PipeStream processedStream, RouteData previousRouteData) {
        // Get the pipeline configuration to find outputs for the current step
        String pipelineName = processedStream.getCurrentPipelineName();
        String currentStepName = previousRouteData.targetStepName();
        
        logger.debug("Checking for outputs from step {} in pipeline {}", currentStepName, pipelineName);
        
        return businessOpsService.getSpecificPipelineConfig(clusterName, pipelineName)
            .flatMap(configOpt -> {
                if (configOpt.isEmpty()) {
                    logger.warn("No pipeline configuration found for {} when looking for outputs", pipelineName);
                    return Mono.empty();
                }
                
                var pipelineConfig = configOpt.get();
                var stepConfig = pipelineConfig.pipelineSteps().get(currentStepName);
                
                if (stepConfig == null || stepConfig.outputs() == null || stepConfig.outputs().isEmpty()) {
                    logger.debug("No outputs configured for step {}", currentStepName);
                    return Mono.empty();
                }
                
                // Route to each configured output
                return Flux.fromIterable(stepConfig.outputs().entrySet())
                    .flatMap(entry -> {
                        var outputTarget = entry.getValue();
                        String nextStepName = outputTarget.targetStepName();
                        
                        logger.info("Routing output from {} to next step {}", currentStepName, nextStepName);
                        
                        // Create new PipeStream for the next step
                        PipeStream nextStream = processedStream.toBuilder()
                            .setTargetStepName(nextStepName)
                            .build();
                        
                        // Route to the next step
                        return route(nextStream);
                    })
                    .then();
            });
    }
}