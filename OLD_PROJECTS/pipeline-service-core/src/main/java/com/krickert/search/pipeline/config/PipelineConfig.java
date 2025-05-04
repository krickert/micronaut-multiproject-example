package com.krickert.search.pipeline.config;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

@EachProperty("pipeline.configs")
@Singleton
@Serdeable
@Introspected
public class PipelineConfig {

    // The property key from the configuration (pipeline1, pipeline2, etc.)
    private final String name;

    // The existing map of service configurations.
    private Map<String, ServiceConfiguration> service = new HashMap<>();

    public PipelineConfig(@Parameter String name) {
        this.name = name;
    }

    public boolean containsService(String serviceName) {
        return service.containsKey(serviceName);
    }

    public void addOrUpdateService(ServiceConfigurationDto dto) {
        // Validate that no topic ends with "-dlq"
        if (dto.getKafkaListenTopics() != null) {
            for (String topic : dto.getKafkaListenTopics()) {
                if (topic.endsWith("-dlq")) {
                    throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                }
            }
        }

        if (dto.getKafkaPublishTopics() != null) {
            for (String topic : dto.getKafkaPublishTopics()) {
                if (topic.endsWith("-dlq")) {
                    throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                }
            }
        }

        ServiceConfiguration config = new ServiceConfiguration(dto.getName());
        config.setKafkaListenTopics(dto.getKafkaListenTopics());
        config.setKafkaPublishTopics(dto.getKafkaPublishTopics());
        config.setGrpcForwardTo(dto.getGrpcForwardTo());
        config.setServiceImplementation(dto.getServiceImplementation());
        config.setConfigParams(dto.getConfigParams());
        service.put(dto.getName(), config);
    }

    public String getName() {
        return name;
    }

    public Map<String, ServiceConfiguration> getService() {
        return service;
    }

    public void setService(Map<String, ServiceConfiguration> service) {
        this.service = service;
    }
}
