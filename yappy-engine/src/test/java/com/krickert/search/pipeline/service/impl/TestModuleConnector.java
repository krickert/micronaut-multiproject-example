package com.krickert.search.pipeline.service.impl;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.ModuleConnector;
import com.krickert.search.pipeline.service.ModuleRegistry;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Test implementation of ModuleConnector that simulates module processing.
 */
public class TestModuleConnector implements ModuleConnector {
    
    private final ModuleRegistry moduleRegistry;
    private final Map<String, Function<PipeDoc, PipeDoc>> processors = new ConcurrentHashMap<>();
    private final Map<String, ModuleCapabilities> capabilities = new ConcurrentHashMap<>();
    
    public TestModuleConnector(ModuleRegistry moduleRegistry) {
        this.moduleRegistry = moduleRegistry;
        
        // Register some default test processors
        registerTestProcessor("echo-processor", doc -> {
            // Build custom_data with metadata
            Struct.Builder customData = doc.hasCustomData() ? 
                    doc.getCustomData().toBuilder() : 
                    Struct.newBuilder();
            customData.putFields("processed_by", 
                    Value.newBuilder().setStringValue("echo-processor").build());
            
            return PipeDoc.newBuilder(doc)
                    .setBody("ECHO: " + doc.getBody())
                    .setCustomData(customData.build())
                    .build();
        });
        
        registerTestProcessor("uppercase-processor", doc -> {
            // Build custom_data with metadata
            Struct.Builder customData = doc.hasCustomData() ? 
                    doc.getCustomData().toBuilder() : 
                    Struct.newBuilder();
            customData.putFields("processed_by", 
                    Value.newBuilder().setStringValue("uppercase-processor").build());
            
            return PipeDoc.newBuilder(doc)
                    .setBody(doc.getBody().toUpperCase())
                    .setCustomData(customData.build())
                    .build();
        });
    }
    
    @Override
    public Mono<PipeDoc> processDocument(String moduleId, PipeDoc document, ModuleConfig config) {
        return moduleRegistry.isRegistered(moduleId)
                .flatMap(registered -> {
                    if (!registered) {
                        return Mono.error(new IllegalArgumentException("Module not registered: " + moduleId));
                    }
                    
                    Function<PipeDoc, PipeDoc> processor = processors.get(moduleId);
                    if (processor == null) {
                        // Default processor just adds metadata
                        processor = doc -> {
                            Struct.Builder customData = doc.hasCustomData() ? 
                                    doc.getCustomData().toBuilder() : 
                                    Struct.newBuilder();
                            customData.putFields("processed_by", 
                                    Value.newBuilder().setStringValue(moduleId).build());
                            customData.putFields("step_id", 
                                    Value.newBuilder().setStringValue(config.stepId()).build());
                            
                            return PipeDoc.newBuilder(doc)
                                    .setCustomData(customData.build())
                                    .build();
                        };
                    }
                    
                    return Mono.just(processor.apply(document));
                });
    }
    
    @Override
    public Flux<PipeDoc> processDocumentStream(String moduleId, Flux<PipeDoc> documents, ModuleConfig config) {
        return documents.flatMap(doc -> processDocument(moduleId, doc, config));
    }
    
    @Override
    public Mono<PipeStream> processPipeStream(String moduleId, PipeStream stream, ModuleConfig config) {
        // Process the document in the stream
        return processDocument(moduleId, stream.getDocument(), config)
                .map(processedDoc -> PipeStream.newBuilder()
                        .setStreamId(stream.getStreamId())
                        .setDocument(processedDoc)
                        .setCurrentPipelineName(stream.getCurrentPipelineName())
                        .setTargetStepName(stream.getTargetStepName())
                        .setCurrentHopNumber(stream.getCurrentHopNumber() + 1)
                        .build());
    }
    
    @Override
    public Mono<Boolean> isModuleHealthy(String moduleId) {
        return moduleRegistry.getModule(moduleId)
                .map(module -> module.healthStatus() == ModuleRegistry.HealthStatus.HEALTHY)
                .defaultIfEmpty(false);
    }
    
    @Override
    public Mono<ModuleCapabilities> getModuleCapabilities(String moduleId) {
        return Mono.justOrEmpty(capabilities.get(moduleId))
                .switchIfEmpty(Mono.just(defaultCapabilities()));
    }
    
    // Test helper methods
    public void registerTestProcessor(String moduleId, Function<PipeDoc, PipeDoc> processor) {
        processors.put(moduleId, processor);
        capabilities.put(moduleId, defaultCapabilities());
    }
    
    public void setModuleCapabilities(String moduleId, ModuleCapabilities caps) {
        capabilities.put(moduleId, caps);
    }
    
    public void simulateModuleFailure(String moduleId) {
        processors.put(moduleId, doc -> {
            throw new RuntimeException("Module " + moduleId + " is failing");
        });
    }
    
    public void clearModuleFailure(String moduleId) {
        processors.remove(moduleId);
    }
    
    private ModuleCapabilities defaultCapabilities() {
        return new ModuleCapabilities(
                true,  // supportsStreaming
                true,  // supportsBatch
                100,   // maxBatchSize
                java.util.List.of("text/plain", "application/json"),
                java.util.Map.of("type", "object")
        );
    }
}