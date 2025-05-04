package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.ServiceConfigurationDto;
import com.krickert.search.config.consul.model.JsonConfigOptions;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceReadyEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the automatic registration of services with the pipeline configuration system.
 * This component listens for service startup events and registers the service with Consul
 * if it's not already registered.
 * 
 * Note: A service does not need to have pipeline configuration to run. The pipelines are
 * dynamically built. The service will have config built into it and the default config props
 * are already there, and it can have custom config added that is validated by a schema.
 */
@Singleton
public class ServiceRegistrationManager implements ApplicationEventListener<ServiceReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceRegistrationManager.class);

    private final PipelineService pipelineService;

    @Value("${pipeline.service.name:}")
    private String serviceName;

    @Value("${pipeline.name:}")
    private String pipelineName;

    @Value("${pipeline.service.implementation:}")
    private String serviceImplementation;

    @Value("${pipeline.listen.topics:}")
    private String listenTopics;

    @Value("${pipeline.publish.topics:}")
    private String publishTopics;

    @Value("${pipeline.grpc.forward.to:}")
    private String grpcForwardTo;

    @Value("${pipeline.service.json.config:{}}")
    private String jsonConfig;

    @Value("${pipeline.service.json.schema:{}}")
    private String jsonSchema;

    @Value("${pipeline.service.registration.enabled:true}")
    private boolean registrationEnabled;

    @Inject
    public ServiceRegistrationManager(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
        LOG.info("ServiceRegistrationManager initialized");
    }

    @Override
    public void onApplicationEvent(ServiceReadyEvent event) {
        // Allow null event for testing purposes
        if (!registrationEnabled) {
            LOG.info("Service registration is disabled. Skipping auto-registration.");
            return;
        }

        if (serviceName == null || serviceName.trim().isEmpty()) {
            LOG.warn("No service name configured. Skipping auto-registration.");
            return;
        }

        // A service can run without being registered to a pipeline
        if (pipelineName == null || pipelineName.trim().isEmpty()) {
            LOG.info("No pipeline name configured. Service '{}' will run with default configuration.", serviceName);
            return;
        }

        LOG.info("Service ready event received. Checking if service '{}' needs to be registered with pipeline '{}'", 
                serviceName, pipelineName);

        registerServiceIfNeeded();
    }

    /**
     * Checks if the service is already registered with the pipeline, and registers it if not.
     */
    private void registerServiceIfNeeded() {
        pipelineService.getPipeline(pipelineName)
            .flatMap(this::checkAndRegisterService)
            .onErrorResume(e -> {
                // Pipeline doesn't exist yet, create it first
                LOG.info("Pipeline '{}' not found. Creating it first.", pipelineName);
                return pipelineService.createPipeline(new CreatePipelineRequest(pipelineName))
                    .flatMap(this::registerService);
            })
            .subscribe(
                result -> LOG.info("Service registration process completed with result: {}", result),
                error -> LOG.error("Error during service registration", error)
            );
    }

    /**
     * Checks if the service is already registered with the pipeline, and registers it if not.
     * 
     * @param pipeline the pipeline configuration
     * @return a Mono that completes with true if the service was registered, false if it was already registered
     */
    private Mono<Boolean> checkAndRegisterService(PipelineConfigDto pipeline) {
        if (pipeline.getServices().containsKey(serviceName)) {
            LOG.info("Service '{}' is already registered with pipeline '{}'", serviceName, pipelineName);
            return Mono.just(false);
        }

        LOG.info("Service '{}' is not registered with pipeline '{}'. Registering it now.", serviceName, pipelineName);
        return registerService(pipeline);
    }

    /**
     * Registers the service with the pipeline.
     * 
     * @param pipeline the pipeline configuration
     * @return a Mono that completes with true if the registration was successful
     */
    private Mono<Boolean> registerService(PipelineConfigDto pipeline) {
        // Create a service configuration DTO
        ServiceConfigurationDto serviceConfig = createServiceConfigDto();

        // Add the service to the pipeline
        pipeline.getServices().put(serviceName, serviceConfig);

        // Update the pipeline
        return pipelineService.updatePipeline(pipelineName, pipeline)
            .map(updatedPipeline -> {
                LOG.info("Successfully registered service '{}' with pipeline '{}'", serviceName, pipelineName);
                return true;
            })
            .onErrorResume(e -> {
                LOG.error("Failed to register service '{}' with pipeline '{}'", serviceName, pipelineName, e);
                return Mono.just(false);
            });
    }

    /**
     * Creates a service configuration DTO based on the configured properties.
     * 
     * @return the service configuration DTO
     */
    private ServiceConfigurationDto createServiceConfigDto() {
        ServiceConfigurationDto serviceConfig = new ServiceConfigurationDto();
        serviceConfig.setName(serviceName);

        if (serviceImplementation != null && !serviceImplementation.trim().isEmpty()) {
            serviceConfig.setServiceImplementation(serviceImplementation);
        }

        // Parse listen topics
        if (listenTopics != null && !listenTopics.trim().isEmpty()) {
            List<String> topics = parseCommaSeparatedList(listenTopics);
            if (!topics.isEmpty()) {
                serviceConfig.setKafkaListenTopics(topics);
            }
        }

        // Parse publish topics
        if (publishTopics != null && !publishTopics.trim().isEmpty()) {
            List<String> topics = parseCommaSeparatedList(publishTopics);
            if (!topics.isEmpty()) {
                serviceConfig.setKafkaPublishTopics(topics);
            }
        }

        // Parse gRPC forward to
        if (grpcForwardTo != null && !grpcForwardTo.trim().isEmpty()) {
            List<String> targets = parseCommaSeparatedList(grpcForwardTo);
            if (!targets.isEmpty()) {
                serviceConfig.setGrpcForwardTo(targets);
            }
        }

        // Set JSON config if provided
        if (jsonConfig != null && !jsonConfig.equals("{}") && 
            jsonSchema != null && !jsonSchema.equals("{}")) {
            JsonConfigOptions jsonConfigOptions = new JsonConfigOptions(jsonConfig, jsonSchema);
            serviceConfig.setJsonConfig(jsonConfigOptions);
        }

        return serviceConfig;
    }

    /**
     * Parses a comma-separated list into a List of strings.
     * 
     * @param commaSeparatedList the comma-separated list
     * @return a List of strings
     */
    private List<String> parseCommaSeparatedList(String commaSeparatedList) {
        List<String> result = new ArrayList<>();
        if (commaSeparatedList != null && !commaSeparatedList.trim().isEmpty()) {
            String[] items = commaSeparatedList.split(",");
            for (String item : items) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
