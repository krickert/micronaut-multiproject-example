package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.serde.annotation.SerdeImport;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin API for managing pipeline configurations.
 * This controller provides CRUD operations for pipelines within a cluster.
 */
@SerdeImport(PipelineStepConfig.class)
@SerdeImport(PipelineConfig.class)
@SerdeImport(PipelineClusterConfig.class)
@SerdeImport(PipelineGraphConfig.class)
@SerdeImport(KafkaInputDefinition.class)
@SerdeImport(GrpcTransportConfig.class)
@SerdeImport(KafkaTransportConfig.class)
@SerdeImport(TransportType.class)
@SerdeImport(StepType.class)
@SerdeImport(PipelineStepConfig.ProcessorInfo.class)
@SerdeImport(PipelineStepConfig.OutputTarget.class)
@SerdeImport(PipelineStepConfig.JsonConfigOptions.class)
@Validated
@Controller("/api/admin/pipelines")
@Tag(name = "Admin Pipeline Management", description = "Administrative endpoints for managing pipeline configurations")
public class AdminPipelineController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminPipelineController.class);

    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final String defaultClusterName;

    @Inject
    public AdminPipelineController(
            ConsulBusinessOperationsService consulBusinessOperationsService,
            @Value("${app.config.cluster-name:yappy-cluster}") String defaultClusterName) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.defaultClusterName = defaultClusterName;
    }

    /**
     * List all pipelines in the specified cluster
     */
    @Get("{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List pipelines", 
              description = "Returns all pipeline configurations from the specified cluster")
    @ApiResponse(responseCode = "200", description = "List of pipelines",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = PipelineListResponse.class)))
    @ApiResponse(responseCode = "404", description = "No cluster configuration found")
    public Mono<HttpResponse<PipelineListResponse>> listPipelines(
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        LOG.debug("Listing pipelines for cluster: {}", targetCluster);
        
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .map(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        LOG.warn("No cluster configuration found for: {}", targetCluster);
                        return HttpResponse.<PipelineListResponse>notFound();
                    }
                    
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineConfig> pipelines = clusterConfig.pipelineGraphConfig().pipelines();
                    
                    List<PipelineSummary> summaries = pipelines.entrySet().stream()
                            .map(entry -> new PipelineSummary(
                                    entry.getKey(),
                                    entry.getValue().pipelineSteps().size(),
                                    extractInputSteps(entry.getValue()),
                                    extractOutputSteps(entry.getValue()),
                                    entry.getKey().equals(clusterConfig.defaultPipelineName())
                            ))
                            .collect(Collectors.toList());
                    
                    return HttpResponse.ok(new PipelineListResponse(
                            clusterConfig.clusterName(),
                            summaries,
                            clusterConfig.defaultPipelineName()
                    ));
                })
                .<HttpResponse<PipelineListResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error listing pipelines for cluster: {}", targetCluster, e);
                    return Mono.just(HttpResponse.<PipelineListResponse>serverError());
                });
    }

    /**
     * Get a specific pipeline configuration
     */
    @Get("/{pipelineName}{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get pipeline configuration", 
              description = "Returns the configuration of a specific pipeline")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pipeline configuration found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PipelineDetailResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pipeline not found")
    })
    public Mono<HttpResponse<PipelineDetailResponse>> getPipeline(
            @Parameter(description = "Pipeline name", required = true)
            @PathVariable String pipelineName,
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        LOG.debug("Getting pipeline '{}' from cluster: {}", pipelineName, targetCluster);
        
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .map(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return HttpResponse.<PipelineDetailResponse>notFound();
                    }
                    
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    PipelineConfig pipeline = clusterConfig.pipelineGraphConfig()
                            .pipelines()
                            .get(pipelineName);
                    
                    if (pipeline == null) {
                        LOG.warn("Pipeline '{}' not found in cluster: {}", pipelineName, targetCluster);
                        return HttpResponse.<PipelineDetailResponse>notFound();
                    }
                    
                    return HttpResponse.ok(new PipelineDetailResponse(
                            pipelineName,
                            pipeline,
                            pipelineName.equals(clusterConfig.defaultPipelineName())
                    ));
                })
                .<HttpResponse<PipelineDetailResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error getting pipeline '{}' from cluster: {}", pipelineName, targetCluster, e);
                    return Mono.just(HttpResponse.<PipelineDetailResponse>serverError());
                });
    }

    /**
     * Create a new pipeline
     */
    @Post("{?clusterName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create pipeline", 
              description = "Creates a new pipeline configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pipeline created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PipelineOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "409", description = "Pipeline already exists"),
            @ApiResponse(responseCode = "500", description = "Error creating pipeline")
    })
    public Mono<HttpResponse<PipelineOperationResponse>> createPipeline(
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName,
            @Body @Valid CreatePipelineRequest request) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        LOG.info("Creating pipeline '{}' in cluster: {}", request.getPipelineName(), targetCluster);
        LOG.debug("CreatePipelineRequest: pipelineName={}, setAsDefault={}, pipelineSteps={}", 
                 request.getPipelineName(), request.isSetAsDefault(), 
                 request.getPipelineSteps() != null ? request.getPipelineSteps().size() : "null");
        
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>serverError(
                                new PipelineOperationResponse(
                                        false,
                                        "No cluster configuration found",
                                        request.getPipelineName()
                                )));
                    }
                    
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineConfig> currentPipelines = new HashMap<>(
                            clusterConfig.pipelineGraphConfig().pipelines()
                    );
                    
                    // Check if pipeline already exists
                    if (currentPipelines.containsKey(request.getPipelineName())) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>status(HttpStatus.CONFLICT)
                                .body(new PipelineOperationResponse(
                                        false,
                                        "Pipeline already exists",
                                        request.getPipelineName()
                                )));
                    }
                    
                    // Create new pipeline
                    PipelineConfig newPipeline = PipelineConfig.builder()
                            .name(request.getPipelineName())
                            .pipelineSteps(request.getPipelineSteps() != null ? 
                                    request.getPipelineSteps() : new HashMap<>())
                            .build();
                    
                    currentPipelines.put(request.getPipelineName(), newPipeline);
                    
                    // Create updated cluster config
                    PipelineGraphConfig updatedGraphConfig = PipelineGraphConfig.builder()
                            .pipelines(currentPipelines)
                            .build();
                    
                    PipelineClusterConfig updatedConfig = PipelineClusterConfig.builder()
                            .clusterName(clusterConfig.clusterName())
                            .pipelineGraphConfig(updatedGraphConfig)
                            .pipelineModuleMap(clusterConfig.pipelineModuleMap())
                            .defaultPipelineName(request.isSetAsDefault() ? 
                                    request.getPipelineName() : clusterConfig.defaultPipelineName())
                            .allowedKafkaTopics(clusterConfig.allowedKafkaTopics())
                            .allowedGrpcServices(clusterConfig.allowedGrpcServices())
                            .build();
                    
                    // Store updated configuration
                    return consulBusinessOperationsService
                            .storeClusterConfiguration(targetCluster, updatedConfig)
                            .map(stored -> {
                                if (Boolean.TRUE.equals(stored)) {
                                    LOG.info("Successfully created pipeline: {}", request.getPipelineName());
                                    return HttpResponse.<PipelineOperationResponse>created(
                                            new PipelineOperationResponse(
                                                    true,
                                                    "Pipeline created successfully",
                                                    request.getPipelineName()
                                            ));
                                } else {
                                    return HttpResponse.<PipelineOperationResponse>serverError(
                                            new PipelineOperationResponse(
                                                    false,
                                                    "Failed to store configuration",
                                                    request.getPipelineName()
                                            ));
                                }
                            });
                })
                .<HttpResponse<PipelineOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error creating pipeline: {}", request.getPipelineName(), e);
                    String errorMessage = "Error: " + e.getMessage();
                    if (e.getCause() != null) {
                        errorMessage += " (cause: " + e.getCause().getMessage() + ")";
                    }
                    return Mono.just(HttpResponse.<PipelineOperationResponse>serverError(
                            new PipelineOperationResponse(
                                    false,
                                    errorMessage,
                                    request.getPipelineName()
                            )));
                });
    }

    /**
     * Update an existing pipeline
     */
    @Put("/{pipelineName}{?clusterName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update pipeline", 
              description = "Updates an existing pipeline configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pipeline updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PipelineOperationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pipeline not found"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Error updating pipeline")
    })
    public Mono<HttpResponse<PipelineOperationResponse>> updatePipeline(
            @Parameter(description = "Pipeline name", required = true)
            @PathVariable String pipelineName,
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName,
            @Body @Valid UpdatePipelineRequest request) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        LOG.info("Updating pipeline '{}' in cluster: {}", pipelineName, targetCluster);
        
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>notFound());
                    }
                    
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineConfig> currentPipelines = new HashMap<>(
                            clusterConfig.pipelineGraphConfig().pipelines()
                    );
                    
                    // Check if pipeline exists
                    if (!currentPipelines.containsKey(pipelineName)) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>notFound());
                    }
                    
                    // Update pipeline
                    PipelineConfig updatedPipeline = PipelineConfig.builder()
                            .name(pipelineName)
                            .pipelineSteps(request.getPipelineSteps())
                            .build();
                    
                    currentPipelines.put(pipelineName, updatedPipeline);
                    
                    // Handle default pipeline update
                    String newDefaultPipeline = clusterConfig.defaultPipelineName();
                    if (request.getSetAsDefault() != null) {
                        if (request.getSetAsDefault()) {
                            newDefaultPipeline = pipelineName;
                        } else if (pipelineName.equals(clusterConfig.defaultPipelineName())) {
                            // If unsetting as default, we need a new default
                            // Use the first available pipeline or null
                            newDefaultPipeline = currentPipelines.keySet().stream()
                                    .filter(name -> !name.equals(pipelineName))
                                    .findFirst()
                                    .orElse(null);
                        }
                    }
                    
                    // Create updated cluster config
                    PipelineGraphConfig updatedGraphConfig = PipelineGraphConfig.builder()
                            .pipelines(currentPipelines)
                            .build();
                    
                    PipelineClusterConfig updatedConfig = PipelineClusterConfig.builder()
                            .clusterName(clusterConfig.clusterName())
                            .pipelineGraphConfig(updatedGraphConfig)
                            .pipelineModuleMap(clusterConfig.pipelineModuleMap())
                            .defaultPipelineName(newDefaultPipeline)
                            .allowedKafkaTopics(clusterConfig.allowedKafkaTopics())
                            .allowedGrpcServices(clusterConfig.allowedGrpcServices())
                            .build();
                    
                    // Store updated configuration
                    return consulBusinessOperationsService
                            .storeClusterConfiguration(targetCluster, updatedConfig)
                            .map(stored -> {
                                if (Boolean.TRUE.equals(stored)) {
                                    LOG.info("Successfully updated pipeline: {}", pipelineName);
                                    return HttpResponse.ok(new PipelineOperationResponse(
                                            true,
                                            "Pipeline updated successfully",
                                            pipelineName
                                    ));
                                } else {
                                    return HttpResponse.<PipelineOperationResponse>serverError(
                                            new PipelineOperationResponse(
                                                    false,
                                                    "Failed to store configuration",
                                                    pipelineName
                                            ));
                                }
                            });
                })
                .<HttpResponse<PipelineOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error updating pipeline: {}", pipelineName, e);
                    return Mono.just(HttpResponse.<PipelineOperationResponse>serverError(
                            new PipelineOperationResponse(
                                    false,
                                    "Error: " + e.getMessage(),
                                    pipelineName
                            )));
                });
    }

    /**
     * Delete a pipeline
     */
    @Delete("/{pipelineName}{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Delete pipeline", 
              description = "Deletes a pipeline configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pipeline deleted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PipelineOperationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Pipeline not found"),
            @ApiResponse(responseCode = "409", description = "Cannot delete default pipeline"),
            @ApiResponse(responseCode = "500", description = "Error deleting pipeline")
    })
    public Mono<HttpResponse<PipelineOperationResponse>> deletePipeline(
            @Parameter(description = "Pipeline name", required = true)
            @PathVariable String pipelineName,
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        LOG.info("Deleting pipeline '{}' from cluster: {}", pipelineName, targetCluster);
        
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>notFound());
                    }
                    
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineConfig> currentPipelines = new HashMap<>(
                            clusterConfig.pipelineGraphConfig().pipelines()
                    );
                    
                    // Check if pipeline exists
                    if (!currentPipelines.containsKey(pipelineName)) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>notFound());
                    }
                    
                    // Check if it's the default pipeline
                    if (pipelineName.equals(clusterConfig.defaultPipelineName())) {
                        return Mono.just(HttpResponse.<PipelineOperationResponse>status(HttpStatus.CONFLICT)
                                .body(new PipelineOperationResponse(
                                        false,
                                        "Cannot delete the default pipeline. Set another pipeline as default first.",
                                        pipelineName
                                )));
                    }
                    
                    // Remove the pipeline
                    currentPipelines.remove(pipelineName);
                    
                    // Create updated cluster config
                    PipelineGraphConfig updatedGraphConfig = PipelineGraphConfig.builder()
                            .pipelines(currentPipelines)
                            .build();
                    
                    PipelineClusterConfig updatedConfig = PipelineClusterConfig.builder()
                            .clusterName(clusterConfig.clusterName())
                            .pipelineGraphConfig(updatedGraphConfig)
                            .pipelineModuleMap(clusterConfig.pipelineModuleMap())
                            .defaultPipelineName(clusterConfig.defaultPipelineName())
                            .allowedKafkaTopics(clusterConfig.allowedKafkaTopics())
                            .allowedGrpcServices(clusterConfig.allowedGrpcServices())
                            .build();
                    
                    // Store updated configuration
                    return consulBusinessOperationsService
                            .storeClusterConfiguration(targetCluster, updatedConfig)
                            .map(stored -> {
                                if (Boolean.TRUE.equals(stored)) {
                                    LOG.info("Successfully deleted pipeline: {}", pipelineName);
                                    return HttpResponse.ok(new PipelineOperationResponse(
                                            true,
                                            "Pipeline deleted successfully",
                                            pipelineName
                                    ));
                                } else {
                                    return HttpResponse.<PipelineOperationResponse>serverError(
                                            new PipelineOperationResponse(
                                                    false,
                                                    "Failed to store configuration",
                                                    pipelineName
                                            ));
                                }
                            });
                })
                .<HttpResponse<PipelineOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error deleting pipeline: {}", pipelineName, e);
                    return Mono.just(HttpResponse.<PipelineOperationResponse>serverError(
                            new PipelineOperationResponse(
                                    false,
                                    "Error: " + e.getMessage(),
                                    pipelineName
                            )));
                });
    }

    // Helper methods
    
    private List<String> extractInputSteps(PipelineConfig pipeline) {
        // Find steps that are not targets of any other step (entry points)
        Set<String> allTargets = new HashSet<>();
        pipeline.pipelineSteps().values().forEach(step -> 
            step.outputs().values().forEach(output -> 
                allTargets.add(output.targetStepName())
            )
        );
        
        return pipeline.pipelineSteps().keySet().stream()
                .filter(stepName -> !allTargets.contains(stepName))
                .collect(Collectors.toList());
    }
    
    private List<String> extractOutputSteps(PipelineConfig pipeline) {
        // Find steps with no outputs or only sink outputs
        return pipeline.pipelineSteps().entrySet().stream()
                .filter(entry -> entry.getValue().outputs().isEmpty() || 
                        entry.getValue().stepType() == StepType.SINK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // Request/Response DTOs
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Response containing list of pipelines")
    public static class PipelineListResponse {
        @JsonProperty("clusterName")
        private final String clusterName;
        @JsonProperty("pipelines")
        private final List<PipelineSummary> pipelines;
        @JsonProperty("defaultPipeline")
        private final String defaultPipeline;
        
        @JsonCreator
        public PipelineListResponse(@JsonProperty("clusterName") String clusterName, 
                                  @JsonProperty("pipelines") List<PipelineSummary> pipelines, 
                                  @JsonProperty("defaultPipeline") String defaultPipeline) {
            this.clusterName = clusterName;
            this.pipelines = pipelines;
            this.defaultPipeline = defaultPipeline;
        }
        
        public String getClusterName() { return clusterName; }
        public List<PipelineSummary> getPipelines() { return pipelines; }
        public String getDefaultPipeline() { return defaultPipeline; }
    }
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Pipeline summary information")
    public static class PipelineSummary {
        @JsonProperty("name")
        private String name;
        @JsonProperty("stepCount")
        private int stepCount;
        @JsonProperty("inputSteps")
        private List<String> inputSteps;
        @JsonProperty("outputSteps")
        private List<String> outputSteps;
        @JsonProperty("isDefault")
        private boolean defaultPipeline;
        
        public PipelineSummary() {
            // Default constructor for deserialization
        }
        
        public PipelineSummary(String name, int stepCount, List<String> inputSteps, 
                              List<String> outputSteps, boolean defaultPipeline) {
            this.name = name;
            this.stepCount = stepCount;
            this.inputSteps = inputSteps;
            this.outputSteps = outputSteps;
            this.defaultPipeline = defaultPipeline;
        }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public int getStepCount() { return stepCount; }
        public void setStepCount(int stepCount) { this.stepCount = stepCount; }
        
        public List<String> getInputSteps() { return inputSteps; }
        public void setInputSteps(List<String> inputSteps) { this.inputSteps = inputSteps; }
        
        public List<String> getOutputSteps() { return outputSteps; }
        public void setOutputSteps(List<String> outputSteps) { this.outputSteps = outputSteps; }
        
        public boolean isDefault() { return defaultPipeline; }
        public void setDefault(boolean defaultPipeline) { this.defaultPipeline = defaultPipeline; }
    }
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Detailed pipeline configuration response")
    public static class PipelineDetailResponse {
        private final String name;
        private final PipelineConfig configuration;
        private final boolean isDefault;
        
        public PipelineDetailResponse(String name, PipelineConfig configuration, boolean isDefault) {
            this.name = name;
            this.configuration = configuration;
            this.isDefault = isDefault;
        }
        
        public String getName() { return name; }
        public PipelineConfig getConfiguration() { return configuration; }
        public boolean isDefault() { return isDefault; }
    }
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Request to create a new pipeline")
    public static class CreatePipelineRequest {
        @NotBlank
        @Schema(description = "Pipeline name", required = true)
        @JsonProperty("pipelineName")
        private String pipelineName;
        
        @Schema(description = "Pipeline steps configuration")
        @JsonProperty("pipelineSteps")
        private Map<String, PipelineStepConfig> pipelineSteps;
        
        @Schema(description = "Set as default pipeline")
        @JsonProperty("setAsDefault")
        private boolean setAsDefault;
        
        public CreatePipelineRequest() {}
        
        public String getPipelineName() { return pipelineName; }
        public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }
        
        public Map<String, PipelineStepConfig> getPipelineSteps() { return pipelineSteps; }
        public void setPipelineSteps(Map<String, PipelineStepConfig> pipelineSteps) { 
            this.pipelineSteps = pipelineSteps; 
        }
        
        public boolean isSetAsDefault() { return setAsDefault; }
        public void setSetAsDefault(boolean setAsDefault) { this.setAsDefault = setAsDefault; }
    }
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Request to update an existing pipeline")
    public static class UpdatePipelineRequest {
        @NotNull
        @Schema(description = "Updated pipeline steps configuration", required = true)
        private Map<String, PipelineStepConfig> pipelineSteps;
        
        @Schema(description = "Set as default pipeline (optional)")
        private Boolean setAsDefault;
        
        public UpdatePipelineRequest() {}
        
        public Map<String, PipelineStepConfig> getPipelineSteps() { return pipelineSteps; }
        public void setPipelineSteps(Map<String, PipelineStepConfig> pipelineSteps) { 
            this.pipelineSteps = pipelineSteps; 
        }
        
        public Boolean getSetAsDefault() { return setAsDefault; }
        public void setSetAsDefault(Boolean setAsDefault) { this.setAsDefault = setAsDefault; }
    }
    
    @Serdeable
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Response for pipeline operations")
    public static class PipelineOperationResponse {
        private final boolean success;
        private final String message;
        private final String pipelineName;
        
        public PipelineOperationResponse(boolean success, String message, String pipelineName) {
            this.success = success;
            this.message = message;
            this.pipelineName = pipelineName;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getPipelineName() { return pipelineName; }
    }
}