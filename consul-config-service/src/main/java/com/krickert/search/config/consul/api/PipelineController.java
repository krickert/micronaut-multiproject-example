package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.event.ConfigChangeNotifier;
import com.krickert.search.config.consul.exception.PipelineVersionConflictException;
import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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

import java.time.LocalDateTime;
import java.util.*;

/**
 * REST controller for managing pipeline configurations.
 * Provides endpoints for creating, reading, updating, and deleting pipeline configurations.
 */
@Controller("/api/pipelines")
@Tag(name = "Pipeline Management", description = "API for managing pipeline configurations")
public class PipelineController {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineController.class);

    private final PipelineConfig pipelineConfig;
    private final ConsulKvService consulKvService;
    private final ConfigChangeNotifier configChangeNotifier;

    /**
     * Creates a new PipelineController with the specified services.
     *
     * @param pipelineConfig the pipeline configuration service
     * @param consulKvService the service for interacting with Consul KV store
     * @param configChangeNotifier the notifier for configuration changes
     */
    @Inject
    public PipelineController(
            PipelineConfig pipelineConfig,
            ConsulKvService consulKvService,
            ConfigChangeNotifier configChangeNotifier) {
        this.pipelineConfig = pipelineConfig;
        this.consulKvService = consulKvService;
        this.configChangeNotifier = configChangeNotifier;
        LOG.info("PipelineController initialized");
    }

    /**
     * Lists all pipeline names.
     *
     * @return a list of pipeline names
     */
    @Operation(
            summary = "List pipelines",
            description = "Retrieves a list of names for all configured pipelines"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of pipeline names retrieved successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, 
                            schema = @Schema(type = "array", implementation = String.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error communicating with Consul or processing the request"
            )
    })
    @Get(produces = MediaType.APPLICATION_JSON)
    public HttpResponse<List<String>> listPipelines() {
        LOG.info("GET request for all pipeline names");

        try {
            // Get all pipeline names from Consul KV store
            String configPrefix = consulKvService.getFullPath("pipeline.configs.");
            List<String> keys = consulKvService.getKeysWithPrefix(configPrefix).block();
            Set<String> pipelineNames = new HashSet<>();

            if (keys != null && !keys.isEmpty()) {
                for (String key : keys) {
                    // Extract pipeline name from key
                    // Format: prefix/pipeline.configs.{pipelineName}.{property}
                    String[] parts = key.split("\\.");
                    if (parts.length >= 3) {
                        String pipelineName = parts[2];
                        // Check if this is a valid pipeline by looking for its version
                        String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
                        Optional<String> versionOpt = consulKvService.getValue(versionKey).block();
                        if (versionOpt != null && versionOpt.isPresent()) {
                            pipelineNames.add(pipelineName);
                        }
                    }
                }
            }

            // Also include pipelines from the in-memory cache
            Map<String, PipelineConfigDto> pipelines = pipelineConfig.getPipelines();
            pipelineNames.addAll(pipelines.keySet());

            List<String> sortedPipelineNames = new ArrayList<>(pipelineNames);
            Collections.sort(sortedPipelineNames);

            LOG.debug("Found {} pipelines", sortedPipelineNames.size());
            return HttpResponse.ok(sortedPipelineNames);
        } catch (Exception e) {
            LOG.error("Error retrieving pipeline names", e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Creates a new pipeline.
     *
     * @param request the request containing the pipeline name
     * @return the created pipeline configuration
     */
    @Operation(
            summary = "Create pipeline",
            description = "Creates a new pipeline configuration with the given name. Initializes version to 1 and sets the creation/update timestamp. Services map will be empty initially."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Pipeline created successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, 
                            schema = @Schema(implementation = PipelineConfigDto.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request (missing name, malformed JSON, or invalid name)"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Pipeline with the same name already exists"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error communicating with Consul or saving the data"
            )
    })
    @Post(consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Object>> createPipeline(@Body CreatePipelineRequest request) {
        LOG.info("POST request to create a new pipeline");

        // Validate request
        if (request == null || request.getName() == null) {
            LOG.warn("Request missing 'name' field");
            return Mono.just(HttpResponse.badRequest(
                    createErrorResponse(HttpStatus.BAD_REQUEST, "Missing required field: name")));
        }

        String pipelineName = request.getName();

        // Validate pipeline name
        if (pipelineName == null || pipelineName.trim().isEmpty()) {
            LOG.warn("Invalid pipeline name: empty or null");
            return Mono.just(HttpResponse.badRequest(
                    createErrorResponse(HttpStatus.BAD_REQUEST, "Pipeline name cannot be empty")));
        }

        // Check if pipeline already exists
        if (pipelineConfig.getPipeline(pipelineName) != null) {
            LOG.warn("Pipeline already exists: {}", pipelineName);
            return Mono.just(HttpResponse.status(HttpStatus.CONFLICT)
                    .body(createErrorResponse(HttpStatus.CONFLICT, 
                            "Pipeline with name '" + pipelineName + "' already exists")));
        }

        // Create new pipeline
        PipelineConfigDto newPipeline = new PipelineConfigDto(pipelineName);
        newPipeline.setPipelineVersion(1); // Initialize version to 1
        newPipeline.setPipelineLastUpdated(LocalDateTime.now());

        // Save the pipeline
        return pipelineConfig.addOrUpdatePipeline(newPipeline)
                .<HttpResponse<Object>>map(success -> {
                    if (success) {
                        LOG.info("Successfully created pipeline: {}", pipelineName);
                        // Notify about configuration change
                        String configKey = "pipeline.configs." + pipelineName;
                        configChangeNotifier.notifyConfigChange(consulKvService.getFullPath(configKey));

                        // Return the created pipeline
                        return HttpResponse.created(newPipeline);
                    } else {
                        LOG.error("Failed to create pipeline: {}", pipelineName);
                        return HttpResponse.serverError(
                                createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                        "Failed to save pipeline configuration"));
                    }
                })
                .onErrorResume(e -> {
                    LOG.error("Error creating pipeline: {}", pipelineName, e);
                    return Mono.just(HttpResponse.serverError(
                            createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                    "Error creating pipeline: " + e.getMessage())));
                });
    }

    /**
     * Gets a specific pipeline configuration.
     *
     * @param pipelineName the name of the pipeline to retrieve
     * @return the pipeline configuration
     */
    @Operation(
            summary = "Get pipeline configuration",
            description = "Retrieves the complete configuration for a specific pipeline"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Pipeline configuration retrieved successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, 
                            schema = @Schema(implementation = PipelineConfigDto.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Pipeline not found"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error communicating with Consul or processing the request"
            )
    })
    @Get(value = "/{pipelineName}", produces = MediaType.APPLICATION_JSON)
    public HttpResponse<Object> getPipeline(
            @Parameter(description = "Name of the pipeline to retrieve", required = true) 
            @PathVariable String pipelineName) {
        LOG.info("GET request for pipeline: {}", pipelineName);

        try {
            // Try to get the pipeline from the in-memory cache
            PipelineConfigDto pipeline = pipelineConfig.getPipeline(pipelineName);

            // If not found, try to load it from Consul
            if (pipeline == null) {
                // Check if the pipeline exists in Consul by looking for its version
                String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
                Optional<String> versionOpt = consulKvService.getValue(versionKey).block();

                if (versionOpt != null && versionOpt.isPresent()) {
                    // Pipeline exists in Consul, load it
                    String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");
                    Optional<String> lastUpdatedOpt = consulKvService.getValue(lastUpdatedKey).block();

                    if (lastUpdatedOpt != null && lastUpdatedOpt.isPresent()) {
                        // Create a new pipeline with the version and last updated timestamp
                        pipeline = new PipelineConfigDto(pipelineName);
                        pipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                        pipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));

                        // Load services for this pipeline
                        pipelineConfig.loadServicesFromConsul(pipeline);

                        // Add the pipeline to the in-memory cache
                        pipelineConfig.getPipelines().put(pipelineName, pipeline);
                        LOG.info("Loaded pipeline from Consul: {}", pipelineName);
                    }
                }
            }

            if (pipeline == null) {
                LOG.warn("Pipeline not found: {}", pipelineName);
                return HttpResponse.notFound(
                        createErrorResponse(HttpStatus.NOT_FOUND, 
                                "Pipeline not found: " + pipelineName));
            }

            LOG.debug("Found pipeline: {}", pipelineName);
            return HttpResponse.ok(pipeline);
        } catch (Exception e) {
            LOG.error("Error retrieving pipeline: {}", pipelineName, e);
            return HttpResponse.serverError(
                    createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                            "Error retrieving pipeline: " + e.getMessage()));
        }
    }

    /**
     * Deletes a pipeline configuration.
     *
     * @param pipelineName the name of the pipeline to delete
     * @return 204 No Content if successful
     */
    @Operation(
            summary = "Delete pipeline",
            description = "Deletes the entire configuration for the specified pipeline"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Pipeline deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Pipeline not found"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error communicating with Consul or deleting the data"
            )
    })
    @Delete("/{pipelineName}")
    public Mono<HttpResponse<?>> deletePipeline(
            @Parameter(description = "Name of the pipeline to delete", required = true) 
            @PathVariable String pipelineName) {
        LOG.info("DELETE request for pipeline: {}", pipelineName);

        try {
            // Try to get the pipeline from the in-memory cache
            PipelineConfigDto pipeline = pipelineConfig.getPipeline(pipelineName);

            // If not found, try to load it from Consul
            if (pipeline == null) {
                // Check if the pipeline exists in Consul by looking for its version
                String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
                Optional<String> versionOpt = consulKvService.getValue(versionKey).block();

                if (versionOpt != null && versionOpt.isPresent()) {
                    // Pipeline exists in Consul, load it
                    String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");
                    Optional<String> lastUpdatedOpt = consulKvService.getValue(lastUpdatedKey).block();

                    if (lastUpdatedOpt != null && lastUpdatedOpt.isPresent()) {
                        // Create a new pipeline with the version and last updated timestamp
                        pipeline = new PipelineConfigDto(pipelineName);
                        pipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                        pipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));

                        // Load services for this pipeline
                        pipelineConfig.loadServicesFromConsul(pipeline);

                        // Add the pipeline to the in-memory cache
                        pipelineConfig.getPipelines().put(pipelineName, pipeline);
                        LOG.info("Loaded pipeline from Consul: {}", pipelineName);
                    }
                }
            }

            if (pipeline == null) {
                LOG.warn("Pipeline not found: {}", pipelineName);
                return Mono.just(HttpResponse.notFound(
                        createErrorResponse(HttpStatus.NOT_FOUND, 
                                "Pipeline not found: " + pipelineName)));
            }

            // Delete the pipeline configuration from Consul
            String configKey = "pipeline.configs." + pipelineName;
            return consulKvService.deleteKeysWithPrefix(consulKvService.getFullPath(configKey))
                    .<HttpResponse<?>>flatMap(success -> {
                        if (success) {
                            LOG.info("Successfully deleted pipeline: {}", pipelineName);
                            // Remove from in-memory cache
                            pipelineConfig.getPipelines().remove(pipelineName);
                            // Notify about configuration change
                            configChangeNotifier.notifyConfigChange(consulKvService.getFullPath(configKey));
                            return Mono.just(HttpResponse.noContent());
                        } else {
                            LOG.error("Failed to delete pipeline: {}", pipelineName);
                            return Mono.just(HttpResponse.serverError(
                                    createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                            "Failed to delete pipeline configuration")));
                        }
                    })
                    .onErrorResume(e -> {
                        LOG.error("Error deleting pipeline: {}", pipelineName, e);
                        return Mono.just(HttpResponse.serverError(
                                createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                        "Error deleting pipeline: " + e.getMessage())));
                    });
        } catch (Exception e) {
            LOG.error("Error processing delete request for pipeline: {}", pipelineName, e);
            return Mono.just(HttpResponse.serverError(
                    createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                            "Error processing delete request: " + e.getMessage())));
        }
    }

    /**
     * Updates a pipeline configuration.
     *
     * @param pipelineName the name of the pipeline to update
     * @param updatedPipeline the updated pipeline configuration
     * @return the updated pipeline configuration
     */
    @Operation(
            summary = "Update pipeline configuration",
            description = "Replaces the entire configuration for a given pipeline, using optimistic locking based on the provided pipelineVersion"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Pipeline configuration updated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, 
                            schema = @Schema(implementation = PipelineConfigDto.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request (malformed JSON, missing required fields, or validation failure)"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Pipeline not found"
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "Pipeline version conflict (optimistic lock failure)"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error communicating with Consul or saving the data"
            )
    })
    @Put(value = "/{pipelineName}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Object>> updatePipeline(
            @Parameter(description = "Name of the pipeline to update", required = true) 
            @PathVariable String pipelineName,
            @Parameter(description = "Updated pipeline configuration", required = true) 
            @Body PipelineConfigDto updatedPipeline) {
        LOG.info("PUT request to update pipeline: {}", pipelineName);

        // Validate request
        if (updatedPipeline == null) {
            LOG.warn("Request body is null");
            return Mono.just(HttpResponse.badRequest(
                    createErrorResponse(HttpStatus.BAD_REQUEST, "Request body cannot be null")));
        }

        try {
            // Try to get the pipeline from the in-memory cache
            PipelineConfigDto existingPipeline = pipelineConfig.getPipeline(pipelineName);

            // If not found, try to load it from Consul
            if (existingPipeline == null) {
                // Check if the pipeline exists in Consul by looking for its version
                String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
                Optional<String> versionOpt = consulKvService.getValue(versionKey).block();

                if (versionOpt != null && versionOpt.isPresent()) {
                    // Pipeline exists in Consul, load it
                    String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");
                    Optional<String> lastUpdatedOpt = consulKvService.getValue(lastUpdatedKey).block();

                    if (lastUpdatedOpt != null && lastUpdatedOpt.isPresent()) {
                        // Create a new pipeline with the version and last updated timestamp
                        existingPipeline = new PipelineConfigDto(pipelineName);
                        existingPipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                        existingPipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));

                        // Load services for this pipeline
                        pipelineConfig.loadServicesFromConsul(existingPipeline);

                        // Add the pipeline to the in-memory cache
                        pipelineConfig.getPipelines().put(pipelineName, existingPipeline);
                        LOG.info("Loaded pipeline from Consul: {}", pipelineName);
                    }
                }
            }

            if (existingPipeline == null) {
                LOG.warn("Pipeline not found: {}", pipelineName);
                return Mono.just(HttpResponse.notFound(
                        createErrorResponse(HttpStatus.NOT_FOUND, 
                                "Pipeline not found: " + pipelineName)));
            }

            // Ensure the pipeline name in the path matches the name in the body
            // If they don't match, use the name from the path
            if (!pipelineName.equals(updatedPipeline.getName())) {
                LOG.warn("Pipeline name in path ({}) doesn't match name in body ({}). Using name from path.", 
                        pipelineName, updatedPipeline.getName());
                updatedPipeline.setName(pipelineName);
            }

            try {
                // Update the pipeline
                return pipelineConfig.addOrUpdatePipeline(updatedPipeline)
                        .<HttpResponse<Object>>map(success -> {
                            if (success) {
                                LOG.info("Successfully updated pipeline: {}", pipelineName);
                                // Notify about configuration change
                                String configKey = "pipeline.configs." + pipelineName;
                                configChangeNotifier.notifyConfigChange(consulKvService.getFullPath(configKey));

                                // Return the updated pipeline with incremented version
                                return HttpResponse.ok(pipelineConfig.getPipeline(pipelineName));
                            } else {
                                LOG.error("Failed to update pipeline: {}", pipelineName);
                                return HttpResponse.serverError(
                                        createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                                "Failed to update pipeline configuration"));
                            }
                        })
                        .onErrorResume(e -> {
                            if (e instanceof PipelineVersionConflictException) {
                                LOG.warn("Pipeline version conflict: {}", e.getMessage());
                                PipelineVersionConflictException conflict = (PipelineVersionConflictException) e;

                                Map<String, Object> errorResponse = new HashMap<>();
                                errorResponse.put("timestamp", LocalDateTime.now().toString());
                                errorResponse.put("status", HttpStatus.CONFLICT.getCode());
                                errorResponse.put("error", "Pipeline Version Conflict");
                                errorResponse.put("message", conflict.getMessage());
                                errorResponse.put("pipelineName", conflict.getPipelineName());
                                errorResponse.put("expectedVersion", conflict.getExpectedVersion());
                                errorResponse.put("actualVersion", conflict.getActualVersion());
                                errorResponse.put("lastUpdated", conflict.getLastUpdated().toString());
                                errorResponse.put("path", "/api/pipelines/" + pipelineName);

                                return Mono.just(HttpResponse.status(HttpStatus.CONFLICT).body(errorResponse));
                            } else {
                                LOG.error("Error updating pipeline: {}", pipelineName, e);
                                return Mono.just(HttpResponse.serverError(
                                        createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                                                "Error updating pipeline: " + e.getMessage())));
                            }
                        });
            } catch (PipelineVersionConflictException e) {
                // Handle version conflict exception
                LOG.warn("Pipeline version conflict: {}", e.getMessage());
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("timestamp", LocalDateTime.now().toString());
                errorResponse.put("status", HttpStatus.CONFLICT.getCode());
                errorResponse.put("error", "Pipeline Version Conflict");
                errorResponse.put("message", e.getMessage());
                errorResponse.put("pipelineName", e.getPipelineName());
                errorResponse.put("expectedVersion", e.getExpectedVersion());
                errorResponse.put("actualVersion", e.getActualVersion());
                errorResponse.put("lastUpdated", e.getLastUpdated().toString());
                errorResponse.put("path", "/api/pipelines/" + pipelineName);

                return Mono.just(HttpResponse.status(HttpStatus.CONFLICT).body(errorResponse));
            }
        } catch (Exception e) {
            LOG.error("Error processing update for pipeline: {}", pipelineName, e);
            return Mono.just(HttpResponse.serverError(
                    createErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
                            "Error processing update: " + e.getMessage())));
        }
    }

    /**
     * Creates a standardized error response.
     *
     * @param status the HTTP status
     * @param message the error message
     * @return a map containing the error details
     */
    private Map<String, Object> createErrorResponse(HttpStatus status, String message) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", status.getCode());
        errorResponse.put("error", status.getReason());
        errorResponse.put("message", message);
        errorResponse.put("path", "/api/pipelines");
        return errorResponse;
    }
}
