package com.krickert.yappy.engine.controller.admin;

import com.krickert.yappy.engine.controller.admin.dto.*;
import com.krickert.yappy.engine.service.BootstrapAdminService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Post;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@Controller("/api/setup")
@io.micronaut.core.annotation.Introspected
public class AdminSetupController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSetupController.class);

    private final BootstrapAdminService bootstrapAdminService;

    @Inject
    public AdminSetupController(BootstrapAdminService bootstrapAdminService) {
        this.bootstrapAdminService = bootstrapAdminService;
        LOG.info("AdminSetupController initialized with hash: {}", this.hashCode());
        LOG.info("AdminSetupController class: {}", this.getClass().getName());
        LOG.info("AdminSetupController annotations: {}", java.util.Arrays.toString(this.getClass().getAnnotations()));
    }
    
    @Get("/test")
    @Produces(MediaType.TEXT_PLAIN)
    public String test() {
        LOG.info("test() method called");
        return "Controller is working";
    }
    
    @Get("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        LOG.info("ping() method called");
        return "pong";
    }
    
    @Get("/ping-reactive")
    @Produces(MediaType.TEXT_PLAIN)
    public Mono<String> pingReactive() {
        LOG.info("pingReactive() method called");
        return Mono.just("pong reactive");
    }

    @Get("/consul-config")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get Current Consul Configuration",
               description = "Returns the current Consul connection details from bootstrap properties.")
    @ApiResponse(responseCode = "200", description = "Current Consul configuration returned successfully.")
    public Mono<ConsulConfigResponse> getCurrentConsulConfiguration() {
        LOG.info("getCurrentConsulConfiguration called");
        return bootstrapAdminService.getCurrentConsulConfiguration();
    }

    @Get("/yappy-clusters")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List Available Yappy Clusters",
               description = "Returns a list of available Yappy clusters from Consul.")
    @ApiResponse(responseCode = "200", description = "List of available clusters returned successfully.")
    public Mono<List<YappyClusterInfo>> listAvailableYappyClusters() {
        LOG.info("listAvailableYappyClusters called");
        return bootstrapAdminService.listAvailableYappyClusters();
    }

    @Post("/yappy-clusters")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create New Yappy Cluster",
               description = "Creates a new Yappy cluster configuration in Consul and selects it in the bootstrap properties.")
    @ApiResponse(responseCode = "201", description = "Yappy cluster created and selected successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload or cluster already exists.")
    @ApiResponse(responseCode = "500", description = "Error creating cluster or updating bootstrap configuration.")
    public Mono<CreateClusterResponse> createNewYappyCluster(@Body @Valid CreateClusterRequest request) {
        LOG.info("createNewYappyCluster called for cluster: {}", request.getClusterName());
        return bootstrapAdminService.createNewYappyCluster(request);
    }

    @Post("/cluster/select")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Select Active Yappy Cluster",
               description = "Selects an existing Yappy cluster as the active cluster in bootstrap properties.")
    @ApiResponse(responseCode = "200", description = "Cluster selected successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload.")
    @ApiResponse(responseCode = "500", description = "Error updating bootstrap properties.")
    public Mono<SelectClusterResponse> selectActiveYappyCluster(@Body @Valid SelectClusterRequest request) {
        LOG.info("selectActiveYappyCluster called for cluster: {}", request.getClusterName());
        return bootstrapAdminService.selectActiveYappyCluster(request);
    }

    @Post("/consul-config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set Consul Configuration",
               description = "Updates the Consul connection details in the bootstrap properties file.")
    @ApiResponse(responseCode = "200", description = "Consul configuration updated successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload.")
    @ApiResponse(responseCode = "500", description = "Error updating bootstrap properties file.")
    public Mono<SetConsulConfigResponse> setConsulConfiguration(@Body @Valid SetConsulConfigRequest request) {
        LOG.info("setConsulConfiguration called for host: {}, port: {}", request.getHost(), request.getPort());
        return bootstrapAdminService.setConsulConfiguration(request);
    }
}