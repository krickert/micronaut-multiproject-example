package com.krickert.search.pipeline.status; // Your package

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.service.model.ServiceAggregatedStatus;
import com.krickert.search.config.service.model.ServiceOperationalStatus;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.catalog.CatalogService; // Import for CatalogService
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class ServiceStatusAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceStatusAggregator.class);
    private static final String STATUS_KV_PREFIX = "yappy/status/services/"; // Make this configurable later
    private static final String CONFIG_DIGEST_TAG_PREFIX = "yappy-config-digest=";

    private final DynamicConfigurationManager dynamicConfigurationManager;
    private final ConsulBusinessOperationsService consulBusinessOpsService;
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;

    public ServiceStatusAggregator(DynamicConfigurationManager dynamicConfigurationManager,
                                   ConsulBusinessOperationsService consulBusinessOpsService,
                                   ConsulKvService consulKvService,
                                   ObjectMapper objectMapper) {
        this.dynamicConfigurationManager = dynamicConfigurationManager;
        this.consulBusinessOpsService = consulBusinessOpsService;
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = "30s", initialDelay = "10s")
    public void aggregateAndStoreServiceStatuses() {
        LOG.debug("Starting scheduled aggregation of service statuses...");
        Optional<PipelineClusterConfig> clusterConfigOpt = dynamicConfigurationManager.getCurrentPipelineClusterConfig();

        if (clusterConfigOpt.isEmpty()) {
            LOG.warn("No current PipelineClusterConfig available. Skipping status aggregation cycle.");
            return;
        }

        PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
        if (clusterConfig.pipelineModuleMap() == null || clusterConfig.pipelineModuleMap().availableModules() == null) {
            LOG.info("No modules defined in the current cluster config. Nothing to aggregate status for.");
            return;
        }

        // Fetch these once per aggregation cycle
        boolean isClusterConfigStale = dynamicConfigurationManager.isCurrentConfigStale();
        String activeClusterConfigVersion = dynamicConfigurationManager.getCurrentConfigVersionIdentifier().orElse("unknown");

        Flux.fromIterable(clusterConfig.pipelineModuleMap().availableModules().entrySet())
                .flatMap(entry -> buildStatusForService(
                        entry.getKey(),
                        entry.getValue(),
                        isClusterConfigStale,
                        activeClusterConfigVersion
                ))
                .flatMap(this::storeServiceStatusInKv)
                .doOnError(error -> LOG.error("Error during service status aggregation and storage: {}", error.getMessage(), error))
                .doOnComplete(() -> LOG.debug("Finished scheduled aggregation of service statuses."))
                .subscribe();
    }

    private Mono<ServiceAggregatedStatus> buildStatusForService(String serviceName,
                                                                PipelineModuleConfiguration moduleConfig,
                                                                boolean isClusterConfigStale,
                                                                String activeClusterConfigVersion) {

        Mono<List<CatalogService>> allInstancesListMono = consulBusinessOpsService.getServiceInstances(serviceName)
                .defaultIfEmpty(Collections.emptyList());
        Mono<Integer> healthyInstancesCountMono = consulBusinessOpsService.getHealthyServiceInstances(serviceName)
                .map(List::size)
                .defaultIfEmpty(0);

        return Mono.zip(allInstancesListMono, healthyInstancesCountMono)
                .map(tuple -> {
                    List<CatalogService> allInstances = tuple.getT1();
                    int healthyInstancesCount = tuple.getT2();
                    int totalInstancesCount = allInstances.size();

                    ServiceOperationalStatus status;
                    String statusDetail;
                    List<String> errorMessages = new java.util.ArrayList<>(); // Use a mutable list for errors

                    String reportedConfigDigest = extractConfigDigestFromInstances(allInstances);
                    String expectedCustomConfigDigest = null;
                    boolean configurationErrorDetected = false;

                    if (moduleConfig.customConfig() != null && !moduleConfig.customConfig().isEmpty()) {
                        expectedCustomConfigDigest = generateDigestForCustomConfig(moduleConfig.customConfig());

                        if (totalInstancesCount > 0) { // Only check instance configs if instances exist
                            if (reportedConfigDigest == null) {
                                configurationErrorDetected = true;
                                String msg = "Instance(s) running but MISSING config digest; expected digest: " + expectedCustomConfigDigest;
                                LOG.warn("Configuration Error for {}: {}", serviceName, msg);
                                errorMessages.add(msg);
                            } else if (reportedConfigDigest.startsWith("INCONSISTENT_DIGESTS:")) {
                                configurationErrorDetected = true;
                                String msg = "Instance(s) running with INCONSISTENT config digests. Reported: " + reportedConfigDigest + "; Expected: " + expectedCustomConfigDigest;
                                LOG.warn("Configuration Error for {}: {}", serviceName, msg);
                                errorMessages.add(msg);
                            } else if (expectedCustomConfigDigest != null && !expectedCustomConfigDigest.equals(reportedConfigDigest)) {
                                configurationErrorDetected = true;
                                String msg = "Instance(s) running with MISMATCHED config digest. Reported: " + reportedConfigDigest + "; Expected: " + expectedCustomConfigDigest;
                                LOG.warn("Configuration Error for {}: {}", serviceName, msg);
                                errorMessages.add(msg);
                            }
                        }
                    } else if (reportedConfigDigest != null) {
                        // Module has no custom config defined, but instances report a digest. This is also a mismatch.
                        configurationErrorDetected = true;
                        String msg = "Instance(s) reported config digest '" + reportedConfigDigest + "' but NO custom config is defined for module.";
                        LOG.warn("Configuration Error for {}: {}", serviceName, msg);
                        errorMessages.add(msg);
                    }


                    if (healthyInstancesCount > 0) {
                        if (configurationErrorDetected) {
                            status = ServiceOperationalStatus.CONFIGURATION_ERROR;
                            statusDetail = healthyInstancesCount + " healthy instance(s) found, but with configuration errors.";
                        } else if (isClusterConfigStale) {
                            status = ServiceOperationalStatus.DEGRADED_OPERATIONAL;
                            statusDetail = healthyInstancesCount + " healthy instance(s) found, but operating on a STALE cluster configuration.";
                        } else {
                            status = ServiceOperationalStatus.ACTIVE_HEALTHY;
                            statusDetail = healthyInstancesCount + " healthy instance(s) found.";
                        }
                    } else if (totalInstancesCount > 0) {
                        // If instances exist but none are healthy, it's UNAVAILABLE regardless of config.
                        // Config errors might be secondary or contributing, but unavailability is primary.
                        status = ServiceOperationalStatus.UNAVAILABLE;
                        statusDetail = totalInstancesCount + " instance(s) found, but none are healthy.";
                        if (configurationErrorDetected) { // Add config error as secondary info
                            errorMessages.add(0, "Additionally, configuration issues detected (see other errors).");
                        }
                    } else {
                        // No instances registered
                        // TODO: Differentiate between DEFINED and AWAITING_HEALTHY_REGISTRATION
                        status = ServiceOperationalStatus.AWAITING_HEALTHY_REGISTRATION;
                        statusDetail = "No instances registered in Consul.";
                        if (moduleConfig.customConfig() != null && !moduleConfig.customConfig().isEmpty() && expectedCustomConfigDigest == null) {
                            // This case implies an error in generating the expected digest itself.
                            errorMessages.add("Could not generate expected custom config digest for module definition.");
                        }
                    }

                    return new ServiceAggregatedStatus(
                            serviceName,
                            status,
                            statusDetail,
                            System.currentTimeMillis(),
                            totalInstancesCount,
                            healthyInstancesCount,
                            false, // isLocalInstanceActive - TODO
                            null,  // activeLocalInstanceId - TODO
                            false, // isProxying - TODO
                            null,  // proxyTargetInstanceId - TODO
                            isClusterConfigStale,
                            activeClusterConfigVersion,
                            reportedConfigDigest, // Keep reporting what instances have
                            errorMessages,        // Populate error messages
                            Collections.emptyMap()   // additionalAttributes - TODO
                    );
                });
    }

    private String extractConfigDigestFromInstances(List<CatalogService> instances) {
        if (instances == null || instances.isEmpty()) {
            return null;
        }

        Set<String> digests = instances.stream()
                .map(CatalogService::getServiceTags)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(tag -> tag != null && tag.startsWith(CONFIG_DIGEST_TAG_PREFIX))
                .map(tag -> tag.substring(CONFIG_DIGEST_TAG_PREFIX.length()))
                .collect(Collectors.toSet());

        if (digests.isEmpty()) {
            return null;
        }
        if (digests.size() == 1) {
            return digests.iterator().next();
        }
        // Multiple different digests found, indicating inconsistency
        LOG.warn("Multiple different config digests found for service instances: {}", digests);
        return "INCONSISTENT_DIGESTS:" + String.join(",", digests); // Or handle as an error/specific status
    }

    private Mono<Void> storeServiceStatusInKv(ServiceAggregatedStatus status) {
        try {
            String jsonStatus = objectMapper.writeValueAsString(status);
            String kvKey = STATUS_KV_PREFIX + status.serviceName();
            LOG.trace("Storing status for service '{}' at KV key '{}': {}", status.serviceName(), kvKey, jsonStatus);
            return consulKvService.putValue(kvKey, jsonStatus)
                    .doOnSuccess(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            LOG.debug("Successfully stored status for service '{}'", status.serviceName());
                        } else {
                            LOG.warn("Failed to store status for service '{}' (putValue returned false)", status.serviceName());
                        }
                    })
                    .then();
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize ServiceAggregatedStatus for service '{}': {}", status.serviceName(), e.getMessage(), e);
            return Mono.error(e);
        }
    }
    // In ServiceStatusAggregator.java

    // (Consider moving this to a shared utility class if DynamicConfigurationManager also needs it for Maps,
    // or if you want to ensure the exact same hashing logic is used. For now, keeping it here is fine.)
    private String generateDigestForCustomConfig(Map<String, Object> customConfig) {
        if (customConfig == null || customConfig.isEmpty()) {
            return null; // Or a predefined string like "NO_CUSTOM_CONFIG" if that's meaningful
        }
        try {
            // Ensure canonical serialization if possible (e.g., sorted map keys)
            // For ObjectMapper, you might need to configure it for sorted map keys
            // or use a library that produces canonical JSON.
            // For simplicity, we'll use default map serialization here.
            String jsonConfig = objectMapper.writeValueAsString(customConfig);

            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(jsonConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize custom config to JSON for digest generation: {}", e.getMessage(), e);
            return "SERIALIZATION_ERROR_DIGEST"; // Fallback digest
        } catch (java.security.NoSuchAlgorithmException e) {
            LOG.error("MD5 algorithm not found for custom config digest generation: {}", e.getMessage(), e);
            return "HASHING_ERROR_DIGEST"; // Fallback digest
        }
    }
}