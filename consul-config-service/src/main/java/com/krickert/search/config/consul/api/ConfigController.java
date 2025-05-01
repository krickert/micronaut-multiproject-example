package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ConfigChangeNotifier;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpResponseFactory; // Import needed
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * REST controller for managing configuration in Consul KV store.
 * Provides endpoints for reading, writing, and deleting configuration values.
 */
@Controller("/config")
@Tag(name = "Configuration", description = "API for managing configuration in Consul KV store")
public class ConfigController {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigController.class);
    // Use the standard response factory
    private static final HttpResponseFactory RESPONSE_FACTORY = HttpResponseFactory.INSTANCE;


    private final ConsulKvService consulKvService;
    private final ApplicationEventPublisher eventPublisher;
    private final ConfigChangeNotifier configChangeNotifier;
    private final ObjectMapper objectMapper; // Inject ObjectMapper directly

    /**
     * Creates a new ConfigController with the specified services.
     *
     * @param consulKvService the service for interacting with Consul KV store
     * @param eventPublisher the publisher for application events
     * @param configChangeNotifier the notifier for configuration changes
     * @param objectMapper Micronaut's configured Jackson ObjectMapper
     */
    @Inject
    public ConfigController(ConsulKvService consulKvService,
                            ApplicationEventPublisher eventPublisher,
                            ConfigChangeNotifier configChangeNotifier,
                            ObjectMapper objectMapper) { // Inject ObjectMapper
        this.consulKvService = consulKvService;
        this.eventPublisher = eventPublisher;
        this.configChangeNotifier = configChangeNotifier;
        this.objectMapper = objectMapper; // Store injected ObjectMapper
        LOG.info("ConfigController initialized");
    }

    /**
     * Gets a configuration value from Consul KV store.
     * Attempts to return JSON if Accept header allows and value is valid JSON,
     * otherwise returns text/plain. Handles potential byte array representations.
     *
     * @param keyPath the path to the key
     * @param acceptHeader The Accept header from the request
     * @return the value if found (with appropriate Content-Type), or 404 if not found
     */
    @Operation(
            summary = "Get configuration value",
            description = "Retrieves a configuration value from Consul KV store by its key path"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuration value found and returned"),
            @ApiResponse(responseCode = "404", description = "Configuration key not found")
    })
    @Get(value = "/{keyPath:.+}", produces = {MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    public Mono<HttpResponse<String>> getConfig(
            @Parameter(description = "Path to the configuration key", required = true) String keyPath,
            @Header(name = "Accept", defaultValue = MediaType.TEXT_PLAIN) String acceptHeader) {
        LOG.info("GET request for key: {}", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.getValue(fullPath) // Returns Mono<Optional<String>>
                .flatMap(optionalValue -> {
                    // Handle case where key is not found in Consul
                    if (optionalValue.isEmpty()) {
                        LOG.debug("No value found for key: {}", fullPath);
                        // Fix: Use factory and explicit type for notFound()
                        HttpResponse<String> notFoundResponse = RESPONSE_FACTORY.status(HttpStatus.NOT_FOUND);
                        return Mono.just(notFoundResponse);
                    }

                    // Key found, extract the raw string value.
                    String rawValueFromConsul = optionalValue.get();
                    LOG.debug("Found raw value for key {}: '{}'", fullPath, rawValueFromConsul);

                    String responseBody = rawValueFromConsul;
                    MediaType responseType = MediaType.TEXT_PLAIN_TYPE;

                    boolean isLikelyByteArrayString = rawValueFromConsul.startsWith("[B@");

                    if (isLikelyByteArrayString) {
                        LOG.warn("Value for key {} appears to be byte array representation '{}'. Returning as text/plain.", fullPath, rawValueFromConsul);
                        responseBody = rawValueFromConsul;
                        responseType = MediaType.TEXT_PLAIN_TYPE;
                    } else if (acceptHeader.contains(MediaType.APPLICATION_JSON)) {
                        try {
                            objectMapper.readTree(responseBody); // Validate JSON structure
                            responseType = MediaType.APPLICATION_JSON_TYPE;
                            LOG.debug("Value for key {} parsed successfully as JSON.", fullPath);
                        } catch (JsonProcessingException e) {
                            LOG.debug("Value for key {} is not valid JSON (parse failed: {}). Returning as text/plain.", fullPath, e.getMessage());
                            responseType = MediaType.TEXT_PLAIN_TYPE;
                        } catch (Exception e) {
                            LOG.warn("Unexpected error parsing value for key {} as JSON. Returning as text/plain.", fullPath, e);
                            responseType = MediaType.TEXT_PLAIN_TYPE;
                        }
                    } else {
                        LOG.debug("Returning value for key {} as text/plain (Accept header: '{}').", fullPath, acceptHeader);
                        responseType = MediaType.TEXT_PLAIN_TYPE;
                    }

                    LOG.debug("Final response for key {}: Type='{}', Body='{}'", fullPath, responseType, responseBody);
                    // Construct the response explicitly typed
                    HttpResponse<String> okResponse = RESPONSE_FACTORY.ok(responseBody).contentType(responseType);
                    return Mono.just(okResponse);

                })
                .onErrorResume(e -> {
                    // Handle errors during the consulKvService.getValue() call
                    LOG.error("Error retrieving value for key {}: {}", fullPath, e.getMessage(), e);
                    // Fix: Use factory and explicit type for serverError()
                    HttpResponse<String> errorResponse = RESPONSE_FACTORY.status(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Error retrieving " +
                            "configuration value");
                    return Mono.just(errorResponse);
                });
    }

    // --- Other methods (PUT, DELETE, refresh) ---
    // Make sure updateConfigJson uses the injected objectMapper as corrected previously

    /**
     * Puts a plain text configuration value into Consul KV store.
     * ... (method content as before) ...
     */
    @Operation(
            summary = "Update configuration with plain text",
            description = "Updates a configuration value in Consul KV store with a plain text value"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuration value updated successfully"),
            @ApiResponse(responseCode = "500", description = "Failed to update configuration value")
    })
    @Put(value = "/{keyPath:.+}", consumes = MediaType.TEXT_PLAIN)
    public Mono<HttpResponse<?>> updateConfigRaw(
            @Parameter(description = "Path to the configuration key", required = true) String keyPath,
            @Parameter(description = "Plain text value to store", required = true) @Body String value) {
        LOG.info("PUT request for key: {} with raw value", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.putValue(fullPath, value)
                .flatMap(success -> {
                    if (success) {
                        LOG.debug("Successfully updated key: {}", fullPath);
                        // Notify about configuration change
                        configChangeNotifier.notifyConfigChange(fullPath);
                        return Mono.just(HttpResponse.ok());
                    } else {
                        LOG.error("Failed to update key: {}", fullPath);
                        return Mono.just(HttpResponse.serverError("Failed to update Consul KV"));
                    }
                });
    }


    /**
     * Puts a JSON configuration value into Consul KV store.
     * ... (method content as before, ensuring objectMapper is used) ...
     */
    @Operation(
            summary = "Update configuration with JSON",
            description = "Updates a configuration value in Consul KV store with a JSON value"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuration value updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid JSON payload"),
            @ApiResponse(responseCode = "500", description = "Failed to update configuration value")
    })
    @Put(value = "/{keyPath:.+}", consumes = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<?>> updateConfigJson(
            @Parameter(description = "Path to the configuration key", required = true) String keyPath,
            @Parameter(description = "JSON value to store", required = true) @Body Map<String, Object> value) {
        LOG.info("PUT request for key: {} with JSON value", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        try {
            // Convert Map to JSON string using the injected objectMapper
            String jsonValue = objectMapper.writeValueAsString(value); // Use injected mapper

            return consulKvService.putValue(fullPath, jsonValue)
                    .flatMap(success -> {
                        if (success) {
                            LOG.debug("Successfully updated key: {}", fullPath);
                            // Notify about configuration change
                            configChangeNotifier.notifyConfigChange(fullPath);
                            return Mono.just(HttpResponse.ok());
                        } else {
                            LOG.error("Failed to update key: {}", fullPath);
                            return Mono.just(HttpResponse.serverError("Failed to update Consul KV"));
                        }
                    });
        } catch (Exception e) {
            LOG.error("Error processing JSON for key: {}", keyPath, e);
            return Mono.just(HttpResponse.badRequest("Invalid JSON payload"));
        }
    }


    /**
     * Deletes a configuration key from Consul KV store.
     * ... (method content as before) ...
     */
    @Operation(
            summary = "Delete configuration",
            description = "Deletes a configuration key from Consul KV store"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Configuration key deleted successfully"),
            @ApiResponse(responseCode = "500", description = "Failed to delete configuration key")
    })
    @Delete("/{keyPath:.+}")
    public Mono<HttpResponse<?>> deleteConfig(
            @Parameter(description = "Path to the configuration key to delete", required = true) String keyPath) {
        LOG.info("DELETE request for key: {}", keyPath);
        String fullPath = consulKvService.getFullPath(keyPath);

        return consulKvService.deleteKey(fullPath)
                .flatMap(success -> {
                    if (success) {
                        LOG.debug("Successfully deleted key: {}", fullPath);
                        // Notify about configuration change
                        configChangeNotifier.notifyConfigChange(fullPath);
                        return Mono.just(HttpResponse.noContent());
                    } else {
                        LOG.error("Failed to delete key: {}", fullPath);
                        return Mono.just(HttpResponse.serverError("Failed to delete key from Consul KV"));
                    }
                });
    }


    /**
     * Triggers a refresh of all @Refreshable beans.
     * ... (method content as before) ...
     */
    @Operation(
            summary = "Refresh configuration",
            description = "Triggers a refresh of all @Refreshable beans to reload configuration from Consul"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refresh triggered successfully")
    })
    @Post("/refresh")
    public HttpResponse<?> refresh() {
        LOG.info("Refresh request received");
        eventPublisher.publishEvent(new RefreshEvent());
        return HttpResponse.ok();
    }
}