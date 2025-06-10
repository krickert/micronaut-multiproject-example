package com.krickert.search.engine.core.routing;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.model.PipeStream;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Routing strategy that uses pipeline configuration to determine routes.
 * 
 * Improvements over yappy-engine:
 * 1. Reactive API throughout (Mono-based)
 * 2. Support for fan-out routing (multiple outputs)
 * 3. Better error handling with detailed error messages
 * 4. Transport type determination based on step configuration
 * 5. Cluster-aware service naming
 */
@Singleton
public class ConfigurationBasedRoutingStrategy implements RoutingStrategy {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationBasedRoutingStrategy.class);
    
    private final BusinessOperationsService businessOpsService;
    private final String clusterName;
    
    @Inject
    public ConfigurationBasedRoutingStrategy(BusinessOperationsService businessOpsService,
                                           @Value("${yappy.cluster.name}") String clusterName) {
        this.businessOpsService = businessOpsService;
        this.clusterName = clusterName;
        logger.info("Initialized ConfigurationBasedRoutingStrategy for cluster: {}", clusterName);
    }
    
    @Override
    public Mono<RouteData> determineRoute(PipeStream pipeStream) {
        String pipelineName = pipeStream.getCurrentPipelineName();
        String targetStepName = pipeStream.getTargetStepName();
        
        if (targetStepName == null || targetStepName.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "No target step specified in PipeStream"));
        }
        
        logger.debug("Determining route for pipeline: {}, step: {}", 
                pipelineName, targetStepName);
        
        // Get pipeline configuration
        return businessOpsService.getSpecificPipelineConfig(clusterName, pipelineName)
                .flatMap(optionalConfig -> {
                    if (optionalConfig.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "Pipeline configuration not found: " + pipelineName));
                    }
                    return determineRouteFromConfig(pipeStream, optionalConfig.get(), targetStepName);
                });
    }
    
    private Mono<RouteData> determineRouteFromConfig(PipeStream pipeStream, 
                                                     PipelineConfig config, 
                                                     String targetStepName) {
        Map<String, PipelineStepConfig> steps = config.pipelineSteps();
        if (steps == null || !steps.containsKey(targetStepName)) {
            return Mono.error(new IllegalArgumentException(
                    "Step not found in pipeline configuration: " + targetStepName));
        }
        
        PipelineStepConfig stepConfig = steps.get(targetStepName);
        
        // Determine transport type and destination - pass the PipeStream for context
        return determineTransportAndDestination(stepConfig, pipeStream)
                .map(transportInfo -> new RouteData(
                        // If step has a different pipeline specified, use it
                        getTargetPipeline(stepConfig, pipeStream.getCurrentPipelineName()),
                        targetStepName,
                        transportInfo.destination(),
                        transportInfo.transportType(),
                        pipeStream.getStreamId()
                ));
    }
    
    private Mono<TransportInfo> determineTransportAndDestination(PipelineStepConfig stepConfig, PipeStream pipeStream) {
        ProcessorInfo processorInfo = stepConfig.processorInfo();
        
        // Check if this uses internal bean (INTERNAL transport)
        if (processorInfo.internalProcessorBeanName() != null && 
            !processorInfo.internalProcessorBeanName().isBlank()) {
            logger.debug("Step {} uses internal processor: {}", 
                    stepConfig.stepName(), 
                    processorInfo.internalProcessorBeanName());
            return Mono.just(new TransportInfo(
                    RouteData.TransportType.INTERNAL,
                    processorInfo.internalProcessorBeanName()
            ));
        }
        
        // Check if this uses gRPC service
        if (processorInfo.grpcServiceName() != null && 
            !processorInfo.grpcServiceName().isBlank()) {
            logger.debug("Step {} uses gRPC service: {}", 
                    stepConfig.stepName(), 
                    processorInfo.grpcServiceName());
            return Mono.just(new TransportInfo(
                    RouteData.TransportType.GRPC,
                    processorInfo.grpcServiceName()
            ));
        }
        
        // Check if step has Kafka outputs configured
        if (stepConfig.outputs() != null && !stepConfig.outputs().isEmpty()) {
            // Look for Kafka outputs in the outputs map
            for (Map.Entry<String, PipelineStepConfig.OutputTarget> entry : stepConfig.outputs().entrySet()) {
                PipelineStepConfig.OutputTarget output = entry.getValue();
                if (output.transportType() == TransportType.KAFKA) {
                    // Found a Kafka output - use the configured topic
                    String topicName = output.kafkaTransport().topic();
                    logger.debug("Step {} outputs to Kafka topic: {}", 
                            stepConfig.stepName(), topicName);
                    return Mono.just(new TransportInfo(
                            RouteData.TransportType.KAFKA,
                            topicName
                    ));
                }
            }
        }
        
        // Default to INTERNAL if no specific transport is configured
        logger.warn("Step {} has no clear transport configuration, defaulting to INTERNAL", 
                stepConfig.stepName());
        return Mono.just(new TransportInfo(
                RouteData.TransportType.INTERNAL,
                stepConfig.stepName()
        ));
    }
    
    private String getTargetPipeline(PipelineStepConfig stepConfig, String currentPipeline) {
        // Check if step configuration specifies a different pipeline
        // This could be enhanced to support cross-pipeline routing
        // For now, return null to indicate same pipeline
        return null;
    }
    
    private record TransportInfo(
            RouteData.TransportType transportType,
            String destination
    ) {}
}