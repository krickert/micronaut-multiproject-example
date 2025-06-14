package com.krickert.search.pipeline.api.controller;

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
import com.krickert.search.config.consul.model.ClusterInfo;
import com.krickert.search.pipeline.api.dto.ClusterStatus;
import com.krickert.search.pipeline.api.service.ClusterService;

import jakarta.validation.constraints.NotBlank;

/**
 * REST API for managing YAPPY clusters.
 * 
 * This is where admins start - seeing what clusters exist and their status.
 */
@Controller("/api/clusters")
@Tag(name = "Clusters", description = "Cluster management operations")
@ExecuteOn(TaskExecutors.IO)
public class ClusterController {

    private final ClusterService clusterService;

    public ClusterController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @Get
    @Operation(
        summary = "List all clusters",
        description = "Returns all configured YAPPY clusters with their basic information"
    )
    @ApiResponse(
        responseCode = "200",
        description = "List of clusters",
        content = @Content(schema = @Schema(implementation = ClusterInfo.class))
    )
    public Flux<ClusterInfo> listClusters() {
        return clusterService.listClusters();
    }

    @Get("/{name}")
    @Operation(
        summary = "Get cluster details",
        description = "Returns detailed information about a specific cluster including configuration"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Cluster details",
        content = @Content(schema = @Schema(implementation = ClusterInfo.class))
    )
    @ApiResponse(responseCode = "404", description = "Cluster not found")
    public Mono<ClusterInfo> getCluster(@PathVariable @NotBlank String name) {
        return clusterService.getCluster(name);
    }

    @Get("/{name}/status")
    @Operation(
        summary = "Get cluster status",
        description = "Returns the current operational status of a cluster including module health"
    )
    @ApiResponse(
        responseCode = "200",
        description = "Cluster status",
        content = @Content(schema = @Schema(implementation = ClusterStatus.class))
    )
    @ApiResponse(responseCode = "404", description = "Cluster not found")
    public Mono<ClusterStatus> getClusterStatus(@PathVariable @NotBlank String name) {
        return clusterService.getClusterStatus(name);
    }

    @Post
    @Operation(
        summary = "Create a new cluster",
        description = "Creates a new YAPPY cluster configuration"
    )
    @ApiResponse(responseCode = "201", description = "Cluster created")
    @ApiResponse(responseCode = "400", description = "Invalid cluster configuration")
    @ApiResponse(responseCode = "409", description = "Cluster already exists")
    public Mono<ClusterInfo> createCluster(@Body ClusterInfo clusterInfo) {
        return clusterService.createCluster(clusterInfo);
    }

    @Put("/{name}")
    @Operation(
        summary = "Update cluster configuration",
        description = "Updates an existing cluster's configuration"
    )
    @ApiResponse(responseCode = "200", description = "Cluster updated")
    @ApiResponse(responseCode = "400", description = "Invalid cluster configuration")
    @ApiResponse(responseCode = "404", description = "Cluster not found")
    public Mono<ClusterInfo> updateCluster(
            @PathVariable @NotBlank String name,
            @Body ClusterInfo clusterInfo) {
        return clusterService.updateCluster(name, clusterInfo);
    }

    @Delete("/{name}")
    @Operation(
        summary = "Delete a cluster",
        description = "Removes a cluster configuration (requires confirmation)"
    )
    @ApiResponse(responseCode = "204", description = "Cluster deleted")
    @ApiResponse(responseCode = "404", description = "Cluster not found")
    @ApiResponse(responseCode = "409", description = "Cluster has active pipelines")
    public Mono<Void> deleteCluster(
            @PathVariable @NotBlank String name,
            @QueryValue(defaultValue = "false") boolean force) {
        return clusterService.deleteCluster(name, force);
    }
}