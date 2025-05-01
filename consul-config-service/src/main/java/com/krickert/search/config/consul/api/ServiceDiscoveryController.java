package com.krickert.search.config.consul.api;

// Removed ConsulClient and related model imports
import com.krickert.search.config.consul.model.ServiceStatusDto; // Import the DTO
import com.krickert.search.config.consul.service.ServiceDiscoveryService; // Import the new Service
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono; // Import Mono

import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * REST controller for service discovery in Consul.
 * Provides endpoints for querying services registered with Consul.
 * Delegates logic to ServiceDiscoveryService.
 */
@Controller("/api/services")
@Tag(name = "Service Discovery", description = "API for discovering services registered with Consul")
public class ServiceDiscoveryController {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceDiscoveryController.class);

    // Inject the new service instead of ConsulClient
    private final ServiceDiscoveryService serviceDiscoveryService;

    @Inject
    public ServiceDiscoveryController(ServiceDiscoveryService serviceDiscoveryService) {
        this.serviceDiscoveryService = serviceDiscoveryService;
        LOG.info("ServiceDiscoveryController initialized.");
    }

    // ... (imports and class definition remain the same)
    /**
     * Gets a list of all relevant pipeline services registered with Consul.
     * Checks if each service is running and includes that information in the response.
     *
     * @return a JSON response containing a list of service statuses.
     */
    @Operation(
            summary = "Get Pipeline Services Status",
            description = "Retrieves a list of pipeline services registered with Consul and their running status"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of service statuses retrieved successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ServiceListResponse.class))
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Error retrieving services from Consul"
            )
    })
    @Get(produces = MediaType.APPLICATION_JSON)
    public Mono<HttpResponse<Map<String, Object>>> getServices() {
        LOG.info("GET request for pipeline services status");

        return serviceDiscoveryService.getPipelineServicesStatus()
                .map(serviceStatuses -> {
                    Map<String, Object> responseBody = new HashMap<>();
                    responseBody.put("services", serviceStatuses);
                    LOG.debug("Returning status for {} services", serviceStatuses.size());
                    // Explicitly return the immutable interface type if needed,
                    // though ok() typically returns MutableHttpResponse, implicit conversion usually works.
                    // Let's be safe and cast if necessary, though often not required here.
                    return (HttpResponse<Map<String, Object>>) HttpResponse.ok(responseBody);
                })
                .onErrorResume(e -> {
                    LOG.error("Error retrieving service statuses: {}", e.getMessage(), e);
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("error", "Failed to retrieve service statuses: " + e.getMessage());
                    // --- CORRECTION HERE ---
                    // Explicitly cast MutableHttpResponse to the expected HttpResponse interface type.
                    MutableHttpResponse<Map<String, Object>> mutableErrorResponse = HttpResponse.serverError(errorResponse);
                    return Mono.just((HttpResponse<Map<String, Object>>) mutableErrorResponse);
                    // --- END CORRECTION ---
                });
    }

    // --- Helper class for Swagger documentation clarity (remains the same) ---
    @Schema(name = "ServiceListResponse", description = "Response containing a list of service statuses")
    private static class ServiceListResponse {
        @Schema(description = "List of discovered pipeline services and their status")
        public List<ServiceStatusDto> services;
    }
    // Removed getServiceStatus private helper method, as its logic is now in ServiceDiscoveryService
}