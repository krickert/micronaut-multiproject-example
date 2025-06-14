package com.krickert.search.pipeline.api.controller;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.Set;

@Controller("/api/v1/clusters/{cluster}/whitelist")
@Tag(name = "Whitelist Management", description = "Manage Kafka topic and gRPC service whitelists for pipeline clusters")
public class WhitelistController {
    
    private final ConsulBusinessOperationsService consulService;
    
    @Inject
    public WhitelistController(ConsulBusinessOperationsService consulService) {
        this.consulService = consulService;
    }
    
    @Get("/kafka-topics")
    @Operation(summary = "Get whitelisted Kafka topics", description = "Retrieve all whitelisted Kafka topics for a cluster")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved Kafka topic whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found")
    })
    public Mono<Set<String>> getKafkaTopicWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster) {
        return consulService.getAllowedKafkaTopics(cluster);
    }
    
    @Get("/grpc-services")
    @Operation(summary = "Get whitelisted gRPC services", description = "Retrieve all whitelisted gRPC services for a cluster")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved gRPC service whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found")
    })
    public Mono<Set<String>> getGrpcServiceWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster) {
        return consulService.getAllowedGrpcServices(cluster);
    }
    
    @Put("/kafka-topics")
    @Operation(summary = "Update Kafka topic whitelist", description = "Replace the entire Kafka topic whitelist for a cluster")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully updated Kafka topic whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public Mono<HttpResponse<Set<String>>> updateKafkaTopicWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Body Set<String> topics) {
        return updateWhitelist(cluster, topics, true);
    }
    
    @Put("/grpc-services")
    @Operation(summary = "Update gRPC service whitelist", description = "Replace the entire gRPC service whitelist for a cluster")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully updated gRPC service whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public Mono<HttpResponse<Set<String>>> updateGrpcServiceWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Body Set<String> services) {
        return updateWhitelist(cluster, services, false);
    }
    
    @Post("/kafka-topics/{topic}")
    @Operation(summary = "Add Kafka topic to whitelist", description = "Add a single Kafka topic to the cluster whitelist")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully added topic to whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found"),
        @ApiResponse(responseCode = "409", description = "Topic already whitelisted")
    })
    public Mono<HttpResponse<Void>> addKafkaTopicToWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Parameter(description = "Kafka topic name", required = true) @PathVariable String topic) {
        return addToWhitelist(cluster, topic, true);
    }
    
    @Post("/grpc-services/{service}")
    @Operation(summary = "Add gRPC service to whitelist", description = "Add a single gRPC service to the cluster whitelist")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully added service to whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster not found"),
        @ApiResponse(responseCode = "409", description = "Service already whitelisted")
    })
    public Mono<HttpResponse<Void>> addGrpcServiceToWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Parameter(description = "gRPC service name", required = true) @PathVariable String service) {
        return addToWhitelist(cluster, service, false);
    }
    
    @Delete("/kafka-topics/{topic}")
    @Operation(summary = "Remove Kafka topic from whitelist", description = "Remove a single Kafka topic from the cluster whitelist")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully removed topic from whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster or topic not found")
    })
    public Mono<HttpResponse<Void>> removeKafkaTopicFromWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Parameter(description = "Kafka topic name", required = true) @PathVariable String topic) {
        return removeFromWhitelist(cluster, topic, true);
    }
    
    @Delete("/grpc-services/{service}")
    @Operation(summary = "Remove gRPC service from whitelist", description = "Remove a single gRPC service from the cluster whitelist")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully removed service from whitelist"),
        @ApiResponse(responseCode = "404", description = "Cluster or service not found")
    })
    public Mono<HttpResponse<Void>> removeGrpcServiceFromWhitelist(
            @Parameter(description = "Cluster name", required = true) @PathVariable String cluster,
            @Parameter(description = "gRPC service name", required = true) @PathVariable String service) {
        return removeFromWhitelist(cluster, service, false);
    }
    
    private Mono<HttpResponse<Set<String>>> updateWhitelist(String cluster, Set<String> items, boolean isKafka) {
        return consulService.getPipelineClusterConfig(cluster)
                .flatMap(configOpt -> {
                    if (configOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<Set<String>>notFound());
                    }
                    
                    var config = configOpt.get();
                    var updatedConfig = isKafka
                            ? config.toBuilder().allowedKafkaTopics(new HashSet<>(items)).build()
                            : config.toBuilder().allowedGrpcServices(new HashSet<>(items)).build();
                    
                    return consulService.storeClusterConfiguration(cluster, updatedConfig)
                            .map(success -> success 
                                    ? HttpResponse.ok(items)
                                    : HttpResponse.<Set<String>>serverError());
                });
    }
    
    private Mono<HttpResponse<Void>> addToWhitelist(String cluster, String item, boolean isKafka) {
        return consulService.getPipelineClusterConfig(cluster)
                .flatMap(configOpt -> {
                    if (configOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<Void>notFound());
                    }
                    
                    var config = configOpt.get();
                    var currentItems = isKafka 
                            ? new HashSet<>(config.allowedKafkaTopics() != null ? config.allowedKafkaTopics() : Set.of())
                            : new HashSet<>(config.allowedGrpcServices() != null ? config.allowedGrpcServices() : Set.of());
                    
                    if (currentItems.contains(item)) {
                        return Mono.just(HttpResponse.<Void>status(io.micronaut.http.HttpStatus.CONFLICT));
                    }
                    
                    currentItems.add(item);
                    var updatedConfig = isKafka
                            ? config.toBuilder().allowedKafkaTopics(currentItems).build()
                            : config.toBuilder().allowedGrpcServices(currentItems).build();
                    
                    return consulService.storeClusterConfiguration(cluster, updatedConfig)
                            .map(success -> success 
                                    ? HttpResponse.<Void>ok()
                                    : HttpResponse.<Void>serverError());
                });
    }
    
    private Mono<HttpResponse<Void>> removeFromWhitelist(String cluster, String item, boolean isKafka) {
        return consulService.getPipelineClusterConfig(cluster)
                .flatMap(configOpt -> {
                    if (configOpt.isEmpty()) {
                        return Mono.just(HttpResponse.<Void>notFound());
                    }
                    
                    var config = configOpt.get();
                    var currentItems = isKafka 
                            ? new HashSet<>(config.allowedKafkaTopics() != null ? config.allowedKafkaTopics() : Set.of())
                            : new HashSet<>(config.allowedGrpcServices() != null ? config.allowedGrpcServices() : Set.of());
                    
                    if (!currentItems.contains(item)) {
                        return Mono.just(HttpResponse.<Void>notFound());
                    }
                    
                    currentItems.remove(item);
                    var updatedConfig = isKafka
                            ? config.toBuilder().allowedKafkaTopics(currentItems).build()
                            : config.toBuilder().allowedGrpcServices(currentItems).build();
                    
                    return consulService.storeClusterConfiguration(cluster, updatedConfig)
                            .map(success -> success 
                                    ? HttpResponse.<Void>ok()
                                    : HttpResponse.<Void>serverError());
                });
    }
}