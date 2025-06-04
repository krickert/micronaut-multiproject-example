package com.krickert.yappy.engine.controller.admin;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.consul.schema.exception.SchemaNotFoundException;
import com.networknt.schema.ValidationMessage;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.validation.Validated;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin API for managing JSON Schema definitions.
 * This controller provides CRUD operations for schemas used in pipeline configuration validation.
 */
@Validated
@Controller("/api/admin/schemas")
@Tag(name = "Admin Schema Management", description = "Administrative endpoints for managing JSON Schema definitions")
public class AdminSchemaController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminSchemaController.class);

    private final ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @Inject
    public AdminSchemaController(ConsulSchemaRegistryDelegate schemaRegistryDelegate) {
        this.schemaRegistryDelegate = schemaRegistryDelegate;
    }

    /**
     * List all registered schemas
     */
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List schemas", 
              description = "Returns all registered JSON Schema definitions")
    @ApiResponse(responseCode = "200", description = "List of schemas",
            content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = SchemaListResponse.class)))
    @ApiResponse(responseCode = "500", description = "Server error")
    public Mono<SchemaListResponse> listSchemas() {
        LOG.info("Listing all schemas");
        
        return schemaRegistryDelegate.listSchemaIds()
                .map(schemaIds -> {
                    LOG.info("Retrieved {} schema IDs from delegate", schemaIds.size());
                    List<SchemaInfo> schemas = schemaIds.stream()
                            .map(id -> new SchemaInfo(id, null, null, null))
                            .collect(Collectors.toList());
                    SchemaListResponse response = new SchemaListResponse(schemas);
                    LOG.info("Created SchemaListResponse with {} schemas", schemas.size());
                    return response;
                })
                .onErrorResume(e -> {
                    LOG.error("Error listing schemas", e);
                    return Mono.empty();
                });
    }

    /**
     * Get a specific schema by ID
     */
    @Get("/{schemaId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get schema", 
              description = "Returns a specific JSON Schema definition by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema found",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SchemaInfo.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public Mono<HttpResponse<SchemaInfo>> getSchema(
            @Parameter(description = "Schema ID", required = true, example = "embedder-config-schema")
            @PathVariable String schemaId) {
        
        LOG.debug("Getting schema: {}", schemaId);
        
        return schemaRegistryDelegate.getSchemaContent(schemaId)
                .map(content -> {
                    SchemaInfo schemaInfo = new SchemaInfo(
                            schemaId,
                            content,
                            Instant.now().toString(),
                            "JSON Schema for " + schemaId
                    );
                    return HttpResponse.ok(schemaInfo);
                })
                .<HttpResponse<SchemaInfo>>map(response -> response)
                .onErrorResume(e -> {
                    if (e instanceof SchemaNotFoundException) {
                        LOG.warn("Schema not found: {}", schemaId);
                        return Mono.just(HttpResponse.<SchemaInfo>notFound());
                    } else {
                        LOG.error("Error getting schema: {}", schemaId, e);
                        return Mono.just(HttpResponse.<SchemaInfo>serverError());
                    }
                });
    }

    /**
     * Register a new schema
     */
    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Register schema", 
              description = "Registers a new JSON Schema definition")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Schema created",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SchemaOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid schema content"),
            @ApiResponse(responseCode = "409", description = "Schema already exists"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public Mono<HttpResponse<SchemaOperationResponse>> createSchema(
            @Body @Valid CreateSchemaRequest request) {
        
        LOG.info("Creating schema: {}", request.getSchemaId());
        
        // First check if schema already exists
        return schemaRegistryDelegate.getSchemaContent(request.getSchemaId())
                .flatMap(existing -> {
                    // Schema already exists
                    return Mono.just(HttpResponse.<SchemaOperationResponse>status(HttpStatus.CONFLICT)
                            .body(new SchemaOperationResponse(
                                    false,
                                    "Schema already exists",
                                    request.getSchemaId(),
                                    Collections.singletonList("Schema with ID '" + request.getSchemaId() + "' already exists")
                            )));
                })
                .<HttpResponse<SchemaOperationResponse>>map(response -> response)
                .onErrorResume(SchemaNotFoundException.class, e -> {
                    // Schema doesn't exist, proceed with creation
                    return schemaRegistryDelegate.saveSchema(request.getSchemaId(), request.getSchemaContent())
                            .then(Mono.just(HttpResponse.<SchemaOperationResponse>created(
                                    new SchemaOperationResponse(
                                            true,
                                            "Schema created successfully",
                                            request.getSchemaId(),
                                            Collections.emptyList()
                                    ))))
                            .<HttpResponse<SchemaOperationResponse>>map(response -> response)
                            .onErrorResume(validationError -> {
                                if (validationError instanceof IllegalArgumentException) {
                                    LOG.warn("Schema validation failed: {}", validationError.getMessage());
                                    return Mono.just(HttpResponse.<SchemaOperationResponse>badRequest()
                                            .body(new SchemaOperationResponse(
                                                    false,
                                                    "Schema validation failed",
                                                    request.getSchemaId(),
                                                    Collections.singletonList(validationError.getMessage())
                                            )));
                                } else {
                                    LOG.error("Error creating schema: {}", request.getSchemaId(), validationError);
                                    return Mono.just(HttpResponse.<SchemaOperationResponse>serverError()
                                            .body(new SchemaOperationResponse(
                                                    false,
                                                    "Internal server error",
                                                    request.getSchemaId(),
                                                    Collections.singletonList("Failed to save schema: " + validationError.getMessage())
                                            )));
                                }
                            });
                });
    }

    /**
     * Update an existing schema
     */
    @Put("/{schemaId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update schema", 
              description = "Updates an existing JSON Schema definition")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema updated",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SchemaOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid schema content"),
            @ApiResponse(responseCode = "404", description = "Schema not found"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public Mono<HttpResponse<SchemaOperationResponse>> updateSchema(
            @Parameter(description = "Schema ID", required = true, example = "embedder-config-schema")
            @PathVariable String schemaId,
            @Body @Valid UpdateSchemaRequest request) {
        
        LOG.info("Updating schema: {}", schemaId);
        
        // First check if schema exists
        return schemaRegistryDelegate.getSchemaContent(schemaId)
                .flatMap(existing -> {
                    // Schema exists, proceed with update
                    return schemaRegistryDelegate.saveSchema(schemaId, request.getSchemaContent())
                            .then(Mono.just(HttpResponse.ok(new SchemaOperationResponse(
                                    true,
                                    "Schema updated successfully",
                                    schemaId,
                                    Collections.emptyList()
                            ))))
                            .onErrorResume(validationError -> {
                                if (validationError instanceof IllegalArgumentException) {
                                    LOG.warn("Schema validation failed: {}", validationError.getMessage());
                                    return Mono.just(HttpResponse.<SchemaOperationResponse>badRequest()
                                            .body(new SchemaOperationResponse(
                                                    false,
                                                    "Schema validation failed",
                                                    schemaId,
                                                    Collections.singletonList(validationError.getMessage())
                                            )));
                                } else {
                                    LOG.error("Error updating schema: {}", schemaId, validationError);
                                    return Mono.just(HttpResponse.<SchemaOperationResponse>serverError()
                                            .body(new SchemaOperationResponse(
                                                    false,
                                                    "Internal server error",
                                                    schemaId,
                                                    Collections.singletonList("Failed to update schema: " + validationError.getMessage())
                                            )));
                                }
                            });
                })
                .<HttpResponse<SchemaOperationResponse>>map(response -> response)
                .onErrorResume(SchemaNotFoundException.class, e -> {
                    LOG.warn("Schema not found for update: {}", schemaId);
                    return Mono.just(HttpResponse.<SchemaOperationResponse>notFound());
                });
    }

    /**
     * Delete a schema
     */
    @Delete("/{schemaId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Delete schema", 
              description = "Deletes a JSON Schema definition")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schema deleted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SchemaOperationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found"),
            @ApiResponse(responseCode = "500", description = "Server error")
    })
    public Mono<HttpResponse<SchemaOperationResponse>> deleteSchema(
            @Parameter(description = "Schema ID", required = true, example = "embedder-config-schema")
            @PathVariable String schemaId) {
        
        LOG.info("Deleting schema: {}", schemaId);
        
        return schemaRegistryDelegate.deleteSchema(schemaId)
                .then(Mono.just(HttpResponse.ok(new SchemaOperationResponse(
                        true,
                        "Schema deleted successfully",
                        schemaId,
                        Collections.emptyList()
                ))))
                .<HttpResponse<SchemaOperationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    if (e instanceof SchemaNotFoundException) {
                        LOG.warn("Schema not found for deletion: {}", schemaId);
                        return Mono.just(HttpResponse.<SchemaOperationResponse>notFound());
                    } else {
                        LOG.error("Error deleting schema: {}", schemaId, e);
                        return Mono.just(HttpResponse.<SchemaOperationResponse>serverError()
                                .body(new SchemaOperationResponse(
                                        false,
                                        "Failed to delete schema",
                                        schemaId,
                                        Collections.singletonList(e.getMessage())
                                )));
                    }
                });
    }

    /**
     * Validate a JSON Schema
     */
    @Post("/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate schema syntax", 
              description = "Validates that a JSON Schema conforms to Draft 7 specification")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Validation result",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ValidationResponse.class)))
    })
    public Mono<HttpResponse<ValidationResponse>> validateSchema(
            @Body @Valid ValidateSchemaRequest request) {
        
        LOG.debug("Validating schema syntax");
        
        return schemaRegistryDelegate.validateSchemaSyntax(request.getSchemaContent())
                .map(validationMessages -> {
                    boolean isValid = validationMessages.isEmpty();
                    List<String> errors = validationMessages.stream()
                            .map(ValidationMessage::getMessage)
                            .collect(Collectors.toList());
                    
                    return HttpResponse.ok(new ValidationResponse(isValid, errors));
                })
                .<HttpResponse<ValidationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    LOG.error("Error validating schema syntax", e);
                    return Mono.just(HttpResponse.ok(new ValidationResponse(
                            false,
                            Collections.singletonList("Error during validation: " + e.getMessage())
                    )));
                });
    }

    /**
     * Validate JSON content against a schema
     */
    @Post("/validate-content")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Validate content against schema", 
              description = "Validates JSON content against a specified JSON Schema")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Validation result",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = ValidationResponse.class))),
            @ApiResponse(responseCode = "404", description = "Schema not found")
    })
    public Mono<HttpResponse<ValidationResponse>> validateContent(
            @Body @Valid ValidateContentRequest request) {
        
        LOG.debug("Validating content against schema: {}", request.getSchemaId());
        
        return schemaRegistryDelegate.getSchemaContent(request.getSchemaId())
                .flatMap(schemaContent -> 
                    schemaRegistryDelegate.validateContentAgainstSchema(
                            request.getJsonContent(), 
                            schemaContent
                    )
                )
                .map(validationMessages -> {
                    boolean isValid = validationMessages.isEmpty();
                    List<String> errors = validationMessages.stream()
                            .map(ValidationMessage::getMessage)
                            .collect(Collectors.toList());
                    
                    return HttpResponse.ok(new ValidationResponse(isValid, errors));
                })
                .<HttpResponse<ValidationResponse>>map(response -> response)
                .onErrorResume(e -> {
                    if (e instanceof SchemaNotFoundException) {
                        LOG.warn("Schema not found for validation: {}", request.getSchemaId());
                        return Mono.just(HttpResponse.<ValidationResponse>notFound());
                    } else {
                        LOG.error("Error validating content", e);
                        return Mono.just(HttpResponse.ok(new ValidationResponse(
                                false,
                                Collections.singletonList("Error during validation: " + e.getMessage())
                        )));
                    }
                });
    }

    // Request/Response DTOs
    
    @Serdeable
    @Schema(description = "Response containing list of schemas")
    public static class SchemaListResponse {
        @Schema(description = "List of schemas")
        @JsonProperty("schemas")
        private final List<SchemaInfo> schemas;
        
        public SchemaListResponse(List<SchemaInfo> schemas) {
            this.schemas = schemas != null ? schemas : Collections.emptyList();
        }
        
        @JsonProperty("schemas")
        public List<SchemaInfo> getSchemas() { return schemas; }
    }
    
    @Serdeable
    @Schema(description = "Schema information")
    public static class SchemaInfo {
        @Schema(description = "Schema identifier", example = "embedder-config-schema")
        @JsonProperty("schemaId")
        private final String schemaId;
        
        @Schema(description = "JSON Schema content", example = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}")
        @JsonProperty("schemaContent")
        private final String schemaContent;
        
        @Schema(description = "Timestamp when schema was last updated", example = "2024-01-15T10:30:00Z")
        @JsonProperty("updatedAt")
        private final String updatedAt;
        
        @Schema(description = "Description of the schema", example = "Configuration schema for embedder module")
        @JsonProperty("description")
        private final String description;
        
        public SchemaInfo(String schemaId, String schemaContent, String updatedAt, String description) {
            this.schemaId = schemaId;
            this.schemaContent = schemaContent;
            this.updatedAt = updatedAt;
            this.description = description;
        }
        
        @JsonProperty("schemaId")
        public String getSchemaId() { return schemaId; }
        
        @JsonProperty("schemaContent")
        public String getSchemaContent() { return schemaContent; }
        
        @JsonProperty("updatedAt")
        public String getUpdatedAt() { return updatedAt; }
        
        @JsonProperty("description")
        public String getDescription() { return description; }
    }
    
    @Serdeable
    @Schema(description = "Request to create a new schema")
    public static class CreateSchemaRequest {
        @NotBlank
        @Schema(description = "Schema identifier", required = true, example = "embedder-config-schema")
        private String schemaId;
        
        @NotBlank
        @Schema(description = "JSON Schema content", required = true, 
                example = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{\"apiKey\":{\"type\":\"string\"}}}")
        private String schemaContent;
        
        @Schema(description = "Description of the schema", example = "Configuration schema for embedder module")
        private String description;
        
        public CreateSchemaRequest() {}
        
        public String getSchemaId() { return schemaId; }
        public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
        
        public String getSchemaContent() { return schemaContent; }
        public void setSchemaContent(String schemaContent) { this.schemaContent = schemaContent; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
    
    @Serdeable
    @Schema(description = "Request to update an existing schema")
    public static class UpdateSchemaRequest {
        @NotBlank
        @Schema(description = "JSON Schema content", required = true,
                example = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\",\"properties\":{\"apiKey\":{\"type\":\"string\"},\"timeout\":{\"type\":\"integer\"}}}")
        private String schemaContent;
        
        @Schema(description = "Updated description of the schema", example = "Updated configuration schema for embedder module")
        private String description;
        
        public UpdateSchemaRequest() {}
        
        public String getSchemaContent() { return schemaContent; }
        public void setSchemaContent(String schemaContent) { this.schemaContent = schemaContent; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
    
    @Serdeable
    @Schema(description = "Request to validate a schema")
    public static class ValidateSchemaRequest {
        @NotBlank
        @Schema(description = "JSON Schema content to validate", required = true,
                example = "{\"$schema\":\"http://json-schema.org/draft-07/schema#\",\"type\":\"object\"}")
        private String schemaContent;
        
        public ValidateSchemaRequest() {}
        
        public String getSchemaContent() { return schemaContent; }
        public void setSchemaContent(String schemaContent) { this.schemaContent = schemaContent; }
    }
    
    @Serdeable
    @Schema(description = "Request to validate JSON content against a schema")
    public static class ValidateContentRequest {
        @NotBlank
        @Schema(description = "Schema ID to validate against", required = true, example = "embedder-config-schema")
        private String schemaId;
        
        @NotBlank
        @Schema(description = "JSON content to validate", required = true,
                example = "{\"apiKey\":\"sk-1234567890\",\"timeout\":30}")
        private String jsonContent;
        
        public ValidateContentRequest() {}
        
        public String getSchemaId() { return schemaId; }
        public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
        
        public String getJsonContent() { return jsonContent; }
        public void setJsonContent(String jsonContent) { this.jsonContent = jsonContent; }
    }
    
    @Serdeable
    @Schema(description = "Response for schema operations")
    public static class SchemaOperationResponse {
        @Schema(description = "Whether the operation was successful", example = "true")
        private final boolean success;
        
        @Schema(description = "Human-readable message about the operation", example = "Schema created successfully")
        private final String message;
        
        @Schema(description = "Schema ID that was operated on", example = "embedder-config-schema")
        private final String schemaId;
        
        @Schema(description = "List of validation errors if any", example = "[\"Missing required property: type\"]")
        private final List<String> validationErrors;
        
        public SchemaOperationResponse(boolean success, String message, String schemaId, List<String> validationErrors) {
            this.success = success;
            this.message = message;
            this.schemaId = schemaId;
            this.validationErrors = validationErrors != null ? validationErrors : Collections.emptyList();
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getSchemaId() { return schemaId; }
        public List<String> getValidationErrors() { return validationErrors; }
    }
    
    @Serdeable
    @Schema(description = "Validation result")
    public static class ValidationResponse {
        @Schema(description = "Whether the validation passed", example = "true")
        @JsonProperty("valid")
        private final boolean valid;
        
        @Schema(description = "List of validation errors if any", example = "[\"Invalid type: expected object, got string\"]")
        @JsonProperty("errors")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private final List<String> errors;
        
        public ValidationResponse(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors != null ? errors : Collections.emptyList();
        }
        
        @JsonProperty("valid")
        public boolean isValid() { return valid; }
        
        @JsonProperty("errors")
        public List<String> getErrors() { return errors; }
    }
}