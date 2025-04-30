package com.krickert.search.pipeline.config;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getKafkaListenTopics() {
        return kafkaListenTopics;
    }

    public void setKafkaListenTopics(List<String> kafkaListenTopics) {
        this.kafkaListenTopics = kafkaListenTopics;
    }

    public List<String> getKafkaPublishTopics() {
        return kafkaPublishTopics;
    }

    public void setKafkaPublishTopics(List<String> kafkaPublishTopics) {
        this.kafkaPublishTopics = kafkaPublishTopics;
    }

    public List<String> getGrpcForwardTo() {
        return grpcForwardTo;
    }

    public void setGrpcForwardTo(List<String> grpcForwardTo) {
        this.grpcForwardTo = grpcForwardTo;
    }

    public String getServiceImplementation() {
        return serviceImplementation;
    }

    public void setServiceImplementation(String serviceImplementation) {
        this.serviceImplementation = serviceImplementation;
    }

    public Map<String, String> getConfigParams() {
        return configParams;
    }

    public void setConfigParams(Map<String, String> configParams) {
        this.configParams = configParams;
    }
}
