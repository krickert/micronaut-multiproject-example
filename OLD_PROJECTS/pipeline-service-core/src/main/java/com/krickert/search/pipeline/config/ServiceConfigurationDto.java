package com.krickert.search.pipeline.config;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Serdeable
public class ServiceConfigurationDto {
    private String name;
    private List<String> kafkaListenTopics;
    private List<String> kafkaPublishTopics;
    private List<String> grpcForwardTo;

    // The name of the service implementation
    private String serviceImplementation;

    // Service-specific configuration parameters
    private Map<String, String> configParams;
}
