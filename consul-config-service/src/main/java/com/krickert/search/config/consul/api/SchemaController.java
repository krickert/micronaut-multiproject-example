package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.service.SchemaService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Controller("/api/schemas")
@Tag(name = "Schema Management", description = "Endpoints for managing pipeline service configuration JSON Schemas")
@ExecuteOn(TaskExecutors.IO)
public class SchemaController {

    private final SchemaService schemaService;

    @Inject
    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Get(uri = "/{serviceImplementationName}", produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Get Schema")
    @ApiResponses({
        // Even though body is string, describe content as object for generic response
        @ApiResponse(responseCode = "200", description = "Schema found", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "404", description = "Schema not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    // *** Change return type to HttpResponse<Object> ***
    public Mono<HttpResponse<Object>> getSchema(
            @PathVariable @NotBlank String serviceImplementationName) {

        log.debug("Received GET request for schema for: {}", serviceImplementationName);
        // Service returns Mono<String> or signals SchemaNotFoundException / RuntimeException
        return schemaService.getSchemaJson(serviceImplementationName)
                // *** Cast the result to Object within ok() ***
                .map(schemaJson -> HttpResponse.ok((Object) schemaJson));
        // Errors (SchemaNotFoundException, RuntimeException) propagate to handlers
    }

    // --- Other methods remain the same as the previous CORRECTED version ---

    @Put(uri = "/{serviceImplementationName}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Register or Update Schema")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Schema saved successfully", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Map.class))),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "500", description = "Failed to save schema")
    })
    public Mono<HttpResponse<Object>> saveSchema(
            @PathVariable @NotBlank String serviceImplementationName,
            @Body String schemaJson) {
        log.info("Received PUT request to save schema for: {}", serviceImplementationName);
        return schemaService.saveSchema(serviceImplementationName, schemaJson)
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> responseBody = Map.of("message", "Schema saved successfully for " + serviceImplementationName);
                    return HttpResponse.ok((Object) responseBody);
                }));
    }

    @Delete(uri = "/{serviceImplementationName}")
    @Operation(summary = "Delete Schema")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Schema deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Schema not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public Mono<HttpResponse<Void>> deleteSchema(
            @PathVariable @NotBlank String serviceImplementationName) {
        log.info("Received DELETE request for schema for: {}", serviceImplementationName);
        return schemaService.deleteSchema(serviceImplementationName)
                .then(Mono.just(HttpResponse.noContent()));
    }

    @Post(uri = "/{serviceImplementationName}/validate", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate Configuration")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Validation successful", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Map.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed or invalid input JSON"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public Mono<HttpResponse<Object>> validateConfiguration(
            @PathVariable @NotBlank String serviceImplementationName,
            @Body Map<String, Object> configParams) {
        log.debug("Received POST request to validate config for: {}", serviceImplementationName);
        if (configParams == null) {
             return Mono.error(new IllegalArgumentException("Request body (configParams) cannot be null"));
        }
        return schemaService.validateConfig(serviceImplementationName, configParams)
                .then(Mono.fromCallable(() -> {
                     Map<String, Object> responseBody = Map.of(
                            "valid", true,
                            "messages", Collections.emptySet()
                    );
                    return HttpResponse.ok((Object) responseBody);
                }));
    }
}