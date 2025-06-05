package com.krickert.search.pipeline.engine.bootstrap;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono; // Import Mono

import java.util.Collections;
import java.util.Optional;

@Singleton
@Requires(property = "app.engine.bootstrapper.enabled", notEquals = "false")
public class YappyEngineBootstrapper implements ApplicationEventListener<ApplicationStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(YappyEngineBootstrapper.class);
    private final Environment environment;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final String appClusterName;

    public YappyEngineBootstrapper(Environment environment,
                                   ConsulBusinessOperationsService consulBusinessOperationsService,
                                   @Value("${app.config.cluster-name}") String appClusterName) {
        this.environment = environment;
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.appClusterName = appClusterName;
        LOG.info("YappyEngineBootstrapper CONSTRUCTOR CALLED for cluster: {}. Classloader: {}", appClusterName, getClass().getClassLoader());
        System.out.println("<<<<< YappyEngineBootstrapper CONSTRUCTOR CALLED for cluster: " + appClusterName + " >>>>>"); // For quick console check
    }

    @Override
    public void onApplicationEvent(ApplicationStartupEvent event) {
        LOG.info("YappyEngineBootstrapper.onApplicationEvent ENTERED for cluster: {}", appClusterName);
        System.out.println("<<<<< YappyEngineBootstrapper.onApplicationEvent ENTERED for cluster: " + appClusterName + " >>>>>");
        LOG.info("YAPPY Engine is starting. Performing initial Consul configuration check for cluster '{}'...", appClusterName);

        Optional<String> consulHost = environment.getProperty("consul.client.host", String.class);
        Optional<Integer> consulPort = environment.getProperty("consul.client.port", Integer.class);

        if (consulHost.isPresent() && !consulHost.get().isBlank() && consulPort.isPresent() && consulPort.get() > 0) {
            System.out.println("<<<<< YappyEngineBootstrapper: Consul Connectivity Config FOUND for cluster: " + appClusterName + " >>>>>");
            LOG.info("Consul connectivity configuration found (Host: '{}', Port: {}). Proceeding with YAPPY Engine startup for cluster '{}'.",
                    consulHost.get(), consulPort.get(), appClusterName);

            LOG.info("Checking for existing PipelineClusterConfig for cluster '{}' in Consul...", appClusterName);
            consulBusinessOperationsService.getPipelineClusterConfig(appClusterName)
                    .flatMap(configOptional -> {
                        if (configOptional.isPresent()) {
                            LOG.info("CONFIG PRESENT for cluster '{}'. No action needed to create default.", appClusterName);
                            return Mono.just(true); // Indicate success (config exists or was handled)
                        } else {
                            LOG.warn("CONFIG NOT FOUND for cluster '{}'. Attempting to create a default configuration.", appClusterName);
                            PipelineClusterConfig defaultConfig = createDefaultPipelineClusterConfig(appClusterName);
                            return consulBusinessOperationsService.storeClusterConfiguration(appClusterName, defaultConfig)
                                    .doOnSubscribe(subscription -> LOG.info("Subscribed to storeClusterConfiguration for cluster '{}'", appClusterName))
                                    .doOnSuccess(success -> {
                                        if (Boolean.TRUE.equals(success)) {
                                            LOG.info(">>> SUCCESSFULLY STORED default config for cluster '{}'", appClusterName);
                                        } else {
                                            LOG.error(">>> FAILED TO STORE (returned false) default config for cluster '{}'", appClusterName);
                                        }
                                    })
                                    .doOnError(error -> LOG.error(">>> ERROR ON STORE for default config for cluster '{}'", appClusterName, error))
                                    .onErrorResume(e -> { // This ensures the main chain doesn't terminate with an unhandled error from store
                                        LOG.error("Error during storeClusterConfiguration for cluster '{}', resuming with false.", appClusterName, e);
                                        return Mono.just(false); // Indicate failure to store
                                    });
                        }
                    })
                    .doOnError(error -> { // This handles errors from getPipelineClusterConfig or the flatMap itself (before store)
                        LOG.error("Failed to check or create PipelineClusterConfig for cluster '{}' due to an error in the reactive chain before subscribe: {}", appClusterName, error.getMessage(), error);
                    })
                    .subscribe( // THIS IS THE KEY ADDITION
                            success -> LOG.info("PipelineClusterConfig check/creation process completed for cluster '{}'. Success status of operation: {}", appClusterName, success),
                            error -> LOG.error("Unhandled error in PipelineClusterConfig check/creation reactive chain for cluster '{}'. This should ideally be caught by doOnError or onErrorResume earlier.", appClusterName, error)
                    );

        } else {
            System.out.println("<<<<< YappyEngineBootstrapper: Consul Connectivity Config MISSING for cluster: " + appClusterName + " >>>>>");
            LOG.error("--------------------------------------------------------------------------------------");
            LOG.error("CRITICAL: Essential Consul connectivity configuration (consul.client.host and/or consul.client.port) is MISSING or INVALID for cluster '{}'.", appClusterName);
            LOG.error("The YAPPY Engine requires this to function correctly. Please ensure Consul is configured and accessible.");
            LOG.error("Application startup will proceed, but the engine will likely be non-functional or unstable.");
            LOG.error("--------------------------------------------------------------------------------------");
        }
        LOG.info("YappyEngineBootstrapper.onApplicationEvent COMPLETED for cluster: {}", appClusterName);
    }

    private PipelineClusterConfig createDefaultPipelineClusterConfig(String clusterName) {
        LOG.info("Creating default minimal PipelineClusterConfig for cluster: {}", clusterName);
        return PipelineClusterConfig.builder()
                .clusterName(clusterName)
                .pipelineGraphConfig(new PipelineGraphConfig(Collections.emptyMap()))
                .pipelineModuleMap(new PipelineModuleMap(Collections.emptyMap()))
                .defaultPipelineName(null)
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
    }
}