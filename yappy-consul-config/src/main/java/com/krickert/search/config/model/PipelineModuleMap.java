package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a mapping of available pipeline modules.
 * This class is used to store configurations for all recognized pipeline module types,
 * including their specific JSON schemas for custom configurations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineModuleMap {

    /**
     * A map containing the available pipeline module configurations.
     * Each entry in the map is keyed by the module implementation ID (unique service ID),
     * and the value represents the corresponding module's definition, including its name, ID,
     * and the JSON schema for its custom configuration.
     * <br/>
     * This structure is utilized to discover and retrieve blueprints for pipeline modules,
     * which represent service implementations intended to operate as nodes in a processing graph.
     */
    private Map<String, PipelineModuleConfiguration> availableModules;

    // Removed 'moduleConfigurations' as 'availableModules' should serve as the central
    // repository for module definitions including their schemas.
    // Specific instances of configurations for steps are in PipelineStepConfig.customConfig.
}