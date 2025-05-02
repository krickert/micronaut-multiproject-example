// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/api/PipelineController.java
package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.PipelineService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
// No Map, HashMap, LocalDateTime, HttpStatus needed here

@Controller("/api/pipelines")
@Tag(name = "Pipeline Management", description = "API for managing pipeline configurations")
public class PipelineController {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineController.class);

    private final PipelineService pipelineService;

    @Inject
    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
        LOG.info("PipelineController initialized with PipelineService");
    }

    @Operation(summary = "List pipelines", description = "Retrieves a list of names for all configured pipelines")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List retrieved", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = "array", implementation = String.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Get(produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<List<String>>> listPipelines() {
        LOG.info("GET request for all pipeline names");
        return pipelineService.listPipelines()
                .map(HttpResponse::ok);
                // Errors propagate to handlers
    }

    @Operation(summary = "Create pipeline", description = "Creates a new pipeline configuration.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = PipelineConfigDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "409", description = "Conflict"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Object>> createPipeline(
            @Parameter(description = "Request containing the name for the new pipeline", required = true, schema = @Schema(implementation = CreatePipelineRequest.class))
            @Body CreatePipelineRequest request) {
        LOG.info("POST request to create a new pipeline");

        if (request == null || request.getName() == null || request.getName().trim().isEmpty()) {
             // Let the IllegalArgumentExceptionHandler handle this
            throw new IllegalArgumentException("Pipeline name cannot be missing or empty");
        }

        return pipelineService.createPipeline(request)
                .map(createdDto -> HttpResponse.created((Object) createdDto));
                // Specific errors (Conflict, IllegalArgument) propagate to handlers
                // Other errors lead to 500 via default handling
    }

    @Operation(summary = "Get pipeline configuration", description = "Retrieves the complete configuration for a specific pipeline")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Retrieved", content = @Content(schema = @Schema(implementation = PipelineConfigDto.class))),
            @ApiResponse(responseCode = "404", description = "Not Found"), // Handled by PipelineNotFoundExceptionHandler
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Get(value = "/{pipelineName}", produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Object>> getPipeline(
            @Parameter(description = "Name of the pipeline to retrieve", required = true)
            @PathVariable String pipelineName) {
        LOG.info("GET request for pipeline: {}", pipelineName);

        // pipelineService.getPipeline returns Mono<PipelineConfigDto> or signals PipelineNotFoundException
        return pipelineService.getPipeline(pipelineName)
                .map(dto -> HttpResponse.ok((Object) dto));
                // If PipelineNotFoundException is signaled, the handler will catch it.
                // Other errors will lead to 500 via default handling.
    }


    @Operation(summary = "Delete pipeline", description = "Deletes the entire configuration for the specified pipeline")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "Not Found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Delete("/{pipelineName}")
    public Mono<HttpResponse<Void>> deletePipeline( // Changed return type for clarity on 204
            @Parameter(description = "Name of the pipeline to delete", required = true)
            @PathVariable String pipelineName) {
        LOG.info("DELETE request for pipeline: {}", pipelineName);

        // pipelineService.deletePipeline returns Mono<Boolean> or signals PipelineNotFoundException/RuntimeException
        return pipelineService.deletePipeline(pipelineName)
                .flatMap(success -> success ? Mono.just(HttpResponse.noContent())
                                           : Mono.error(new RuntimeException("Deletion reported failure"))); // Should ideally not happen if errors are signaled
                // PipelineNotFoundException propagates to handler -> 404
                // Other RuntimeExceptions propagate to default handler -> 500
    }

    @Operation(summary = "Update pipeline configuration", description = "Replaces the entire configuration for a given pipeline")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated", content = @Content(schema = @Schema(implementation = PipelineConfigDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "404", description = "Not Found"),
            @ApiResponse(responseCode = "409", description = "Conflict"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @Put(value = "/{pipelineName}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Object>> updatePipeline(
            @Parameter(description = "Name of the pipeline to update", required = true)
            @PathVariable String pipelineName,
            @Parameter(description = "Updated pipeline configuration", required = true, schema = @Schema(implementation = PipelineConfigDto.class))
            @Body PipelineConfigDto updatedPipeline) {
        LOG.info("PUT request to update pipeline: {}", pipelineName);

        if (updatedPipeline == null) {
             // Let the IllegalArgumentExceptionHandler handle this
            throw new IllegalArgumentException("Request body cannot be null");
        }
        // Potential validation on DTO can happen via annotations or service layer

        // Call service, map success. Let errors propagate to handlers.
        return pipelineService.updatePipeline(pipelineName, updatedPipeline)
                .map(dto -> HttpResponse.ok((Object) dto));
                // Specific errors (NotFound, Conflict, IllegalArgument) propagate to handlers
                // Other RuntimeExceptions propagate to default handler -> 500
    }
}