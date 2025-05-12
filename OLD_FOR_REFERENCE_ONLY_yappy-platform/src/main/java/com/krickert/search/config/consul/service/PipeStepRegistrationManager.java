package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.model.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceReadyEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the automatic registration of pipe steps with the pipeline configuration system.
 * This component listens for service startup events and registers the pipe step with Consul
 * if it's not already registered.
 * <br/>
 * Note: A pipe step does not need to have pipeline configuration to run. The pipelines are
 * dynamically built. The pipe step will have config built into it and the default config props
 * are already there, and it can have custom config added that is validated by a schema.
 */
@Singleton
public class PipeStepRegistrationManager implements ApplicationEventListener<ServiceReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStepRegistrationManager.class);

    private final PipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @Value("${pipeline.step.name:}")
    private String pipeStepName;

    @Value("${pipeline.name:}")
    private String pipelineName;

    @Value("${pipeline.step.implementation:}")
    private String stepImplementation;

    @Value("${pipeline.listen.topics:}")
    private String listenTopics;

    @Value("${pipeline.publish.topics:}")
    private String publishTopics;

    @Value("${pipeline.grpc.forward.to:}")
    private String grpcForwardTo;

    @Value("${pipeline.step.json.config:{}}")
    private String jsonConfig;

    @Value("${pipeline.step.json.schema:{}}")
    private String jsonSchema;

    @Value("${pipeline.step.registration.enabled:true}")
    private boolean registrationEnabled;

    @Inject
    public PipeStepRegistrationManager(PipelineService pipelineService, ObjectMapper objectMapper) {
        this.pipelineService = pipelineService;
        this.objectMapper = objectMapper;
        LOG.info("PipeStepRegistrationManager initialized");
    }

    @Override
    public void onApplicationEvent(ServiceReadyEvent event) {
        // Allow null event for testing purposes
        if (!registrationEnabled) {
            LOG.info("Pipe step registration is disabled. Skipping auto-registration.");
            return;
        }

        if (pipeStepName == null || pipeStepName.trim().isEmpty()) {
            LOG.warn("No pipe step name configured. Skipping auto-registration.");
            return;
        }

        // A pipe step can run without being registered to a pipeline
        if (pipelineName == null || pipelineName.trim().isEmpty()) {
            LOG.info("No pipeline name configured. Pipe step '{}' will run with default configuration.", pipeStepName);
            return;
        }

        LOG.info("Service ready event received. Checking if pipe step '{}' needs to be registered with pipeline '{}'", 
                pipeStepName, pipelineName);

        registerPipeStepIfNeeded();
    }

    /**
     * Checks if the pipe step is already registered with the pipeline, and registers it if not.
     */
    private void registerPipeStepIfNeeded() {
        pipelineService.getPipeline(pipelineName)
            .flatMap(this::checkAndRegisterPipeStep)
            .onErrorResume(e -> {
                // Pipeline doesn't exist yet, create it first
                LOG.info("Pipeline '{}' not found. Creating it first.", pipelineName);
                return pipelineService.createPipeline(new CreatePipelineRequest(pipelineName))
                    .flatMap(this::registerPipeStep);
            })
            .subscribe(
                result -> LOG.info("Pipe step registration process completed with result: {}", result),
                error -> LOG.error("Error during pipe step registration", error)
            );
    }

    /**
     * Checks if the pipe step is already registered with the pipeline, and registers it if not.
     * 
     * @param pipeline the pipeline configuration
     * @return a Mono that completes with true if the pipe step was registered, false if it was already registered
     */
    private Mono<Boolean> checkAndRegisterPipeStep(PipelineConfigDto pipeline) {
        if (pipeline.getServices().containsKey(pipeStepName)) {
            LOG.info("Pipe step '{}' is already registered with pipeline '{}'", pipeStepName, pipelineName);
            return Mono.just(false);
        }

        LOG.info("Pipe step '{}' is not registered with pipeline '{}'. Registering it now.", pipeStepName, pipelineName);
        return registerPipeStep(pipeline);
    }

    /**
     * Registers the pipe step with the pipeline.
     * 
     * @param pipeline the pipeline configuration
     * @return a Mono that completes with true if the registration was successful
     */
    private Mono<Boolean> registerPipeStep(PipelineConfigDto pipeline) {
        // Create a pipe step configuration DTO
        PipeStepConfigurationDto pipeStepConfig = createPipeStepConfigDto();

        // Log the pipe step configuration before adding it to the pipeline
        LOG.debug("Adding pipe step configuration to pipeline: {}", pipeStepConfig);
        LOG.debug("Pipe step kafkaPublishTopics: {}", pipeStepConfig.getKafkaPublishTopics());

        // Add the pipe step to the pipeline
        pipeline.getServices().put(pipeStepName, pipeStepConfig);

        // Update the pipeline
        return pipelineService.updatePipeline(pipelineName, pipeline)
            .map(updatedPipeline -> {
                LOG.info("Successfully registered pipe step '{}' with pipeline '{}'", pipeStepName, pipelineName);
                return true;
            })
            .onErrorResume(e -> {
                LOG.error("Failed to register pipe step '{}' with pipeline '{}'", pipeStepName, pipelineName, e);
                return Mono.just(false);
            });
    }

    /**
     * Creates a pipe step configuration DTO based on the configured properties.
     * 
     * @return the pipe step configuration DTO
     */
    private PipeStepConfigurationDto createPipeStepConfigDto() {
        PipeStepConfigurationDto pipeStepConfig = new PipeStepConfigurationDto();
        pipeStepConfig.setName(pipeStepName);

        if (stepImplementation != null && !stepImplementation.trim().isEmpty()) {
            // Using setServiceImplementation for compatibility with the rest of the codebase
            pipeStepConfig.setServiceImplementation(stepImplementation);
        }

        // Parse listen topics
        if (listenTopics != null && !listenTopics.trim().isEmpty()) {
            List<String> topics = parseCommaSeparatedList(listenTopics);
            if (!topics.isEmpty()) {
                pipeStepConfig.setKafkaListenTopics(topics);
            }
        }

        // Parse publish topics
        if (publishTopics != null && !publishTopics.trim().isEmpty()) {
            // Check if the publishTopics is in JSON format (starts with '[')
            if (publishTopics.trim().startsWith("[")) {
                try {
                    List<KafkaRouteTarget> kafkaRouteTargets = parseJsonPublishTopics(publishTopics);
                    LOG.debug("Parsed JSON publish topics for step '{}': {}", pipeStepName, kafkaRouteTargets);
                    if (!kafkaRouteTargets.isEmpty()) {
                        pipeStepConfig.setKafkaPublishTopics(kafkaRouteTargets);
                        LOG.debug("Set kafkaPublishTopics for step '{}': {}", pipeStepName, pipeStepConfig.getKafkaPublishTopics());
                    }
                } catch (Exception e) {
                    LOG.error("Failed to parse JSON publish topics for step '{}': {}", pipeStepName, e.getMessage(), e);
                    // Fallback to comma-separated list parsing
                    handleLegacyPublishTopics(pipeStepConfig);
                }
            } else {
                // Legacy format (comma-separated list)
                handleLegacyPublishTopics(pipeStepConfig);
            }
        }

        // Parse gRPC forward to
        if (grpcForwardTo != null && !grpcForwardTo.trim().isEmpty()) {
            List<String> targets = parseCommaSeparatedList(grpcForwardTo);
            if (!targets.isEmpty()) {
                pipeStepConfig.setGrpcForwardTo(targets);
            }
        }

        // Set JSON config if provided
        if (jsonConfig != null && !jsonConfig.equals("{}") && 
            jsonSchema != null && !jsonSchema.equals("{}")) {
            JsonConfigOptions jsonConfigOptions = new JsonConfigOptions(jsonConfig, jsonSchema);
            pipeStepConfig.setJsonConfig(jsonConfigOptions);
        }

        return pipeStepConfig;
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

    /**
     * Parses JSON-formatted publish topics into a List of KafkaRouteTarget objects.
     * 
     * @param jsonPublishTopics the JSON-formatted publish topics
     * @return a List of KafkaRouteTarget objects
     * @throws JsonProcessingException if the JSON parsing fails
     */
    private List<KafkaRouteTarget> parseJsonPublishTopics(String jsonPublishTopics) throws JsonProcessingException {
        return objectMapper.readValue(jsonPublishTopics, new TypeReference<List<KafkaRouteTarget>>() {});
    }

    /**
     * Handles legacy format (comma-separated list) for publish topics.
     * 
     * @param pipeStepConfig the pipe step configuration DTO
     */
    private void handleLegacyPublishTopics(PipeStepConfigurationDto pipeStepConfig) {
        List<String> topicNamesOnly = parseCommaSeparatedList(publishTopics);
        if (!topicNamesOnly.isEmpty()) {
            LOG.warn("Auto-registration for step '{}': Setting kafkaPublishTopics with topic names only. Target pipe step IDs must be configured via API/UI.", pipeStepName);
            List<KafkaRouteTarget> incompleteRoutes = topicNamesOnly.stream()
                    .map(topic -> new KafkaRouteTarget(topic, null))
                    .collect(Collectors.toList());
            pipeStepConfig.setKafkaPublishTopics(incompleteRoutes);
        }
    }
}
