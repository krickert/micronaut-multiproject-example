package com.krickert.search.pipeline.config;

import io.micronaut.serde.annotation.Serdeable;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Serdeable
public class ServiceConfigurationDto {
    private String name;
    private List<String> kafkaListenTopics;
    private List<String> kafkaPublishTopics;
    private List<String> grpcForwardTo;
}