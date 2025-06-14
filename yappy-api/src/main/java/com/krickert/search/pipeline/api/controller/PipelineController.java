package com.krickert.search.pipeline.api.controller;

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
    public Flux<PipelineSummary> listPipelines(
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.listPipelines(cluster);
    }

    @Get("/{id}")
    @Operation(summary = "Get pipeline details", description = "Get complete configuration for a specific pipeline")
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
    public Mono<PipelineView> updatePipeline(
            @PathVariable @NotBlank String id,
            @Body @Valid UpdatePipelineRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.updatePipeline(cluster, id, request);
    }

    @Delete("/{id}")
    @Status(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a pipeline", description = "Remove a pipeline from the cluster")
    public Mono<Void> deletePipeline(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.deletePipeline(cluster, id);
    }

    @Post("/{id}/test")
    @Operation(summary = "Test a pipeline", description = "Send test data through a pipeline to verify it works")
    public Mono<TestPipelineResponse> testPipeline(
            @PathVariable @NotBlank String id,
            @Body @Valid TestPipelineRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.testPipeline(cluster, id, request);
    }

    @Get("/{id}/status")
    @Operation(summary = "Get pipeline status", description = "Get runtime status and metrics for a pipeline")
    public Mono<PipelineStatus> getPipelineStatus(
            @PathVariable @NotBlank String id,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.getPipelineStatus(cluster, id);
    }

    @Post("/validate")
    @Operation(summary = "Validate pipeline configuration", description = "Check if a pipeline configuration is valid without creating it")
    public Mono<ValidationResponse> validatePipeline(@Body @Valid CreatePipelineRequest request) {
        return pipelineService.validatePipeline(request);
    }

    @Get("/templates")
    @Operation(summary = "Get pipeline templates", description = "Get pre-built pipeline templates for common use cases")
    public Flux<PipelineTemplate> getTemplates() {
        return pipelineService.getTemplates();
    }

    @Post("/from-template")
    @Status(HttpStatus.CREATED)
    @Operation(summary = "Create from template", description = "Create a new pipeline from a template")
    public Mono<PipelineView> createFromTemplate(
            @Body @Valid CreateFromTemplateRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return pipelineService.createFromTemplate(cluster, request);
    }
}