package com.krickert.search.config.service;

import com.krickert.search.config.model.PipelineClusterConfig;
import com.krickert.search.config.model.PipelineConfig;
import com.krickert.search.config.model.PipelineModuleConfiguration;
import com.krickert.search.config.model.PipelineStepConfig;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Default implementation of the PipelineClusterLoader interface.
 * This implementation loads pipeline cluster configurations from a JSON source
 * and validates them against their schemas.
 */
@Singleton
@Primary
public class DefaultPipelineClusterLoader implements PipelineClusterLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultPipelineClusterLoader.class);

    private final ObjectMapper objectMapper;
    private PipelineClusterConfig currentCluster;
    private String validationErrors;

    /**
     * Constructor with required dependencies.
     *
     * @param objectMapper the object mapper for JSON serialization/deserialization
     */
    @Inject
    public DefaultPipelineClusterLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @Nullable
    public PipelineClusterConfig loadCluster(@NonNull String clusterName) {
        // In a real implementation, this would load from Consul or another config source
        try {
            // Load the configuration from the test resources
            // In a real implementation, this would come from Consul or another config source
            PipelineClusterConfig clusterConfig;
            try (var inputStream = getClass().getClassLoader().getResourceAsStream("pipeline-cluster-config.json")) {
                if (inputStream == null) {
                    LOG.error("Could not find pipeline-cluster-config.json in resources");
                    this.validationErrors = "Could not find pipeline-cluster-config.json in resources";
                    return null;
                }
                clusterConfig = objectMapper.readValue(inputStream, PipelineClusterConfig.class);
            }

            // Override the cluster name if it doesn't match the requested one
            if (!clusterName.equals(clusterConfig.getClusterName())) {
                clusterConfig.setClusterName(clusterName);
            }

            if (validateClusterConfig(clusterConfig)) {
                this.currentCluster = clusterConfig;
                return clusterConfig;
            } else {
                LOG.error("Failed to validate cluster configuration: {}", validationErrors);
                return null;
            }
        } catch (IOException e) {
            LOG.error("Failed to load cluster configuration", e);
            this.validationErrors = "Failed to load cluster configuration: " + e.getMessage();
            return null;
        }
    }

    @Override
    @Nullable
    public PipelineClusterConfig getCurrentCluster() {
        return currentCluster;
    }

    @Override
    public boolean validateClusterConfig(@NonNull PipelineClusterConfig clusterConfig) {
        // Validate the cluster configuration
        if (clusterConfig.getClusterName() == null || clusterConfig.getClusterName().isEmpty()) {
            this.validationErrors = "Cluster name cannot be empty";
            return false;
        }

        if (clusterConfig.getPipelineGraphConfig() == null) {
            this.validationErrors = "Pipeline graph configuration cannot be null";
            return false;
        }

        Map<String, PipelineConfig> pipelineGraph = clusterConfig.getPipelineGraphConfig().getPipelineGraph();
        if (pipelineGraph == null) {
            this.validationErrors = "Pipeline graph cannot be null";
            return false;
        }

        // Validate module configurations if present
        Map<String, PipelineModuleConfiguration> moduleConfigurations = clusterConfig.getModuleConfigurations();
        if (moduleConfigurations == null) {
            this.validationErrors = "Module configurations cannot be null";
            return false;
        }

        // Validate each module configuration
        for (Map.Entry<String, PipelineModuleConfiguration> entry : moduleConfigurations.entrySet()) {
            PipelineModuleConfiguration moduleConfig = entry.getValue();
            if (moduleConfig.getImplementationName() == null || moduleConfig.getImplementationName().isEmpty()) {
                this.validationErrors = "Module implementation name cannot be empty for module ID: " + entry.getKey();
                return false;
            }

            if (moduleConfig.getImplementationId() == null || moduleConfig.getImplementationId().isEmpty()) {
                this.validationErrors = "Module implementation ID cannot be empty for module: " + moduleConfig.getImplementationName();
                return false;
            }

            // Custom config JSON schema can be empty or null, in which case the custom config
            // will be treated as a simple JSON object without schema validation
        }

        // Validate each pipeline configuration
        for (Map.Entry<String, PipelineConfig> entry : pipelineGraph.entrySet()) {
            PipelineConfig pipelineConfig = entry.getValue();
            if (pipelineConfig.getName() == null || pipelineConfig.getName().isEmpty()) {
                this.validationErrors = "Pipeline name cannot be empty for pipeline ID: " + entry.getKey();
                return false;
            }

            Map<String, PipelineStepConfig> pipelineSteps = pipelineConfig.getPipelineSteps();
            if (pipelineSteps == null) {
                this.validationErrors = "Pipeline steps cannot be null for pipeline: " + pipelineConfig.getName();
                return false;
            }

            // Validate each pipeline step configuration
            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipelineSteps.entrySet()) {
                PipelineStepConfig stepConfig = stepEntry.getValue();
                if (stepConfig.getPipelineStepId() == null || stepConfig.getPipelineStepId().isEmpty()) {
                    this.validationErrors = "Pipeline step ID cannot be empty for step: " + stepEntry.getKey();
                    return false;
                }

                if (stepConfig.getPipelineImplementationId() == null || stepConfig.getPipelineImplementationId().isEmpty()) {
                    this.validationErrors = "Pipeline implementation ID cannot be empty for step: " + stepConfig.getPipelineStepId();
                    return false;
                }

                // Verify that the pipeline implementation ID exists in the module configurations
                if (!moduleConfigurations.containsKey(stepConfig.getPipelineImplementationId())) {
                    this.validationErrors = "Pipeline implementation ID not found in module configurations: " + stepConfig.getPipelineImplementationId();
                    return false;
                }

                // Validate custom configuration if present
                if (stepConfig.getCustomConfig() != null) {
                    // Get the schema from the module configuration
                    String schema = moduleConfigurations.get(stepConfig.getPipelineImplementationId()).getCustomConfigJsonSchema();

                    // TODO: In a real implementation, we would validate the custom configuration against the schema
                    // For now, we'll just check if validateConfig returns true
                    if (!stepConfig.getCustomConfig().validateConfig()) {
                        this.validationErrors = "Invalid custom configuration for step: " + stepConfig.getPipelineStepId() + 
                                               ". " + stepConfig.getCustomConfig().getValidationErrors();
                        return false;
                    }
                }
            }
        }

        // All validations passed
        this.validationErrors = null;
        return true;
    }

    @Override
    @Nullable
    public String getValidationErrors() {
        return validationErrors;
    }
}
