package com.krickert.search.engine.service.impl;

import com.google.protobuf.Timestamp;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.engine.registration.ModuleRegistrationService;
import com.krickert.search.engine.routing.ModuleConnector;
import com.krickert.search.engine.routing.PipelineRouter;
import com.krickert.search.engine.service.MessageRoutingService;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.*;
import com.krickert.search.sdk.ProcessResponse;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of MessageRoutingService that handles message routing
 * through gRPC calls to modules and Kafka for async communication.
 */
@Singleton
public class MessageRoutingServiceImpl implements MessageRoutingService {
    
    private static final Logger LOG = LoggerFactory.getLogger(MessageRoutingServiceImpl.class);
    
    private final ModuleRegistrationService registrationService;
    private final ConsulBusinessOperationsService consulService;
    private final PipelineRouter pipelineRouter;
    private final ModuleConnector moduleConnector;
    private final KafkaMessageProducer kafkaProducer;
    private final String clusterName;
    
    // Cache for pipeline configurations
    private final Map<String, PipelineConfig> pipelineCache = new ConcurrentHashMap<>();
    
    @Inject
    public MessageRoutingServiceImpl(
            ModuleRegistrationService registrationService,
            ConsulBusinessOperationsService consulService,
            PipelineRouter pipelineRouter,
            ModuleConnector moduleConnector,
            KafkaMessageProducer kafkaProducer,
            @Value("${yappy.cluster.name:default}") String clusterName) {
        this.registrationService = registrationService;
        this.consulService = consulService;
        this.pipelineRouter = pipelineRouter;
        this.moduleConnector = moduleConnector;
        this.kafkaProducer = kafkaProducer;
        this.clusterName = clusterName;
    }
    
    @Override
    public Mono<PipeStream> routeMessage(PipeStream pipeStream, String pipelineName) {
        LOG.debug("Routing message through pipeline: {}, stream: {}", 
            pipelineName, pipeStream.getStreamId());
        
        return loadPipelineConfig(pipelineName)
            .flatMap(pipelineConfig -> {
                // Check if pipeline is complete
                if (pipelineRouter.isPipelineComplete(pipeStream, pipelineConfig)) {
                    LOG.info("Pipeline {} complete for stream {}", 
                        pipelineName, pipeStream.getStreamId());
                    return Mono.just(pipeStream);
                }
                
                // Get next step
                Optional<PipelineStepConfig> nextStep = 
                    pipelineRouter.getNextStep(pipeStream, pipelineConfig);
                
                if (nextStep.isEmpty()) {
                    LOG.debug("No more steps to execute in pipeline {} for stream {}", 
                        pipelineName, pipeStream.getStreamId());
                    return Mono.just(pipeStream);
                }
                
                PipelineStepConfig step = nextStep.get();
                LOG.info("Executing step {} in pipeline {} for stream {}", 
                    step.stepName(), pipelineName, pipeStream.getStreamId());
                
                // Execute the step
                return executeStep(pipeStream, step, pipelineName)
                    .flatMap(updatedStream -> {
                        // Recursively route to next step
                        return routeMessage(updatedStream, pipelineName);
                    });
            })
            .onErrorResume(error -> {
                LOG.error("Error routing message through pipeline {}: {}", 
                    pipelineName, error.getMessage(), error);
                
                // Update stream with error
                PipeStream.Builder errorStream = pipeStream.toBuilder();
                errorStream.setStreamErrorData(ErrorData.newBuilder()
                    .setErrorMessage(error.getMessage())
                    .setOriginatingStepName("routing")
                    .setTimestamp(createTimestamp())
                    .build());
                
                return Mono.just(errorStream.build());
            });
    }
    
