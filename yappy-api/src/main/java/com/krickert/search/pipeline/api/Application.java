package com.krickert.search.pipeline.api;

import com.krickert.search.config.pipeline.model.*;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.runtime.Micronaut;
import io.micronaut.serde.annotation.SerdeImport;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.info.*;

@SerdeImport(PipelineConfig.class)
@SerdeImport(PipelineStepConfig.class)
@SerdeImport(StepType.class)
@SerdeImport(TransportType.class)
@SerdeImport(KafkaInputDefinition.class)
@SerdeImport(KafkaTransportConfig.class)
@SerdeImport(GrpcTransportConfig.class)
@SerdeImport(PipelineClusterConfig.class)
@OpenAPIDefinition(
    info = @Info(
            title = "YAPPY Pipeline API",
            version = "${api.version:1.0.0-SNAPSHOT}",
            description = "REST API for managing YAPPY data processing pipelines"
    )
)
public class Application {

    @ContextConfigurer
    public static class Configurer implements ApplicationContextConfigurer {
        @Override
        public void configure(@NonNull ApplicationContextBuilder builder) {
            builder.defaultEnvironments("dev");
        }
    }
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}