package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for service configuration.
 * This class represents the configuration for a single service in a pipeline.
 */
@Getter
@Setter
@Serdeable
public class ServiceConfigurationDto {
    /**
     * The name of the service.
     */
    private String name;

    /**
     * The list of Kafka topics this service listens to.
     */
    private List<String> kafkaListenTopics;

    /**
     * The list of Kafka topics this service publishes to.
     */
    private List<String> kafkaPublishTopics;

    /**
     * The list of services this service forwards to via gRPC.
     */
    private List<String> grpcForwardTo;

    /**
     * The name of the service implementation class.
     */
    private String serviceImplementation;

    /**
     * Service-specific configuration parameters.
     * This can be a simple Map<String, String> for backward compatibility.
     */
    private Map<String, String> configParams;

    /**
     * JSON configuration options for the service.
     * This provides schema validation and serialization capabilities.
     */
    private JsonConfigOptions jsonConfig;

    /**
     * Gets the JSON configuration options.
     * If it doesn't exist, creates a new one.
     *
     * @return the JSON configuration options
     */
    public JsonConfigOptions getJsonConfig() {
        if (jsonConfig == null) {
            jsonConfig = new JsonConfigOptions();
        }
        return jsonConfig;
    }
}
