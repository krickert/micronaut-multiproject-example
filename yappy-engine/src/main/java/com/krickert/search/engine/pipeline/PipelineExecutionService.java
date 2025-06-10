package com.krickert.search.engine.pipeline;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for executing pipelines and managing their lifecycle.
 */
public interface PipelineExecutionService {
    
    /**
     * Creates or updates a pipeline configuration.
     * @param pipelineId the unique identifier for the pipeline
     * @param config the pipeline configuration
     * @return Mono that completes with true if successful
     */
    Mono<Boolean> createOrUpdatePipeline(String pipelineId, PipelineConfig config);
    
    /**
     * Removes a pipeline from the system.
     * @param pipelineId the pipeline to remove
     * @return Mono that completes when pipeline is removed
     */
    Mono<Void> removePipeline(String pipelineId);
    
    /**
     * Checks if a pipeline is ready to process documents.
     * @param pipelineId the pipeline to check
     * @return Mono with true if pipeline is ready, false otherwise
     */
    Mono<Boolean> isPipelineReady(String pipelineId);
    
    /**
     * Process a single document through a pipeline.
     * @param pipelineId the pipeline to use
     * @param document the document to process
     * @return Mono with the processed document
     */
    Mono<PipeDoc> processDocument(String pipelineId, PipeDoc document);
    
    /**
     * Process a stream through a pipeline.
     * @param pipelineId the pipeline to use
     * @param stream the stream to process
     * @return Mono with the processed stream
     */
    Mono<PipeStream> processStream(String pipelineId, PipeStream stream);
    
    /**
     * Process multiple documents through a pipeline.
     * @param pipelineId the pipeline to use
     * @param documents flux of documents to process
     * @return Flux of processed documents
     */
    Flux<PipeDoc> processDocuments(String pipelineId, Flux<PipeDoc> documents);
    
    /**
     * Get statistics for a pipeline.
     * @param pipelineId the pipeline ID
     * @return pipeline execution statistics
     */
    Mono<PipelineStatistics> getPipelineStatistics(String pipelineId);
    
    /**
     * Lists all active pipelines.
     * @return Flux of pipeline IDs
     */
    Flux<String> listActivePipelines();
}