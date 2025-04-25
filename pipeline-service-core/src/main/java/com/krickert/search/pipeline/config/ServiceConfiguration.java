package com.krickert.search.pipeline.config;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Serdeable
@Introspected
public class ServiceConfiguration {

    private String name;
    private List<String> kafkaListenTopics;
    private List<String> kafkaPublishTopics;
    private List<String> grpcForwardTo;

    // Default constructor is needed for deserialization.
    public ServiceConfiguration() {
    }

    public ServiceConfiguration(String name) {
        this.name = name;
    }
}