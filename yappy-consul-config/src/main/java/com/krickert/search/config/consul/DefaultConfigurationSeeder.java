package com.krickert.search.config.consul;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct; // Use this for PostConstruct
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Singleton // Make it a regular Singleton
// @Context // You can keep @Context if you intend for it to be eligible for bootstrap context,
           // but its main job now is to run its @PostConstruct before DCM.
public class DefaultConfigurationSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultConfigurationSeeder.class);
    private final ConsulBusinessOperationsService consulOpsService;
    // Ensure this cluster name matches what DCM will try to load.
    // Your app.yml has app.config.cluster.name: yappy-cluster
    private final String defaultClusterNameToSeed = "yappy-cluster";

    public DefaultConfigurationSeeder(ConsulBusinessOperationsService consulOpsService) {
        this.consulOpsService = consulOpsService;
    }

    @PostConstruct // Logic now runs when this bean is initialized
    public void seedConsulWithDefaults() {
        LOG.info("DefaultConfigurationSeeder (@PostConstruct): Checking if default configuration seeding is required for cluster '{}'...", defaultClusterNameToSeed);

        if (this.consulOpsService == null) {
            LOG.error("DefaultConfigurationSeeder: ConsulBusinessOperationsService is null in @PostConstruct. Cannot proceed with seeding.");
            return;
        }

        try {
            Boolean clusterExists = consulOpsService.getPipelineClusterConfig(defaultClusterNameToSeed)
                .map(Optional::isPresent)
                .blockOptional()
                .orElse(false);

            if (Boolean.TRUE.equals(clusterExists)) {
                LOG.info("DefaultConfigurationSeeder: Default cluster configuration '{}' already exists in Consul. No seeding needed.", defaultClusterNameToSeed);
            } else {
                LOG.info("DefaultConfigurationSeeder: Default cluster configuration '{}' not found in Consul. Attempting to seed now...", defaultClusterNameToSeed);
                PipelineClusterConfig defaultConfigToSeed = createDefaultPipelineClusterConfig(defaultClusterNameToSeed);

                Boolean seededSuccessfully = consulOpsService.storeClusterConfiguration(defaultClusterNameToSeed, defaultConfigToSeed)
                    .blockOptional()
                    .orElse(false);

                if (Boolean.TRUE.equals(seededSuccessfully)) {
                    LOG.info("DefaultConfigurationSeeder: Successfully seeded default configuration for cluster '{}'.", defaultClusterNameToSeed);
                } else {
                    LOG.error("DefaultConfigurationSeeder: FAILED to seed default configuration for cluster '{}'. Please check Consul Business Operations Service logs.", defaultClusterNameToSeed);
                }
            }
        } catch (Exception e) {
            LOG.error("DefaultConfigurationSeeder: CRITICAL error during @PostConstruct seeding for cluster '{}'. Error: {}", defaultClusterNameToSeed, e.getMessage(), e);
        }
    }

    private PipelineClusterConfig createDefaultPipelineClusterConfig(String clusterName) {
        LOG.info("DefaultConfigurationSeeder: Creating a minimal, valid default PipelineClusterConfig for cluster: {}", clusterName);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        PipelineConfig defaultEmptyPipeline = new PipelineConfig("default-empty-pipeline", Collections.emptyMap());
        pipelines.put(defaultEmptyPipeline.name(), defaultEmptyPipeline);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Collections.emptyMap());
        Set<String> allowedKafkaTopics = Collections.emptySet();
        Set<String> allowedGrpcServices = Collections.emptySet();
        return new PipelineClusterConfig(clusterName, graphConfig, moduleMap, defaultEmptyPipeline.name(), allowedKafkaTopics, allowedGrpcServices);
    }
}