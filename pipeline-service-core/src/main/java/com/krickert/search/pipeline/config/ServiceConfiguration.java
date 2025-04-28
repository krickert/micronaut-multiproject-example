package com.krickert.search.pipeline.config;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Serdeable
@Introspected
public class ServiceConfiguration {

    private String name;
    private List<String> kafkaListenTopics;
    private List<String> kafkaPublishTopics;
    private List<String> grpcForwardTo;

    // The name of the service implementation
    private String serviceImplementation;

    // Service-specific configuration parameters
    private Map<String, String> configParams;

    // Default constructor is needed for deserialization.
    public ServiceConfiguration() {
    }

    public ServiceConfiguration(String name) {
        this.name = name;
    }
}
