package com.krickert.search.config.consul.api;

// Keep necessary imports: SchemaNotFoundException, SchemaValidationException, SchemaService, HttpResponse, MediaType, @Controller, @Get, @Put, @Post, @Delete, @PathVariable, @Body, Operation, etc., Mono, Map, Collections

import com.krickert.search.config.consul.service.SchemaService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
// *** NO @ExecuteOn annotation ***
public class SchemaController {

    private final SchemaService schemaService;

    @Inject
    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @Get(uri = "/{serviceImplementationName}", produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Get Schema")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema found", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "404", description = "Schema not found"), // Handled by SchemaNotFoundExceptionHandler
            @ApiResponse(responseCode = "500", description = "Internal server error") // Default handler
    })
    public Mono<HttpResponse<Object>> getSchema(
            @PathVariable @NotBlank String serviceImplementationName) {
        log.debug("Controller: GET schema for {}", serviceImplementationName);
        // Delegate, map success, let errors propagate to handlers
        return schemaService.getSchemaJson(serviceImplementationName)
                .map(schemaJson -> HttpResponse.<Object>ok(schemaJson));
        // Errors (e.g., SchemaNotFoundException) will propagate and be caught by handlers
    }

    @Put(uri = "/{serviceImplementationName}", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Register or Update Schema")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema saved successfully", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input"), // Handled by IllegalArgumentExceptionHandler
            @ApiResponse(responseCode = "500", description = "Failed to save schema") // Default handler
    })
    public Mono<HttpResponse<Object>> saveSchema(
            @PathVariable @NotBlank String serviceImplementationName,
            @Body String schemaJson) {
        log.info("Controller: PUT schema for {}", serviceImplementationName);
        // Delegate, map success, let errors propagate to handlers
        return schemaService.saveSchema(serviceImplementationName, schemaJson) // Returns Mono<Void>
                .then(Mono.fromCallable(() -> { // Execute only on successful completion
                    Map<String, Object> responseBody = Map.of(
                            "message", "Schema saved successfully for " + serviceImplementationName
                    );
                    // Use explicit type parameter for HttpResponse
                    return HttpResponse.<Object>ok(responseBody);
                }));
        // Errors (e.g., IllegalArgumentException) will propagate and be caught by handlers
    }

    @Delete(uri = "/{serviceImplementationName}")
    @Operation(summary = "Delete Schema")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Schema deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Schema not found"), // Handled by SchemaNotFoundExceptionHandler
            @ApiResponse(responseCode = "500", description = "Internal server error") // Default handler
    })
    public Mono<HttpResponse<Void>> deleteSchema(
            @PathVariable @NotBlank String serviceImplementationName) {
        log.info("Controller: DELETE schema for {}", serviceImplementationName);
        // Delegate, map success, let errors propagate to handlers
        return schemaService.deleteSchema(serviceImplementationName) // Returns Mono<Void>
                .then(Mono.just(HttpResponse.<Void>noContent())); // Map success to 204
        // Errors (e.g., SchemaNotFoundException) will propagate and be caught by handlers
    }

    @Post(uri = "/{serviceImplementationName}/validate", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate Configuration")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Validation successful", content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed or invalid input JSON"), // Handled by SchemaValidationExceptionHandler or IllegalArgumentExceptionHandler
            @ApiResponse(responseCode = "500", description = "Internal server error") // Default handler
    })
    public Mono<HttpResponse<Object>> validateConfiguration(
            @PathVariable @NotBlank String serviceImplementationName,
            @Body Map<String, Object> configParams) {
        log.debug("Controller: POST validate config for {}", serviceImplementationName);
        // Synchronous check for null body
        if (configParams == null) {
            // Throw standard exceptions, let handler manage
            throw new IllegalArgumentException("Request body (configParams) cannot be null");
        }

        // Delegate, map success, let errors propagate to handlers
        return schemaService.validateConfigurationReactive(serviceImplementationName, configParams) // Returns Mono<Void>
                .then(Mono.fromCallable(() -> { // Execute only on successful completion
                    Map<String, Object> responseBody = Map.of(
                            "valid", true,
                            "messages", Collections.emptySet()
                    );
                    log.debug("Controller: Validation successful, returning OK for {}", serviceImplementationName);
                    // Use explicit type parameter for HttpResponse
                    return HttpResponse.<Object>ok(responseBody);
                }));
        // Errors (e.g., SchemaValidationException) will propagate and be caught by handlers
    }
}