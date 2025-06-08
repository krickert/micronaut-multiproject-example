package com.krickert.search.pipeline.service;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for pipeline execution.
 * Handles processing documents through configured pipelines.
 */
public interface PipelineExecutor {
    
    /**
     * Process a single document through a pipeline.
     * 
     * @param pipelineId The pipeline to use
     * @param document The document to process
     * @return The processed document
     */
    Mono<PipeDoc> processDocument(String pipelineId, PipeDoc document);
    
    /**
     * Process a stream of documents through a pipeline.
     * 
     * @param pipelineId The pipeline to use
     * @param documents The documents to process
     * @return Flux of processed documents
     */
    Flux<PipeDoc> processDocuments(String pipelineId, Flux<PipeDoc> documents);
    
    /**
     * Process a PipeStream through a pipeline.
     * 
     * @param stream The stream to process
     * @return The processed stream
     */
    Mono<PipeStream> processPipeStream(PipeStream stream);
    
    /**
     * Check if a pipeline is active and ready.
     * 
     * @param pipelineId The pipeline ID
     * @return true if the pipeline is ready
     */
    Mono<Boolean> isPipelineReady(String pipelineId);
    
    /**
     * Get execution statistics for a pipeline.
     * 
     * @param pipelineId The pipeline ID
     * @return Execution statistics
     */
    Mono<PipelineStats> getPipelineStats(String pipelineId);
    
    /**
     * Pipeline execution statistics.
     */
    record PipelineStats(
            String pipelineId,
            long documentsProcessed,
            long documentsInProgress,
            long errorCount,
            double averageProcessingTimeMs,
            long lastProcessedTimestamp
    ) {}
}