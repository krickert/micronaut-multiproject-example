package com.krickert.search.pipeline.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties("pipeline")
@Singleton
@Serdeable
@Introspected
public class InternalServiceConfig {
    private String pipelineServiceName;
}
