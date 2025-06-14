package com.krickert.search.pipeline.api.controller;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.PipelineService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Pipeline management API - the heart of YAPPY configuration.
 * 
 * Simple, intuitive endpoints for managing data processing pipelines.
 */
@Controller("/api/v1/pipelines")
@Tag(name = "Pipelines", description = "Create and manage data processing pipelines")
@ExecuteOn(TaskExecutors.IO)
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @Get
    @Operation(summary = "List all pipelines", description = "Get a list of all configured pipelines")
    @ApiResponse(responseCode = "200", description = "List of pipelines retrieved successfully")
    public Flux<PipelineSummary> listPipelines(
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.listPipelines(cluster);
    }

    @Get("/{id}")
    @Operation(summary = "Get pipeline details", description = "Get complete configuration for a specific pipeline")
    @ApiResponse(responseCode = "200", description = "Pipeline retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<PipelineView> getPipeline(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.getPipeline(cluster, id);
    }

    @Post
    @Status(HttpStatus.CREATED)
    @Operation(summary = "Create a pipeline", description = "Create a new data processing pipeline")
    @ApiResponse(responseCode = "201", description = "Pipeline created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid pipeline configuration")
    @ApiResponse(responseCode = "409", description = "Pipeline already exists")
    public Mono<PipelineView> createPipeline(
            @Body @Valid CreatePipelineRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.createPipeline(cluster, request);
    }

    @Put("/{id}")
    @Operation(summary = "Update a pipeline", description = "Update an existing pipeline configuration")
    @ApiResponse(responseCode = "200", description = "Pipeline updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid pipeline configuration")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<PipelineView> updatePipeline(
            @PathVariable @NotBlank String id,
            @Body @Valid UpdatePipelineRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.updatePipeline(cluster, id, request);
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a pipeline", description = "Remove a pipeline from the cluster")
    @ApiResponse(responseCode = "204", description = "Pipeline deleted successfully")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<Void> deletePipeline(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.deletePipeline(cluster, id);
    }

    @Post("/{id}/test")
    @Operation(summary = "Test a pipeline", description = "Send test data through a pipeline to verify it works")
    @ApiResponse(responseCode = "200", description = "Pipeline test completed")
    @ApiResponse(responseCode = "400", description = "Invalid test request")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<TestPipelineResponse> testPipeline(
            @PathVariable @NotBlank String id,
            @Body @Valid TestPipelineRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.testPipeline(cluster, id, request);
    }

    @Get("/{id}/status")
    @Operation(summary = "Get pipeline status", description = "Get runtime status and metrics for a pipeline")
    @ApiResponse(responseCode = "200", description = "Pipeline status retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<PipelineStatus> getPipelineStatus(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.getPipelineStatus(cluster, id);
    }

    @Post("/validate")
    @Operation(summary = "Validate pipeline configuration", description = "Check if a pipeline configuration is valid without creating it")
    @ApiResponse(responseCode = "200", description = "Validation completed")
    @ApiResponse(responseCode = "400", description = "Invalid pipeline configuration")
    public Mono<ValidationResponse> validatePipeline(@Body @Valid CreatePipelineRequest request) {
        return pipelineService.validatePipeline(request);
    }

    @Get("/templates")
    @Operation(summary = "Get pipeline templates", description = "Get pre-built pipeline templates for common use cases")
    @ApiResponse(responseCode = "200", description = "Templates retrieved successfully")
    public Flux<PipelineTemplate> getTemplates() {
        return pipelineService.getTemplates();
    }

    @Post("/from-template")
    @Status(HttpStatus.CREATED)
    @Operation(summary = "Create from template", description = "Create a new pipeline from a template")
    @ApiResponse(responseCode = "201", description = "Pipeline created from template successfully")
    @ApiResponse(responseCode = "400", description = "Invalid template request")
    @ApiResponse(responseCode = "404", description = "Template not found")
    public Mono<PipelineView> createFromTemplate(
            @Body @Valid CreateFromTemplateRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.createFromTemplate(cluster, request);
    }
    
    @Get("/{id}/config")
    @Operation(summary = "Get raw pipeline configuration", description = "Get the raw PipelineConfig domain model")
    @ApiResponse(responseCode = "200", description = "Pipeline config retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Pipeline not found")
    public Mono<PipelineConfig> getPipelineConfig(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.getPipelineConfig(cluster, id)
                .switchIfEmpty(Mono.error(new io.micronaut.http.exceptions.HttpStatusException(HttpStatus.NOT_FOUND, "Pipeline not found")));
    }
    
    @Get("/cluster/{cluster}/config")
    @Operation(summary = "Get cluster configuration", description = "Get the full PipelineClusterConfig for a cluster")
    @ApiResponse(responseCode = "200", description = "Cluster config retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Cluster not found")
    public Mono<PipelineClusterConfig> getClusterConfig(@PathVariable @NotBlank String cluster) {
        return pipelineService.getClusterConfig(cluster)
                .switchIfEmpty(Mono.error(new io.micronaut.http.exceptions.HttpStatusException(HttpStatus.NOT_FOUND, "Cluster not found")));
    }
}