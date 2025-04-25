package com.krickert.search.pipeline.controller;

import com.krickert.search.pipeline.config.PipelineConfigManager;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.config.ServiceConfigurationDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for managing pipeline configurations.
 * Provides endpoints for reloading configuration from Consul or files,
 * and for updating specific service configurations.
 */
@Controller("/api/pipeline/config")
@Slf4j
public class PipelineConfigController {

    @Inject
    private PipelineConfigManager pipelineConfig;

    /**
     * Get the current pipeline configuration.
     * 
     * @return A map of pipeline configurations
     */
    @Get("/")
    public HttpResponse<Map<String, Object>> getConfig() {
        log.info("Getting current pipeline configuration");
        Map<String, Object> response = new HashMap<>();
        response.put("pipelines", pipelineConfig.getPipelines());
        return HttpResponse.ok(response);
    }

    /**
     * Reload the pipeline configuration from Consul.
     * 
     * @return A response indicating success or failure
     */
    @Post("/reload/consul")
    public HttpResponse<Map<String, Object>> reloadFromConsul() {
        log.info("Reloading pipeline configuration from Consul");
        Map<String, Object> response = new HashMap<>();

        boolean success = pipelineConfig.loadPropertiesFromConsul();

        if (success) {
            response.put("status", "success");
            response.put("message", "Pipeline configuration reloaded from Consul");
            response.put("pipelines", pipelineConfig.getPipelines());
            return HttpResponse.ok(response);
        } else {
            response.put("status", "error");
            response.put("message", "Failed to reload pipeline configuration from Consul");
            return HttpResponse.serverError(response);
        }
    }

    /**
     * Reload the pipeline configuration from a file.
     * 
     * @return A response indicating success or failure
     */
    @Post("/reload/file")
    public HttpResponse<Map<String, Object>> reloadFromFile() {
        log.info("Reloading pipeline configuration from file");
        Map<String, Object> response = new HashMap<>();

        boolean success = pipelineConfig.loadPropertiesFromFile("pipeline.properties");

        if (success) {
            response.put("status", "success");
            response.put("message", "Pipeline configuration reloaded from file");
            response.put("pipelines", pipelineConfig.getPipelines());
            return HttpResponse.ok(response);
        } else {
            response.put("status", "error");
            response.put("message", "Failed to reload pipeline configuration from file");
            return HttpResponse.serverError(response);
        }
    }

    /**
     * Update a specific service configuration.
     * 
     * @param pipelineName The name of the pipeline
     * @param serviceDto The service configuration to update
     * @return A response indicating success or failure
     */
    @Put("/{pipelineName}/service")
    public HttpResponse<Map<String, Object>> updateServiceConfig(
            @PathVariable String pipelineName,
            @Body ServiceConfigurationDto serviceDto) {
        log.info("Updating service configuration for pipeline: {}, service: {}", 
                pipelineName, serviceDto.getName());

        Map<String, Object> response = new HashMap<>();

        // Check if the pipeline exists
        if (!pipelineConfig.getPipelines().containsKey(pipelineName)) {
            response.put("status", "error");
            response.put("message", "Pipeline not found: " + pipelineName);
            return HttpResponse.notFound(response);
        }

        try {
            // Get the pipeline configuration
            var pipeline = pipelineConfig.getPipelines().get(pipelineName);

            // Update the service configuration in memory
            pipeline.addOrUpdateService(serviceDto);

            // Get the updated service configuration
            ServiceConfiguration updatedConfig = pipeline.getService().get(serviceDto.getName());

            // Update the service configuration in Consul
            boolean success = pipelineConfig.updateServiceConfigInConsul(pipelineName, updatedConfig);

            // Reload from Consul to ensure the changes are persisted
            if (success) {
                success = pipelineConfig.loadPropertiesFromConsul();
            }

            if (success) {
                response.put("status", "success");
                response.put("message", "Service configuration updated successfully");
                response.put("service", pipeline.getService().get(serviceDto.getName()));
                return HttpResponse.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "Failed to persist service configuration to Consul");
                return HttpResponse.serverError(response);
            }
        } catch (Exception e) {
            log.error("Error updating service configuration", e);
            response.put("status", "error");
            response.put("message", "Error updating service configuration: " + e.getMessage());
            return HttpResponse.serverError(response);
        }
    }
}
