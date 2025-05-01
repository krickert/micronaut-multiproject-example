package com.krickert.search.config.consul.service;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.catalog.CatalogServicesRequest;
import com.ecwid.consul.v1.health.HealthServicesRequest;
import com.ecwid.consul.v1.health.model.Check;
import com.ecwid.consul.v1.health.model.HealthService;
import com.krickert.search.config.consul.model.ServiceStatusDto; // Import the DTO
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux; // Using Reactor for potential async benefits
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers; // For blocking calls

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service responsible for discovering services registered with Consul
 * and determining their status, particularly for pipeline services.
 */
@Singleton
public class ServiceDiscoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceDiscoveryService.class);
    private static final String PIPE_SERVICE_TAG = "grpc-pipeservice"; // Tag likely indicating a pipeline service

    private final ConsulClient consulClient;

    @Inject
    public ServiceDiscoveryService(ConsulClient consulClient) {
        this.consulClient = consulClient;
        LOG.info("ServiceDiscoveryService initialized.");
    }

    /**
     * Gets the status of all relevant pipeline services registered with Consul.
     * Filters services by name containing "pipe" or having the PIPE_SERVICE_TAG.
     *
     * @return A Mono emitting a List of ServiceStatusDto objects.
     */
    public Mono<List<ServiceStatusDto>> getPipelineServicesStatus() {
        LOG.debug("Fetching pipeline services status from Consul...");
        // Wrap the synchronous Consul call in a Mono using subscribeOn to avoid blocking the caller thread
        return Mono.fromCallable(() -> {
                    CatalogServicesRequest request = CatalogServicesRequest.newBuilder()
                            .setQueryParams(QueryParams.DEFAULT)
                            .build();
                    Response<Map<String, List<String>>> servicesResponse = consulClient.getCatalogServices(request);
                    return servicesResponse.getValue(); // Returns Map<String, List<String>>
                })
                .subscribeOn(Schedulers.boundedElastic()) // Execute blocking call on a dedicated thread pool
                .flatMapMany(servicesMap -> {
                    if (servicesMap == null || servicesMap.isEmpty()) {
                        LOG.info("No services found in Consul catalog.");
                        return Flux.empty(); // Return empty Flux if no services
                    }
                    LOG.debug("Found {} total services in Consul catalog. Filtering for pipeline services...", servicesMap.size());
                    // Filter for pipeline services
                    List<String> pipelineServiceNames = servicesMap.entrySet().stream()
                            .filter(entry -> isPipelineService(entry.getKey(), entry.getValue()))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toList());

                    LOG.debug("Found {} potential pipeline services: {}", pipelineServiceNames.size(), pipelineServiceNames);
                    // Fetch status for each pipeline service name concurrently
                    return Flux.fromIterable(pipelineServiceNames)
                               .flatMap(this::fetchServiceStatus); // Fetch status for each name
                })
                .collectList() // Collect all ServiceStatusDto into a List
                .doOnSuccess(list -> LOG.info("Successfully fetched status for {} pipeline services.", list.size()))
                .onErrorResume(e -> {
                    LOG.error("Error getting services from Consul: {}", e.getMessage(), e);
                    // Return an empty list on error
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Checks if a service is considered a pipeline service based on name or tags.
     */
    private boolean isPipelineService(String serviceName, List<String> tags) {
        boolean nameMatch = serviceName != null && serviceName.toLowerCase().contains("pipe");
        boolean tagMatch = tags != null && tags.stream()
                .anyMatch(tag -> tag.equalsIgnoreCase(PIPE_SERVICE_TAG) || tag.toLowerCase().contains("pipe"));
        return nameMatch || tagMatch;
    }

    /**
     * Fetches the detailed status (running, address, port, tags) for a single service.
     *
     * @param serviceName The name of the service.
     * @return A Mono emitting the ServiceStatusDto.
     */
    private Mono<ServiceStatusDto> fetchServiceStatus(String serviceName) {
        LOG.debug("Fetching health status for service: {}", serviceName);
        // Wrap synchronous call
        return Mono.fromCallable(() -> {
                    HealthServicesRequest request = HealthServicesRequest.newBuilder()
                            .setQueryParams(QueryParams.DEFAULT)
                            .setPassing(false) // Include services in any state initially
                            .build();
                    Response<List<HealthService>> healthResponse = consulClient.getHealthServices(serviceName, request);
                    return healthResponse.getValue();
                })
                .subscribeOn(Schedulers.boundedElastic()) // Use elastic scheduler for blocking call
                .map(healthServices -> {
                    if (healthServices == null || healthServices.isEmpty()) {
                        LOG.warn("No health information found for service: {}", serviceName);
                        // Service might be registered but have no instances or failed checks
                        return new ServiceStatusDto(serviceName, false, null, null, null);
                    }

                    // Determine overall running status (at least one instance passing all checks)
                    // A service instance is healthy if *all* its checks are passing.
                    // The service is considered "running" if at least one instance is healthy.
                    boolean isRunning = healthServices.stream()
                            .anyMatch(instance -> instance.getChecks().stream()
                                    .allMatch(check -> Check.CheckStatus.PASSING == check.getStatus()));

                    // Get details from the first healthy instance if available, otherwise first instance
                    HealthService representativeInstance = healthServices.stream()
                            .filter(instance -> instance.getChecks().stream().allMatch(check -> Check.CheckStatus.PASSING == check.getStatus()))
                            .findFirst()
                            .orElse(healthServices.get(0)); // Fallback to the first instance

                    HealthService.Service serviceDetails = representativeInstance.getService();
                    String address = serviceDetails.getAddress();
                    // Use fallback if address is empty (Consul might return empty if using node address)
                    if (address == null || address.isEmpty()) {
                         address = representativeInstance.getNode().getAddress();
                    }

                    return new ServiceStatusDto(
                            serviceName,
                            isRunning,
                            address,
                            serviceDetails.getPort(),
                            serviceDetails.getTags() == null ? Collections.emptyList() : serviceDetails.getTags() // Ensure tags list is not null
                    );
                })
                .onErrorResume(e -> {
                     LOG.error("Error fetching health status for service '{}': {}", serviceName, e.getMessage());
                     // Return a DTO indicating error/unknown status
                     return Mono.just(new ServiceStatusDto(serviceName, false, null, null, Collections.emptyList()));
                });
    }
}