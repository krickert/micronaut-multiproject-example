package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for connecting to and communicating with processing modules.
 * This abstraction allows different transport mechanisms (gRPC, REST, etc.)
 */
public interface ModuleConnector {
    
    /**
     * Process a single document through a module.
     * 
     * @param moduleId The module to connect to
     * @param document The document to process
     * @param config Module-specific configuration
     * @return The processed document
     */
    Mono<PipeDoc> processDocument(String moduleId, PipeDoc document, ModuleConfig config);
    
    /**
     * Process a stream of documents through a module.
     * 
     * @param moduleId The module to connect to
     * @param documents The documents to process
     * @param config Module-specific configuration
     * @return Flux of processed documents
     */
    Flux<PipeDoc> processDocumentStream(String moduleId, Flux<PipeDoc> documents, ModuleConfig config);
    
    /**
     * Process a PipeStream through a module.
     * 
     * @param moduleId The module to connect to
     * @param stream The stream to process
     * @param config Module-specific configuration
     * @return The processed stream
     */
    Mono<PipeStream> processPipeStream(String moduleId, PipeStream stream, ModuleConfig config);
    
    /**
     * Check if a module is available and healthy.
     * 
     * @param moduleId The module ID
     * @return true if the module is healthy
     */
    Mono<Boolean> isModuleHealthy(String moduleId);
    
    /**
     * Get module capabilities.
     * 
     * @param moduleId The module ID
     * @return Module capabilities
     */
    Mono<ModuleCapabilities> getModuleCapabilities(String moduleId);
    
    /**
     * Module-specific configuration passed to the module during processing.
     */
    record ModuleConfig(
            String stepId,
            String pipelineId,
            java.util.Map<String, Object> parameters
    ) {}
    
    /**
     * Module capabilities information.
     */
    record ModuleCapabilities(
            boolean supportsStreaming,
            boolean supportsBatch,
            int maxBatchSize,
            java.util.List<String> supportedContentTypes,
            java.util.Map<String, String> configSchema
    ) {}
}