    @Override
    public Mono<PipeStream> sendToModule(PipeStream pipeStream, String moduleId) {
        return registrationService.isModuleHealthy(moduleId)
            .flatMap(isHealthy -> {
                if (!isHealthy) {
                    return Mono.error(new IllegalStateException(
                        "Module " + moduleId + " is not healthy"));
                }
                
                // Get module info from Consul
                return consulService.getAgentServiceDetails(moduleId)
                    .flatMap(serviceOpt -> {
                        if (serviceOpt.isEmpty()) {
                            return Mono.error(new IllegalArgumentException(
                                "Module not found: " + moduleId));
                        }
                        
                        var fullService = serviceOpt.get();
                        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                            .setServiceId(moduleId)
                            .setServiceName(fullService.getService())
                            .setHost(fullService.getAddress())
                            .setPort(fullService.getPort())
                            .build();
                        
                        // Create a simple step config for direct module call
                        Map<String, Object> stepConfig = new HashMap<>();
                        stepConfig.put("directCall", true);
                        
                        return moduleConnector.processDocument(
                            moduleInfo,
                            pipeStream,
                            "direct-call",
                            stepConfig
                        ).map(response -> updateStreamFromResponse(pipeStream, response, "direct-call"));
                    });
            });
    }
    
    @Override
    public Mono<Boolean> sendToKafkaTopic(PipeStream pipeStream, String topicName) {
        return Mono.fromCallable(() -> {
            try {
                kafkaProducer.sendMessage(topicName, 
                    pipeStream.getDocument().getId(), 
                    pipeStream.toByteArray());
                LOG.debug("Sent message to Kafka topic: {}", topicName);
                return true;
            } catch (Exception e) {
                LOG.error("Error sending message to Kafka topic: {}", topicName, e);
                return false;
            }
        });
    }
    
    private Mono<PipelineConfig> loadPipelineConfig(String pipelineName) {
        // Check cache first
        PipelineConfig cached = pipelineCache.get(pipelineName);
        if (cached != null) {
            return Mono.just(cached);
        }
        
        // Load from Consul
        return consulService.getSpecificPipelineConfig(clusterName, pipelineName)
            .map(configOpt -> {
                if (configOpt.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Pipeline configuration not found: " + pipelineName);
                }
                PipelineConfig config = configOpt.get();
                pipelineCache.put(pipelineName, config);
                return config;
            });
    }
    
    private Mono<PipeStream> executeStep(
            PipeStream pipeStream, 
            PipelineStepConfig step,
            String pipelineName) {
        
        // Find the module that implements this step
        return findModuleForStep(step)
            .flatMap(moduleInfo -> {
                // Record step start
                StepExecutionRecord.Builder recordBuilder = StepExecutionRecord.newBuilder()
                    .setStepName(step.stepName())
                    .setHopNumber(pipeStream.getCurrentHopNumber() + 1)
                    .setStartTime(createTimestamp());
                
                // Set implementation ID based on processorInfo
                if (step.processorInfo().grpcServiceName() != null) {
                    recordBuilder.setServiceInstanceId(step.processorInfo().grpcServiceName());
                } else if (step.processorInfo().internalProcessorBeanName() != null) {
                    recordBuilder.setServiceInstanceId(step.processorInfo().internalProcessorBeanName());
                }
                
                // Convert step config
                Map<String, Object> stepConfigMap = new HashMap<>();
                if (step.customConfig() != null && step.customConfig().jsonConfig() != null) {
                    stepConfigMap.put("customConfig", step.customConfig().jsonConfig());
                }
                if (step.customConfig() != null && step.customConfig().configParams() != null) {
                    stepConfigMap.putAll(step.customConfig().configParams());
                }
                
                // Update stream with current pipeline name
                PipeStream.Builder streamBuilder = pipeStream.toBuilder();
                streamBuilder.setCurrentPipelineName(pipelineName);
                
                PipeStream updatedStream = streamBuilder.build();
                
                // Process the document
                return moduleConnector.processDocument(
                    moduleInfo,
                    updatedStream,
                    step.stepName(),
                    stepConfigMap
                ).map(response -> {
                    // Update execution record
                    recordBuilder.setEndTime(createTimestamp());
                    recordBuilder.setStatus(response.getSuccess() ? "SUCCESS" : "FAILURE");
                    
                    if (!response.getSuccess() && response.hasErrorDetails()) {
                        recordBuilder.setErrorInfo(ErrorData.newBuilder()
                            .setErrorMessage(response.getErrorDetails().toString())
                            .setOriginatingStepName(step.stepName())
                            .setTimestamp(createTimestamp())
                            .build());
                    }
                    
                    // Add logs if present
                    if (!response.getProcessorLogsList().isEmpty()) {
                        recordBuilder.addAllProcessorLogs(response.getProcessorLogsList());
                    }
                    
                    // Update stream with response
                    return updateStreamFromResponse(updatedStream, response, step.stepName())
                        .toBuilder()
                        .addHistory(recordBuilder.build())
                        .setCurrentHopNumber(updatedStream.getCurrentHopNumber() + 1)
                        .build();
                });
            });
    }
    
