package com.krickert.search.pipeline.api.controller;

import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.ModuleService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Module management API - register and manage processing modules.
 */
@Controller("/api/v1/modules")
@Tag(name = "Modules", description = "Register and manage processing modules")
@ExecuteOn(TaskExecutors.IO)
public class ModuleController {

    private final ModuleService moduleService;

    public ModuleController(ModuleService moduleService) {
        this.moduleService = moduleService;
    }

    @Get
    @Operation(summary = "List modules", description = "Get all registered modules")
    public Flux<ModuleInfo> listModules(
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.listModules(cluster);
    }

    @Get("/{serviceId}")
    @Operation(summary = "Get module details", description = "Get information about a specific module")
    public Mono<ModuleInfo> getModule(
            @PathVariable @NotBlank String serviceId,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.getModule(cluster, serviceId);
    }

    @Post("/register")
    @Status(HttpStatus.CREATED)
    @Operation(summary = "Register a module", description = "Register a new processing module")
    @ApiResponse(responseCode = "201", description = "Module registered successfully")
    @ApiResponse(responseCode = "400", description = "Invalid module configuration")
    @ApiResponse(responseCode = "409", description = "Module already exists")
    public Mono<ModuleRegistrationResponse> registerModule(
            @Body @Valid ModuleRegistrationRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.registerModule(cluster, request);
    }

    @Put("/{serviceId}")
    @Operation(summary = "Update module", description = "Update module registration")
    public Mono<ModuleInfo> updateModule(
            @PathVariable @NotBlank String serviceId,
            @Body @Valid ModuleUpdateRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.updateModule(cluster, serviceId, request);
    }

    @Delete("/{serviceId}")
    @Status(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deregister module", description = "Remove a module from the cluster")
    public Mono<Void> deregisterModule(
            @PathVariable @NotBlank String serviceId,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.deregisterModule(cluster, serviceId);
    }

    @Get("/{serviceId}/health")
    @Operation(summary = "Check module health", description = "Get current health status of a module")
    public Mono<ModuleHealthStatus> checkModuleHealth(
            @PathVariable @NotBlank String serviceId,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.checkHealth(cluster, serviceId);
    }

    @Post("/{serviceId}/test")
    @Operation(summary = "Test module", description = "Send test data to a module")
    public Mono<ModuleTestResponse> testModule(
            @PathVariable @NotBlank String serviceId,
            @Body @Valid ModuleTestRequest request,
            @QueryValue(defaultValue = "default") String cluster) {
        return moduleService.testModule(cluster, serviceId, request);
    }

    @Get("/templates")
    @Operation(summary = "Get module templates", description = "Get available module configuration templates")
    public Flux<ModuleTemplate> getModuleTemplates() {
        return moduleService.getTemplates();
    }
}