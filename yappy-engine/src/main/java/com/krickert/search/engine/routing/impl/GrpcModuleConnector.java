package com.krickert.search.engine.routing.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.engine.routing.ModuleConnector;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC-based implementation of ModuleConnector for communicating with processing modules.
 */
@Singleton
@Requires(property = "grpc.client.enabled", value = "true", defaultValue = "true")
public class GrpcModuleConnector implements ModuleConnector {
    
    private static final Logger LOG = LoggerFactory.getLogger(GrpcModuleConnector.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    
    private final ObjectMapper objectMapper;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    
    public GrpcModuleConnector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Mono<ProcessResponse> processDocument(
            ModuleInfo moduleInfo,
            PipeStream pipeStream,
            String stepName,
            Map<String, Object> stepConfig) {
        
        return Mono.fromCallable(() -> {
            try {
                // Get or create channel
                ManagedChannel channel = getOrCreateChannel(moduleInfo);
                
                // Create gRPC stub
                PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                    PipeStepProcessorGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                
                // Build the request
                ProcessRequest request = buildProcessRequest(pipeStream, stepName, stepConfig);
                
                // Make the gRPC call
                LOG.debug("Sending process request to module {} for step {}", 
                    moduleInfo.getServiceId(), stepName);
                
                ProcessResponse response = stub.processData(request);
                
                LOG.debug("Received response from module {} for step {}: success={}", 
                    moduleInfo.getServiceId(), stepName, response.getSuccess());
                
                return response;
                
            } catch (StatusRuntimeException e) {
                LOG.error("gRPC error calling module {} for step {}: {}", 
                    moduleInfo.getServiceId(), stepName, e.getStatus());
                throw new RuntimeException("Failed to process document in module: " + e.getStatus(), e);
            } catch (Exception e) {
                LOG.error("Error processing document in module {} for step {}", 
                    moduleInfo.getServiceId(), stepName, e);
                throw new RuntimeException("Failed to process document", e);
            }
        });
    }
    
    @Override
    public Mono<Boolean> isModuleAvailable(ModuleInfo moduleInfo) {
        return Mono.fromCallable(() -> {
            try {
                ManagedChannel channel = getOrCreateChannel(moduleInfo);
                
                // Try to get service registration as a health check
                PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                    PipeStepProcessorGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(5, TimeUnit.SECONDS);
                
                ServiceRegistrationData registration = stub.getServiceRegistration(Empty.getDefaultInstance());
                return registration != null;
            } catch (Exception e) {
                LOG.debug("Module {} is not available: {}", moduleInfo.getServiceId(), e.getMessage());
                return false;
            }
        });
    }
    
    @Override
    public Mono<ServiceRegistrationInfo> getServiceRegistration(ModuleInfo moduleInfo) {
        return Mono.fromCallable(() -> {
            ManagedChannel channel = getOrCreateChannel(moduleInfo);
            
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            
            ServiceRegistrationData registration = stub.getServiceRegistration(Empty.getDefaultInstance());
            
            Map<String, String> capabilities = new HashMap<>();
            // Extract capabilities from registration if available
            
            return new ServiceRegistrationInfo(
                registration.getModuleName(),
                registration.hasJsonConfigSchema() ? registration.getJsonConfigSchema() : null,
                capabilities
            );
        });
    }
    
    @Override
    public void disconnectModule(String moduleId) {
        ManagedChannel channel = channelCache.remove(moduleId);
        if (channel != null) {
            LOG.debug("Disconnecting from module: {}", moduleId);
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    @Override
    public void disconnectAll() {
        LOG.info("Disconnecting all module connections");
        channelCache.forEach((moduleId, channel) -> {
            try {
                channel.shutdown();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        });
        channelCache.clear();
    }
    
    @PreDestroy
    public void shutdown() {
        disconnectAll();
    }
    
    private ManagedChannel getOrCreateChannel(ModuleInfo moduleInfo) {
        String channelKey = moduleInfo.getHost() + ":" + moduleInfo.getPort();
        return channelCache.computeIfAbsent(channelKey, key -> {
            LOG.info("Creating gRPC channel to {}:{} for module {}", 
                moduleInfo.getHost(), moduleInfo.getPort(), moduleInfo.getServiceId());
            
            return ManagedChannelBuilder
                .forAddress(moduleInfo.getHost(), moduleInfo.getPort())
                .usePlaintext()
                .build();
        });
    }
    
    private ProcessRequest buildProcessRequest(
            PipeStream pipeStream, 
            String stepName, 
            Map<String, Object> stepConfig) throws Exception {
        
        ProcessRequest.Builder requestBuilder = ProcessRequest.newBuilder();
        
        // Set the document
        if (pipeStream.hasDocument()) {
            requestBuilder.setDocument(pipeStream.getDocument());
        }
        
        // Build configuration
        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        
        // Convert step config to Struct
        if (stepConfig != null && !stepConfig.isEmpty()) {
            String jsonConfig = objectMapper.writeValueAsString(stepConfig);
            Struct.Builder structBuilder = Struct.newBuilder();
            JsonFormat.parser().merge(jsonConfig, structBuilder);
            configBuilder.setCustomJsonConfig(structBuilder.build());
        }
        
        // Add config params if available
        Map<String, String> configParams = extractConfigParams(stepConfig);
        if (!configParams.isEmpty()) {
            configBuilder.putAllConfigParams(configParams);
        }
        
        requestBuilder.setConfig(configBuilder.build());
        
        // Build metadata
        ServiceMetadata.Builder metadataBuilder = ServiceMetadata.newBuilder()
            .setPipelineName(pipeStream.getCurrentPipelineName())
            .setPipeStepName(stepName)
            .setStreamId(pipeStream.getStreamId())
            .setCurrentHopNumber(pipeStream.getCurrentHopNumber());
        
        // Add execution history
        metadataBuilder.addAllHistory(pipeStream.getHistoryList());
        
        // Add error data if present
        if (pipeStream.hasStreamErrorData()) {
            metadataBuilder.setStreamErrorData(pipeStream.getStreamErrorData());
        }
        
        // Add context params
        metadataBuilder.putAllContextParams(pipeStream.getContextParamsMap());
        
        requestBuilder.setMetadata(metadataBuilder.build());
        
        return requestBuilder.build();
    }
    
    private Map<String, String> extractConfigParams(Map<String, Object> stepConfig) {
        Map<String, String> params = new HashMap<>();
        if (stepConfig != null) {
            stepConfig.forEach((key, value) -> {
                if (value != null) {
                    params.put(key, value.toString());
                }
            });
        }
        return params;
    }
}