package com.krickert.search.pipeline.api.service;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.pipeline.api.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service interface for pipeline management operations.
 * This is the main service for creating, updating, and managing pipelines.
 */
public interface PipelineService {
    
    /**
     * List all pipelines in a cluster.
     */
    Flux<PipelineSummary> listPipelines(String cluster);
    
    /**
     * Get detailed information about a specific pipeline.
     */
    Mono<PipelineView> getPipeline(String cluster, String pipelineId);
    
    /**
     * Get raw pipeline configuration.
     */
    Mono<PipelineConfig> getPipelineConfig(String cluster, String pipelineId);
    
    /**
     * Create a new pipeline.
     */
    Mono<PipelineView> createPipeline(String cluster, CreatePipelineRequest request);
    
    /**
     * Update an existing pipeline.
     */
    Mono<PipelineView> updatePipeline(String cluster, String pipelineId, UpdatePipelineRequest request);
    
    /**
     * Delete a pipeline.
     */
    Mono<Void> deletePipeline(String cluster, String pipelineId);
    
    /**
     * Test a pipeline with sample data.
     */
    Mono<TestPipelineResponse> testPipeline(String cluster, String pipelineId, TestPipelineRequest request);
    
    /**
     * Get runtime status of a pipeline.
     */
    Mono<PipelineStatus> getPipelineStatus(String cluster, String pipelineId);
    
    /**
     * Validate a pipeline configuration without creating it.
     */
    Mono<ValidationResponse> validatePipeline(CreatePipelineRequest request);
    
    /**
     * Get available pipeline templates.
     */
    Flux<PipelineTemplate> getTemplates();
    
    /**
     * Create a pipeline from a template.
     */
    Mono<PipelineView> createFromTemplate(String cluster, CreateFromTemplateRequest request);
    
    /**
     * Get the full cluster configuration.
     */
    Mono<PipelineClusterConfig> getClusterConfig(String cluster);
    
    // ========================================
    // Pipeline Step Management
    // ========================================
    
    /**
     * Get details of a specific step in a pipeline.
     */
    Mono<PipelineStepView> getPipelineStep(String cluster, String pipelineId, String stepId);
    
    /**
     * Add a new step to an existing pipeline.
     * This will automatically create associated Kafka topics.
     */
    Mono<PipelineStepView> addPipelineStep(String cluster, String pipelineId, AddPipelineStepRequest request);
    
    /**
     * Update an existing step in a pipeline.
     */
    Mono<PipelineStepView> updatePipelineStep(String cluster, String pipelineId, String stepId, UpdatePipelineStepRequest request);
    
    /**
     * Remove a step from a pipeline.
     * This will not delete associated Kafka topics.
     */
    Mono<Void> removePipelineStep(String cluster, String pipelineId, String stepId);
}