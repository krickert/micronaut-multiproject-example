package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for pipe step configuration.
 * This class represents the configuration for a single pipe step in a pipeline.
 */
@Getter
@Setter
@Serdeable
public class PipeStepConfigurationDto {
    /**
     * The name of the pipe step.
     */
    private String name;

    /**
     * The list of Kafka topics this pipe step listens to.
     */
    private List<String> kafkaListenTopics;

    /**
     * The list of Kafka topics this pipe step publishes to.
     */
    private List<String> kafkaPublishTopics;

    /**
     * The list of pipe steps this pipe step forwards to via gRPC.
     */
    private List<String> grpcForwardTo;

    /**
     * The name of the pipe step implementation class.
     */
    private String serviceImplementation;

    /**
     * Pipe step-specific configuration parameters.
     * This can be a simple Map<String, String> for backward compatibility.
     */
    private Map<String, String> configParams;

    /**
     * JSON configuration options for the pipe step.
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
