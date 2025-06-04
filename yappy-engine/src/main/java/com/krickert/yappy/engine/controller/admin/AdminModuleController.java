package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.yappy.engine.controller.admin.dto.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import reactor.core.publisher.Mono;
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

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin API for managing module definitions in the PipelineModuleMap.
 * This controller manages the configuration of available modules that can be used in pipelines.
 */
@Validated
@Controller("/api/admin/modules")
@Tag(name = "Admin Module Management", description = "Administrative endpoints for managing pipeline module definitions")
@SerdeImport(SchemaReference.class)
public class AdminModuleController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminModuleController.class);

    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final String defaultClusterName;

    @Inject
    public AdminModuleController(
            ConsulBusinessOperationsService consulBusinessOperationsService,
            @Value("${app.config.cluster-name:yappy-cluster}") String defaultClusterName) {
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.defaultClusterName = defaultClusterName;
    }

    /**
     * Get all module definitions from the specified or current cluster configuration
     */
    @Get("/definitions{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List module definitions", 
              description = "Returns all module definitions from the specified cluster's PipelineModuleMap")
    @ApiResponse(responseCode = "200", description = "List of module definitions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ModuleDefinitionsResponse.class)))
    @ApiResponse(responseCode = "404", description = "No cluster configuration found")
    public Mono<HttpResponse<ModuleDefinitionsResponse>> getModuleDefinitions(
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<ModuleDefinitionsResponse>notFound());
                    }
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    PipelineModuleMap moduleMap = clusterConfig.pipelineModuleMap();
                    Map<String, PipelineModuleConfiguration> modules = moduleMap.availableModules();
                    
                    List<ModuleDefinition> definitions = modules.entrySet().stream()
                            .map(entry -> new ModuleDefinition(
                                    entry.getKey(),
                                    entry.getValue().implementationName(),
                                    entry.getValue().customConfigSchemaReference(),
                                    entry.getValue().customConfig()
                            ))
                            .collect(Collectors.toList());
                    
                    return Mono.just(HttpResponse.ok(new ModuleDefinitionsResponse(
                            clusterConfig.clusterName(),
                            definitions
                    )));
                });
    }

    /**
     * Get a specific module definition
     */
    @Get("/definitions/{moduleId}{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get module definition", 
              description = "Returns the definition of a specific module")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module definition found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ModuleDefinition.class))),
            @ApiResponse(responseCode = "404", description = "Module definition not found")
    })
    public Mono<HttpResponse<ModuleDefinition>> getModuleDefinition(
            @Parameter(description = "Module implementation ID", required = true)
            @PathVariable String moduleId,
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<ModuleDefinition>notFound());
                    }
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    PipelineModuleConfiguration moduleConfig = clusterConfig
                            .pipelineModuleMap()
                            .availableModules()
                            .get(moduleId);
                    
                    if (moduleConfig == null) {
                        return Mono.just(HttpResponse.<ModuleDefinition>notFound());
                    }
                    
                    return Mono.just(HttpResponse.ok(new ModuleDefinition(
                            moduleId,
                            moduleConfig.implementationName(),
                            moduleConfig.customConfigSchemaReference(),
                            moduleConfig.customConfig()
                    )));
                });
    }

    /**
     * Add or update a module definition
     */
    @Post("/definitions{?clusterName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create or update module definition", 
              description = "Adds a new module definition or updates an existing one in the PipelineModuleMap")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Module definition created/updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ModuleOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Error updating configuration")
    })
    public Mono<HttpResponse<ModuleOperationResponse>> createOrUpdateModuleDefinition(
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName,
            @Body @Valid CreateModuleDefinitionRequest request) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<ModuleOperationResponse>serverError(new ModuleOperationResponse(
                                false,
                                "No cluster configuration found",
                                request.getImplementationId()
                        )));
                    }
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineModuleConfiguration> currentModules = 
                            new HashMap<>(clusterConfig.pipelineModuleMap().availableModules());
                    
                    // Create schema reference if provided
                    SchemaReference schemaRef = null;
                    if (request.getSchemaSubject() != null && request.getSchemaVersion() != null) {
                        schemaRef = new SchemaReference(
                                request.getSchemaSubject(),
                                request.getSchemaVersion()
                        );
                    }
                    
                    // Create new module configuration
                    PipelineModuleConfiguration newModule = new PipelineModuleConfiguration(
                            request.getImplementationName(),
                            request.getImplementationId(),
                            schemaRef,
                            request.getProperties() != null ? new HashMap<String, Object>(request.getProperties()) : Collections.emptyMap()
                    );
                    
                    // Update the modules map
                    currentModules.put(request.getImplementationId(), newModule);
                    
                    // Create updated cluster config
                    PipelineModuleMap updatedModuleMap = new PipelineModuleMap(currentModules);
                    PipelineClusterConfig updatedConfig = new PipelineClusterConfig(
                            clusterConfig.clusterName(),
                            clusterConfig.pipelineGraphConfig(),
                            updatedModuleMap,
                            clusterConfig.defaultPipelineName(),
                            clusterConfig.allowedKafkaTopics(),
                            clusterConfig.allowedGrpcServices()
                    );
                    
                    // Store updated configuration
                    return consulBusinessOperationsService
                            .storeClusterConfiguration(targetCluster, updatedConfig)
                            .map(stored -> {
                                if (Boolean.TRUE.equals(stored)) {
                                    LOG.info("Successfully added/updated module definition: {}", request.getImplementationId());
                                    return HttpResponse.<ModuleOperationResponse>created(new ModuleOperationResponse(
                                            true,
                                            "Module definition created/updated successfully",
                                            request.getImplementationId()
                                    ));
                                } else {
                                    return HttpResponse.<ModuleOperationResponse>serverError(new ModuleOperationResponse(
                                            false,
                                            "Failed to store updated configuration",
                                            request.getImplementationId()
                                    ));
                                }
                            });
                })
                .<HttpResponse<ModuleOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error creating/updating module definition: {}", request.getImplementationId(), e);
                    return Mono.just(HttpResponse.<ModuleOperationResponse>serverError(new ModuleOperationResponse(
                            false,
                            "Error: " + e.getMessage(),
                            request.getImplementationId()
                    )));
                });
    }

    /**
     * Delete a module definition
     */
    @Delete("/definitions/{moduleId}{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Delete module definition", 
              description = "Removes a module definition from the PipelineModuleMap")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Module definition deleted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ModuleOperationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Module definition not found"),
            @ApiResponse(responseCode = "409", description = "Module is in use and cannot be deleted"),
            @ApiResponse(responseCode = "500", description = "Error updating configuration")
    })
    public Mono<HttpResponse<ModuleOperationResponse>> deleteModuleDefinition(
            @Parameter(description = "Module implementation ID", required = true)
            @PathVariable String moduleId,
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        
        String targetCluster = clusterName.orElse(defaultClusterName);
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<ModuleOperationResponse>notFound());
                    }
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineModuleConfiguration> currentModules = 
                            new HashMap<>(clusterConfig.pipelineModuleMap().availableModules());
                    
                    if (!currentModules.containsKey(moduleId)) {
                        return Mono.just(HttpResponse.<ModuleOperationResponse>notFound());
                    }
                    
                    // TODO: Check if module is in use by any pipeline before deleting
                    // This would require iterating through all pipelines and checking processorInfo.grpcServiceName
                    
                    // Remove the module
                    currentModules.remove(moduleId);
                    
                    // Create updated cluster config
                    PipelineModuleMap updatedModuleMap = new PipelineModuleMap(currentModules);
                    PipelineClusterConfig updatedConfig = new PipelineClusterConfig(
                            clusterConfig.clusterName(),
                            clusterConfig.pipelineGraphConfig(),
                            updatedModuleMap,
                            clusterConfig.defaultPipelineName(),
                            clusterConfig.allowedKafkaTopics(),
                            clusterConfig.allowedGrpcServices()
                    );
                    
                    // Store updated configuration
                    return consulBusinessOperationsService
                            .storeClusterConfiguration(targetCluster, updatedConfig)
                            .map(stored -> {
                                if (Boolean.TRUE.equals(stored)) {
                                    LOG.info("Successfully deleted module definition: {}", moduleId);
                                    return HttpResponse.ok(new ModuleOperationResponse(
                                            true,
                                            "Module definition deleted successfully",
                                            moduleId
                                    ));
                                } else {
                                    return HttpResponse.<ModuleOperationResponse>serverError(new ModuleOperationResponse(
                                            false,
                                            "Failed to store updated configuration",
                                            moduleId
                                    ));
                                }
                            });
                })
                .<HttpResponse<ModuleOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error deleting module definition: {}", moduleId, e);
                    return Mono.just(HttpResponse.<ModuleOperationResponse>serverError(new ModuleOperationResponse(
                            false,
                            "Error: " + e.getMessage(),
                            moduleId
                    )));
                });
    }

    /**
     * Get module registration status from Consul
     */
    @Get("/status{?clusterName}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get module status", 
              description = "Returns the registration status of all defined modules in Consul")
    @ApiResponse(responseCode = "200", description = "Module status information",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ModuleStatusListResponse.class)))
    public Mono<HttpResponse<ModuleStatusListResponse>> getModuleStatus(
            @Parameter(description = "Cluster name (optional, defaults to configured cluster)")
            @QueryValue Optional<String> clusterName) {
        String targetCluster = clusterName.orElse(defaultClusterName);
        return consulBusinessOperationsService
                .getPipelineClusterConfig(targetCluster)
                .flatMap(clusterConfigOpt -> {
                    if (clusterConfigOpt.isEmpty()) {
                        return Mono.just(HttpResponse.ok(new ModuleStatusListResponse(Collections.emptyList())));
                    }
                    PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
                    Map<String, PipelineModuleConfiguration> modules = 
                            clusterConfig.pipelineModuleMap().availableModules();
                    
                    // Convert each module to a Mono of ModuleStatusInfo
                    List<Mono<ModuleStatusInfo>> statusMonos = modules.entrySet().stream()
                            .map(entry -> {
                                String moduleId = entry.getKey();
                                PipelineModuleConfiguration config = entry.getValue();
                                
                                // Check if module is registered in Consul
                                return consulBusinessOperationsService
                                        .getHealthyServiceInstances(moduleId)
                                        .defaultIfEmpty(Collections.emptyList())
                                        .map(serviceHealthList -> {
                                            // Ensure serviceHealthList is not null
                                            List<String> instances = Collections.emptyList();
                                            if (serviceHealthList != null && !serviceHealthList.isEmpty()) {
                                                instances = serviceHealthList.stream()
                                                        .filter(sh -> sh != null && sh.getNode() != null && sh.getService() != null)
                                                        .map(sh -> sh.getNode().getAddress() + ":" + sh.getService().getPort())
                                                        .collect(Collectors.toList());
                                            }
                                            
                                            boolean isRegistered = !instances.isEmpty();
                                            int instanceCount = instances.size();
                                            
                                            return new ModuleStatusInfo(
                                                    moduleId,
                                                    config.implementationName(),
                                                    isRegistered,
                                                    instanceCount,
                                                    instances
                                            );
                                        });
                            })
                            .collect(Collectors.toList());
                    
                    // Combine all Monos into a single list
                    if (statusMonos.isEmpty()) {
                        return Mono.just(HttpResponse.ok(new ModuleStatusListResponse(Collections.emptyList())));
                    }
                    
                    // Using collectList instead of zip to handle the results
                    return Mono.zip(statusMonos, results -> {
                        List<ModuleStatusInfo> statusList = new ArrayList<>();
                        for (Object result : results) {
                            if (result instanceof ModuleStatusInfo) {
                                ModuleStatusInfo info = (ModuleStatusInfo) result;
                                // Ensure instances list is never null
                                if (info.getInstances() == null) {
                                    // Create a new ModuleStatusInfo with an empty list
                                    info = new ModuleStatusInfo(
                                            info.getImplementationId(),
                                            info.getImplementationName(),
                                            info.isRegistered(),
                                            info.getInstanceCount(),
                                            Collections.emptyList()
                                    );
                                }
                                statusList.add(info);
                            }
                        }
                        return HttpResponse.ok(new ModuleStatusListResponse(statusList));
                    });
                });
    }

    // Request/Response DTOs
    
    @Serdeable
    @Schema(description = "Response containing module definitions")
    public static class ModuleDefinitionsResponse {
        private final String clusterName;
        private final List<ModuleDefinition> modules;
        
        public ModuleDefinitionsResponse(String clusterName, List<ModuleDefinition> modules) {
            this.clusterName = clusterName;
            this.modules = modules;
        }
        
        public String getClusterName() { return clusterName; }
        public List<ModuleDefinition> getModules() { return modules; }
    }
    
    @Serdeable
    @Schema(description = "Module definition details")
    public static class ModuleDefinition {
        @Schema(description = "Unique implementation ID")
        private final String implementationId;
        @Schema(description = "Human-readable name")
        private final String implementationName;
        @Schema(description = "Schema reference for custom configuration")
        private final SchemaReference customConfigSchemaReference;
        @Schema(description = "Additional properties")
        private final Map<String, Object> properties;
        
        public ModuleDefinition(String implementationId, String implementationName, 
                               SchemaReference customConfigSchemaReference,
                               Map<String, Object> properties) {
            this.implementationId = implementationId;
            this.implementationName = implementationName;
            this.customConfigSchemaReference = customConfigSchemaReference;
            this.properties = properties;
        }
        
        public String getImplementationId() { return implementationId; }
        public String getImplementationName() { return implementationName; }
        public SchemaReference getCustomConfigSchemaReference() { return customConfigSchemaReference; }
        public Map<String, Object> getProperties() { return properties; }
    }
    
    @Serdeable
    @Schema(description = "Request to create or update a module definition")
    public static class CreateModuleDefinitionRequest {
        @NotBlank
        @Schema(description = "Unique implementation ID", required = true)
        private String implementationId;
        
        @NotBlank
        @Schema(description = "Human-readable name", required = true)
        private String implementationName;
        
        @Schema(description = "Schema subject for custom configuration")
        private String schemaSubject;
        
        @Schema(description = "Schema version for custom configuration")
        private Integer schemaVersion;
        
        @Schema(description = "Additional properties")
        private Map<String, Object> properties;
        
        public CreateModuleDefinitionRequest() {}
        
        // Getters and setters
        public String getImplementationId() { return implementationId; }
        public void setImplementationId(String implementationId) { this.implementationId = implementationId; }
        
        public String getImplementationName() { return implementationName; }
        public void setImplementationName(String implementationName) { this.implementationName = implementationName; }
        
        public String getSchemaSubject() { return schemaSubject; }
        public void setSchemaSubject(String schemaSubject) { this.schemaSubject = schemaSubject; }
        
        public Integer getSchemaVersion() { return schemaVersion; }
        public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
        
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }
    
    @Serdeable
    @Schema(description = "Response for module operations")
    public static class ModuleOperationResponse {
        private final boolean success;
        private final String message;
        private final String moduleId;
        
        public ModuleOperationResponse(boolean success, String message, String moduleId) {
            this.success = success;
            this.message = message;
            this.moduleId = moduleId;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getModuleId() { return moduleId; }
    }
    
    @Serdeable
    @Schema(description = "Module status information")
    public static class ModuleStatusInfo {
        @Schema(description = "Module implementation ID")
        private final String implementationId;
        @Schema(description = "Module name")
        private final String implementationName;
        @Schema(description = "Whether module is registered in Consul")
        private final boolean registered;
        @Schema(description = "Number of registered instances")
        private final int instanceCount;
        @Schema(description = "List of instance addresses")
        private final List<String> instances;
        
        public ModuleStatusInfo(String implementationId, String implementationName,
                               boolean registered, int instanceCount, List<String> instances) {
            this.implementationId = implementationId;
            this.implementationName = implementationName;
            this.registered = registered;
            this.instanceCount = instanceCount;
            this.instances = instances != null ? instances : Collections.emptyList();
        }
        
        public String getImplementationId() { return implementationId; }
        public String getImplementationName() { return implementationName; }
        public boolean isRegistered() { return registered; }
        public int getInstanceCount() { return instanceCount; }
        public List<String> getInstances() { return instances; }
    }
    
    @Serdeable
    @Schema(description = "Response containing module status list")
    public static class ModuleStatusListResponse {
        private final List<ModuleStatusInfo> modules;
        
        public ModuleStatusListResponse(List<ModuleStatusInfo> modules) {
            this.modules = modules;
        }
        
        public List<ModuleStatusInfo> getModules() { return modules; }
    }
}