package com.krickert.search.engine.pipeline.impl;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.engine.pipeline.PipelineExecutionService;
import com.krickert.search.engine.pipeline.PipelineStatistics;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of PipelineExecutionService.
 */
@Singleton
public class PipelineExecutionServiceImpl implements PipelineExecutionService {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineExecutionServiceImpl.class);
    
    private final Map<String, PipelineConfig> pipelines = new ConcurrentHashMap<>();
    private final Map<String, PipelineExecutionStats> pipelineStats = new ConcurrentHashMap<>();
    
    @Inject
    public PipelineExecutionServiceImpl() {
        // Constructor for dependency injection
    }
    
    @Override
    public Mono<Boolean> createOrUpdatePipeline(String pipelineId, PipelineConfig config) {
        return Mono.fromCallable(() -> {
            LOG.info("Creating/updating pipeline: {}", pipelineId);
            pipelines.put(pipelineId, config);
            pipelineStats.putIfAbsent(pipelineId, new PipelineExecutionStats(pipelineId));
            return true;
        });
    }
    
    @Override
    public Mono<Void> removePipeline(String pipelineId) {
        return Mono.fromRunnable(() -> {
            LOG.info("Removing pipeline: {}", pipelineId);
            pipelines.remove(pipelineId);
            pipelineStats.remove(pipelineId);
        });
    }
    
    @Override
    public Mono<Boolean> isPipelineReady(String pipelineId) {
        return Mono.fromCallable(() -> pipelines.containsKey(pipelineId));
    }
    
    @Override
    public Mono<PipeDoc> processDocument(String pipelineId, PipeDoc document) {
        return Mono.defer(() -> {
            if (!pipelines.containsKey(pipelineId)) {
                return Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId));
            }
            
            PipelineExecutionStats stats = pipelineStats.get(pipelineId);
            long startTime = System.currentTimeMillis();
            
            return executePipeline(pipelineId, document)
                .doOnSuccess(doc -> {
                    long duration = System.currentTimeMillis() - startTime;
                    stats.recordSuccess(duration);
                })
                .doOnError(error -> {
                    long duration = System.currentTimeMillis() - startTime;
                    stats.recordFailure(duration);
                    LOG.error("Pipeline {} failed to process document: {}", pipelineId, error.getMessage());
                });
        });
    }
    
    @Override
    public Mono<PipeStream> processStream(String pipelineId, PipeStream stream) {
        return Mono.defer(() -> {
            if (!pipelines.containsKey(pipelineId)) {
                return Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId));
            }
            
            // For now, just return the stream as-is
            // In a real implementation, this would process the stream through the pipeline
            return Mono.just(stream);
        });
    }
    
    @Override
    public Flux<PipeDoc> processDocuments(String pipelineId, Flux<PipeDoc> documents) {
        return documents.flatMap(doc -> processDocument(pipelineId, doc));
    }
    
    @Override
    public Mono<PipelineStatistics> getPipelineStatistics(String pipelineId) {
        return Mono.fromCallable(() -> {
            PipelineExecutionStats stats = pipelineStats.get(pipelineId);
            if (stats == null) {
                return null;
            }
            
            return new PipelineStatistics(
                pipelineId,
                stats.totalProcessed.get(),
                stats.successCount.get(),
                stats.failureCount.get(),
                Duration.ofMillis(stats.getAverageProcessingTime()),
                Duration.ofMillis(stats.minProcessingTime.get()),
                Duration.ofMillis(stats.maxProcessingTime.get()),
                stats.lastProcessedTime,
                Map.of() // Step statistics not implemented yet
            );
        });
    }
    
    @Override
    public Flux<String> listActivePipelines() {
        return Flux.fromIterable(pipelines.keySet());
    }
    
    private Mono<PipeDoc> executePipeline(String pipelineId, PipeDoc document) {
        // For now, this is a simple implementation that just returns the document
        // In a real implementation, this would execute all pipeline steps
        return Mono.just(document)
            .delayElement(Duration.ofMillis(10)); // Simulate some processing time
    }
    
    private static class PipelineExecutionStats {
        final String pipelineId;
        final AtomicLong totalProcessed = new AtomicLong();
        final AtomicLong successCount = new AtomicLong();
        final AtomicLong failureCount = new AtomicLong();
        final AtomicLong totalProcessingTime = new AtomicLong();
        final AtomicLong minProcessingTime = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong maxProcessingTime = new AtomicLong(0);
        volatile Instant lastProcessedTime;
        
        PipelineExecutionStats(String pipelineId) {
            this.pipelineId = pipelineId;
        }
        
        void recordSuccess(long processingTime) {
            totalProcessed.incrementAndGet();
            successCount.incrementAndGet();
            totalProcessingTime.addAndGet(processingTime);
            updateMinMax(processingTime);
            lastProcessedTime = Instant.now();
        }
        
        void recordFailure(long processingTime) {
            totalProcessed.incrementAndGet();
            failureCount.incrementAndGet();
            totalProcessingTime.addAndGet(processingTime);
            updateMinMax(processingTime);
            lastProcessedTime = Instant.now();
        }
        
        void updateMinMax(long processingTime) {
            minProcessingTime.updateAndGet(current -> Math.min(current, processingTime));
            maxProcessingTime.updateAndGet(current -> Math.max(current, processingTime));
        }
        
        long getAverageProcessingTime() {
            long total = totalProcessed.get();
            return total > 0 ? totalProcessingTime.get() / total : 0;
        }
    }
}