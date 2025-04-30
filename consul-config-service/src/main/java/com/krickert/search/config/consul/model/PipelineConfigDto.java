package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Data Transfer Object for pipeline configuration.
 * This class represents the configuration for an entire pipeline, containing multiple service configurations.
 */
@Getter
@Setter
@Serdeable
public class PipelineConfigDto {
    /**
     * The name of the pipeline.
     */
    private String name;
    
    /**
     * Map of service configurations, keyed by service name.
     */
    private Map<String, ServiceConfigurationDto> services = new HashMap<>();
    
    /**
     * Default constructor.
     */
    public PipelineConfigDto() {
    }
    
    /**
     * Constructor with pipeline name.
     *
     * @param name the name of the pipeline
     */
    public PipelineConfigDto(String name) {
        this.name = name;
    }
    
    /**
     * Checks if the pipeline contains a service with the given name.
     *
     * @param serviceName the name of the service to check
     * @return true if the pipeline contains the service, false otherwise
     */
    public boolean containsService(String serviceName) {
        return services.containsKey(serviceName);
    }
    
    /**
     * Adds or updates a service configuration.
     *
     * @param serviceConfig the service configuration to add or update
     */
    public void addOrUpdateService(ServiceConfigurationDto serviceConfig) {
        // Validate that no topic ends with "-dlq"
        if (serviceConfig.getKafkaListenTopics() != null) {
            for (String topic : serviceConfig.getKafkaListenTopics()) {
                if (topic.endsWith("-dlq")) {
                    throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                }
            }
        }

        if (serviceConfig.getKafkaPublishTopics() != null) {
            for (String topic : serviceConfig.getKafkaPublishTopics()) {
                if (topic.endsWith("-dlq")) {
                    throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                }
            }
        }
        
        services.put(serviceConfig.getName(), serviceConfig);
    }
    
    /**
     * Removes a service configuration.
     *
     * @param serviceName the name of the service to remove
     * @return the removed service configuration, or null if not found
     */
    public ServiceConfigurationDto removeService(String serviceName) {
        return services.remove(serviceName);
    }
}