package com.krickert.search.pipeline.router;

import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder; // Use your class

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Singleton
@Slf4j
public class PipelineRouter {

    private final ConfigurationService configurationService;
    private final GrpcPipelineStepExecutor grpcExecutor;
    private final KafkaForwarder kafkaForwarder;

    @Inject
    public PipelineRouter(ConfigurationService configurationService,
                          GrpcPipelineStepExecutor grpcExecutor,
                          KafkaForwarder kafkaForwarder) {
        this.configurationService = configurationService;
        this.grpcExecutor = grpcExecutor;
        this.kafkaForwarder = kafkaForwarder;
    }

    /**
     * Processes routing rules and forwards the stream asynchronously.
     *
     * @param pipelineName        Name of the current pipeline.
     * @param completedStepConfig The config DTO of the step that just finished.
     * @param outgoingStreamBuilder The PipeStream builder after the completed step's logic.
     * The router sets the current_pipestep_id before forwarding.
     */
    public void routeAndForward(String pipelineName,
                                PipeStepConfigurationDto completedStepConfig,
                                PipeStream.Builder outgoingStreamBuilder) {

        String completedLogicalStepId = completedStepConfig.getName();
        String streamId = outgoingStreamBuilder.getStreamId();

        log.debug("Stream [{}]: Routing after step '{}' in pipeline '{}'.", streamId, completedLogicalStepId, pipelineName);

        // Fetch pipeline config once for lookups (may be cached by ConfigService)
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        if (pipelineConfig == null) {
            log.error("Stream [{}]: Cannot route - Pipeline config '{}' not found.", streamId, pipelineName);
            return; // Or throw/handle error
        }

        // --- 1. Handle gRPC Forwarding ---
        List<String> nextGrpcStepIds = completedStepConfig.getGrpcForwardTo();
        if (nextGrpcStepIds != null && !nextGrpcStepIds.isEmpty()) {
            handleGrpcForwarding(pipelineConfig, completedStepConfig, outgoingStreamBuilder, nextGrpcStepIds);
        } else {
            log.debug("Stream [{}]: No gRPC forward targets for step '{}'.", streamId, completedLogicalStepId);
        }

        // --- 2. Handle Kafka Forwarding ---
        List<KafkaRouteTarget> kafkaTopics = completedStepConfig.getKafkaPublishTopics();
        if (kafkaTopics != null && !kafkaTopics.isEmpty()) {
            handleKafkaForwarding(pipelineConfig, completedStepConfig, outgoingStreamBuilder, kafkaTopics);
        } else {
            log.debug("Stream [{}]: No Kafka publish topics for step '{}'.", streamId, completedLogicalStepId);
        }

        // --- 3. Check for Terminal Step ---
        if ((nextGrpcStepIds == null || nextGrpcStepIds.isEmpty()) && (kafkaTopics == null || kafkaTopics.isEmpty())) {
            log.info("Stream [{}]: Step '{}' is a terminal step in pipeline '{}'.", streamId, completedLogicalStepId, pipelineName);
        }
    }

    private void handleGrpcForwarding(PipelineConfigDto pipelineConfig,
                                      PipeStepConfigurationDto completedStepConfig,
                                      PipeStream.Builder baseOutgoingStreamBuilder,
                                      List<String> nextLogicalStepIds) {

        String pipelineName = pipelineConfig.getName();
        String completedLogicalStepId = completedStepConfig.getName();
        String streamId = baseOutgoingStreamBuilder.getStreamId();

        for (String nextLogicalStepId : nextLogicalStepIds) {
            if (nextLogicalStepId == null || nextLogicalStepId.isBlank()) {
                log.warn("Stream [{}]: Blank gRPC forward target from step '{}'. Skipping.", streamId, completedLogicalStepId);
                continue;
            }

            // Lookup the config for the *next* step using its logical ID
            PipeStepConfigurationDto nextStepConfigDto = pipelineConfig.getServices().get(nextLogicalStepId);
            if (nextStepConfigDto == null) {
                log.error("Stream [{}]: Routing config error! Next step ID '{}' (from step '{}') not found in pipeline '{}'. Cannot forward.",
                        streamId, nextLogicalStepId, completedLogicalStepId, pipelineName);
                continue; // Skip invalid route
            }

            // Find the Consul app name (serviceImplementation) for the next step
            String targetConsulAppName = nextStepConfigDto.getServiceImplementation();
            if (targetConsulAppName == null || targetConsulAppName.isBlank()) {
                log.error("Stream [{}]: Routing config error! Next step ID '{}' in pipeline '{}' has no serviceImplementation. Cannot forward.",
                        streamId, nextLogicalStepId, pipelineName);
                continue; // Skip invalid route
            }

            // Prepare PipeStream specifically for this target step
            PipeStream streamForNextStep = baseOutgoingStreamBuilder.build().toBuilder()
                    .setCurrentPipestepId(nextLogicalStepId) // Set ID for the receiver
                    .build();

            log.info("Stream [{}]: Forwarding via gRPC from step '{}' to step '{}' (service: '{}')",
                    streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName);

            CompletableFuture<Void> forwardFuture = grpcExecutor.forwardPipeStreamAsync(targetConsulAppName, streamForNextStep);

            // Handle async result (log failure)
            forwardFuture.whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error("Stream [{}]: Failed async gRPC dispatch from step '{}' to step '{}' (service: '{}'). Reason: {}",
                            streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName, exception.getMessage(), exception);
                } else {
                    log.debug("Stream [{}]: Async gRPC dispatch acknowledged from step '{}' to step '{}' (service: '{}')",
                            streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName);
                }
            });
        }
    }

    private void handleKafkaForwarding(PipelineConfigDto pipelineConfig,
                                       PipeStepConfigurationDto completedStepConfig,
                                       PipeStream.Builder baseOutgoingStreamBuilder,
                                       List<KafkaRouteTarget> kafkaTopics) {

        String pipelineName = pipelineConfig.getName();
        String completedLogicalStepId = completedStepConfig.getName();
        String streamId = baseOutgoingStreamBuilder.getStreamId();

        // Build the stream state ONCE for all Kafka topics originating from this completed step.
        // We assume Kafka consumers will know their role based on topic/pipeline, so we don't
        // set a specific 'current_pipestep_id' intended *for the Kafka consumer* here.
        // The ID in the stream reflects the step *that produced this message*.
        PipeStream streamForKafka = baseOutgoingStreamBuilder
                // .clearCurrentPipestepId() // Optional: Clear if it causes confusion for Kafka consumers
                .build();

        for (KafkaRouteTarget routeTarget : kafkaTopics) {
            String topicName = routeTarget.topic();
            if (topicName == null || topicName.isBlank()) {
                log.warn("Stream [{}]: Blank Kafka publish topic from step '{}'. Skipping.", streamId, completedLogicalStepId);
                continue;
            }
            log.info("Stream [{}]: Forwarding via Kafka from step '{}' to topic '{}'",
                    streamId, completedLogicalStepId, topicName);

            try {
                // Use your KafkaForwarder. Adapt if signature requires Route object.
                // If Route is needed, construct a minimal one here.
                kafkaForwarder.forwardToKafka(streamForKafka, topicName);
            } catch (Exception e) {
                log.error("Stream [{}]: Exception during Kafka dispatch from step '{}' to topic '{}': {}",
                        streamId, completedLogicalStepId, topicName, e.getMessage(), e);
            }
        }
    }
}