    private Mono<ModuleInfo> findModuleForStep(PipelineStepConfig step) {
        // Get implementation ID from processorInfo
        String implementationId = null;
        if (step.processorInfo().grpcServiceName() != null) {
            implementationId = step.processorInfo().grpcServiceName();
        } else if (step.processorInfo().internalProcessorBeanName() != null) {
            implementationId = step.processorInfo().internalProcessorBeanName();
        }
        
        if (implementationId == null) {
            return Mono.error(new IllegalStateException(
                "No implementation ID found for step: " + step.stepName()));
        }
        
        final String implId = implementationId;
        return registrationService.isModuleHealthy(implId)
            .flatMap(isHealthy -> {
                if (!isHealthy) {
                    // Try to find another instance
                    LOG.warn("Primary module {} is not healthy, looking for alternatives", 
                        implId);
                }
                
                // For now, use the implementation ID as the module ID
                // In a real system, this would do service discovery
                return consulService.getAgentServiceDetails(implId)
                    .flatMap(serviceOpt -> {
                        if (serviceOpt.isEmpty()) {
                            return Mono.error(new IllegalStateException(
                                "No module available for implementation: " + implId));
                        }
                        
                        var fullService = serviceOpt.get();
                        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                            .setServiceId(implId)
                            .setServiceName(fullService.getService())
                            .setHost(fullService.getAddress())
                            .setPort(fullService.getPort())
                            .build();
                        
                        return Mono.just(moduleInfo);
                    });
            });
    }
    
    private PipeStream updateStreamFromResponse(
            PipeStream originalStream, 
            ProcessResponse response,
            String stepName) {
        
        PipeStream.Builder builder = originalStream.toBuilder();
        
        // Update document if provided
        if (response.hasOutputDoc()) {
            builder.setDocument(response.getOutputDoc());
        }
        
        // Add error if processing failed
        if (!response.getSuccess()) {
            ErrorData.Builder errorBuilder = ErrorData.newBuilder()
                .setErrorMessage("Step " + stepName + " failed")
                .setOriginatingStepName(stepName)
                .setTimestamp(createTimestamp());
            
            if (response.hasErrorDetails()) {
                errorBuilder.setErrorMessage(response.getErrorDetails().toString());
            }
            
            builder.setStreamErrorData(errorBuilder.build());
        }
        
        return builder.build();
    }
    
    private Timestamp createTimestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build();
    }
    
    /**
     * Cleanup resources on shutdown.
     */
    @PreDestroy
    public void shutdown() {
        LOG.info("Shutting down MessageRoutingService");
        moduleConnector.disconnectAll();
        pipelineCache.clear();
    }
    
    /**
     * Kafka producer interface for sending messages.
     */
    @KafkaClient
    public interface KafkaMessageProducer {
        void sendMessage(@Topic String topic, String key, byte[] value);
    }
}