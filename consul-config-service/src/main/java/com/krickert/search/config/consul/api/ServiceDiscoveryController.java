package com.krickert.search.config.consul.api;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.health.HealthCheck;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for service discovery in Consul.
 * Provides endpoints for querying services registered with Consul.
 */
@Controller("/api/services")
@Tag(name = "Service Discovery", description = "API for discovering services registered with Consul")
public class ServiceDiscoveryController {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceDiscoveryController.class);
    private static final String PIPE_SERVICE_TAG = "grpc-pipeservice";

    private final Consul consulClient;

    /**
     * Creates a new ServiceDiscoveryController with the specified Consul client.
     *
     * @param consulClient the client for interacting with Consul
     */
    @Inject
    public ServiceDiscoveryController(@jakarta.inject.Named("primaryConsulClient") Consul consulClient) {
        this.consulClient = consulClient;
        LOG.info("ServiceDiscoveryController initialized");
    }

    /**
     * Gets a list of all PipeService gRPC services registered with Consul.
     * Checks if each service is running and includes that information in the response.
     *
     * @return a JSON response with the list of services and their status
     */
    @Operation(
        summary = "Get PipeService gRPC services",
        description = "Retrieves a list of all PipeService gRPC services registered with Consul and their running status"
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200", 
            description = "List of services retrieved successfully",
            content = @Content(mediaType = "application/json", 
                schema = @Schema(implementation = Map.class))
        ),
        @ApiResponse(
            responseCode = "500", 
            description = "Error retrieving services from Consul"
        )
    })
    @Get(produces = MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String, Object>> getServices() {
        LOG.info("GET request for PipeService gRPC services");

        try {
            // Get all services from Consul catalog
            Map<String, List<String>> services = consulClient.catalogClient().getServices().getResponse();
            LOG.debug("Found {} services in Consul catalog", services.size());

            // Filter services with the PipeService tag or name containing "pipe"
            List<String> pipeServices = services.entrySet().stream()
                    .filter(entry -> {
                        String serviceName = entry.getKey();
                        List<String> tags = entry.getValue();
                        return serviceName.toLowerCase().contains("pipe") || 
                               (tags != null && tags.stream().anyMatch(tag -> 
                                   tag.equals(PIPE_SERVICE_TAG) || tag.toLowerCase().contains("pipe")));
                    })
                    .map(Map.Entry::getKey)
                    .toList();

            LOG.debug("Found {} PipeService gRPC services", pipeServices.size());

            if (pipeServices.isEmpty()) {
                Map<String, Object> emptyResponse = new HashMap<>();
                emptyResponse.put("services", new ArrayList<>());
                return HttpResponse.ok(emptyResponse);
            }

            // Get health status for each service
            List<Map<String, Object>> serviceStatuses = pipeServices.stream()
                    .map(this::getServiceStatus)
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("services", serviceStatuses);
            return HttpResponse.ok(response);

        } catch (Exception e) {
            LOG.error("Error getting services from Consul", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to get services from Consul: " + e.getMessage());
            return HttpResponse.serverError(errorResponse);
        }
    }

    /**
     * Gets the status of a specific service.
     *
     * @param serviceName the name of the service
     * @return a Map with the service status information
     */
    private Map<String, Object> getServiceStatus(String serviceName) {
        try {
            // Get health services for the specified service name
            List<ServiceHealth> healthServices = consulClient.healthClient().getHealthyServiceInstances(serviceName).getResponse();

            // Check if the service is running (all checks are passing)
            boolean isRunning = !healthServices.isEmpty();

            Map<String, Object> serviceStatus = new HashMap<>();
            serviceStatus.put("name", serviceName);
            serviceStatus.put("running", isRunning);

            // Add node information if available
            if (!healthServices.isEmpty()) {
                ServiceHealth firstService = healthServices.get(0);

                // Only add address if it's not null
                String address = firstService.getService().getAddress();
                if (address != null && !address.isEmpty()) {
                    serviceStatus.put("address", address);
                } else {
                    // Use node address as fallback or a default value
                    serviceStatus.put("address", firstService.getNode().getAddress() != null ? 
                                    firstService.getNode().getAddress() : "localhost");
                }

                // Only add port if it's not null
                Integer port = firstService.getService().getPort();
                if (port != null) {
                    serviceStatus.put("port", port);
                } else {
                    // Use a default port value
                    serviceStatus.put("port", 8500); // Default Consul port as fallback
                }

                if (firstService.getService().getTags() != null && !firstService.getService().getTags().isEmpty()) {
                    serviceStatus.put("tags", firstService.getService().getTags());
                }
            }

            return serviceStatus;
        } catch (Exception e) {
            LOG.error("Error getting health status for service: {}", serviceName, e);
            Map<String, Object> errorStatus = new HashMap<>();
            errorStatus.put("name", serviceName);
            errorStatus.put("running", false);
            errorStatus.put("error", "Failed to get health status: " + e.getMessage());
            return errorStatus;
        }
    }
}
