package com.krickert.search.pipeline.router;

// Assuming KafkaRouteTarget is defined in this package or imported
import com.krickert.search.config.consul.model.KafkaRouteTarget;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService; // To lookup next step's config
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder; // Your existing forwarder class

import io.netty.util.internal.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID; // For Kafka key generation
import java.util.concurrent.CompletableFuture;

/**
 * Responsible for interpreting routing rules after a pipeline step completes
 * and dispatching the PipeStream to the next destinations (gRPC or Kafka)
 * using the appropriate forwarders. Assumes best-effort asynchronous handoff.
 */
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
        log.info("PipelineRouter initialized.");
    }

    /**
     * Processes the routing rules for the completed step and forwards the PipeStream
     * asynchronously to the next destinations (gRPC and/or Kafka).
     * Assumes the caller has already updated the stream builder with history, results,
     * and incremented hop number from the completed step.
     *
     * @param pipelineName        The name of the current pipeline definition.
     * @param completedStepConfig The configuration DTO of the step that just finished executing.
     * @param outgoingStreamBuilder The PipeStream builder, ready to be finalized
     * for forwarding. The router sets 'current_pipestep_id'.
     */
    public void routeAndForward(String pipelineName,
                                PipeStepConfigurationDto completedStepConfig,
                                PipeStream.Builder outgoingStreamBuilder) {

        String completedLogicalStepId = completedStepConfig.getName();
        // Ensure streamId exists before proceeding
        String streamId = outgoingStreamBuilder.getStreamId();
        if (isNullOrBlank(streamId)) {
             log.error("CRITICAL: Stream ID is missing in outgoingStreamBuilder before routing from step '{}'. Aborting routing.", completedLogicalStepId);
             // This indicates a problem in the calling generic processor logic
             return;
        }

        log.debug("Stream [{}]: Routing after step '{}' in pipeline '{}'.", streamId, completedLogicalStepId, pipelineName);

        // Fetch the full pipeline config once for potential lookups
        // ConfigurationService might cache this internally.
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        if (pipelineConfig == null) {
            log.error("Stream [{}]: Cannot route - Pipeline configuration '{}' not found.", streamId, pipelineName);
            // TODO: Define error handling strategy (e.g., update stream history with error, send to DLQ?)
            return;
        }

        boolean forwarded = false;

        // --- 1. Handle gRPC Forwarding ---
        List<String> nextGrpcStepIds = completedStepConfig.getGrpcForwardTo(); // List of next logical step IDs
        if (!isNullOrEmpty(nextGrpcStepIds)) {
            handleGrpcForwarding(pipelineConfig, completedStepConfig, outgoingStreamBuilder, nextGrpcStepIds);
            forwarded = true; // Mark that at least one forward attempt was made
        } else {
            log.debug("Stream [{}]: No gRPC forward targets defined for step '{}'.", streamId, completedLogicalStepId);
        }

        // --- 2. Handle Kafka Forwarding ---
        List<KafkaRouteTarget> kafkaRoutes = completedStepConfig.getKafkaPublishTopics(); // Use the field directly
        if (!isNullOrEmpty(kafkaRoutes)) {
            handleKafkaForwarding(pipelineConfig, completedStepConfig, outgoingStreamBuilder, kafkaRoutes);
            forwarded = true; // Mark that at least one forward attempt was made
        } else {
            log.debug("Stream [{}]: No Kafka publish targets defined for step '{}'.", streamId, completedLogicalStepId);
        }

        // --- 3. Log if Terminal Step ---
        if (!forwarded) {
            log.info("Stream [{}]: Step '{}' is a terminal step (no defined gRPC or Kafka routes) in pipeline '{}'.",
                    streamId, completedLogicalStepId, pipelineName);
            // Potentially update stream status to COMPLETED here if needed
        }
    }

    /**
     * Handles forwarding to downstream gRPC steps based on routing rules.
     */
    private void handleGrpcForwarding(PipelineConfigDto pipelineConfig,
                                      PipeStepConfigurationDto completedStepConfig,
                                      PipeStream.Builder baseOutgoingStreamBuilder, // Base state after current step
                                      List<String> nextLogicalStepIds) {

        String pipelineName = pipelineConfig.getName();
        String completedLogicalStepId = completedStepConfig.getName();
        String streamId = baseOutgoingStreamBuilder.getStreamId(); // Assumes non-null based on check in caller

        for (String nextLogicalStepId : nextLogicalStepIds) {
            if (isNullOrBlank(nextLogicalStepId)) {
                log.warn("Stream [{}]: Blank gRPC forward target found in routing rules for step '{}'. Skipping.", streamId, completedLogicalStepId);
                continue;
            }

            // Lookup the config for the *next* step using its logical ID
            PipeStepConfigurationDto nextStepConfigDto = pipelineConfig.getServices().get(nextLogicalStepId);
            if (nextStepConfigDto == null) {
                log.error("Stream [{}]: Routing config error! Next step ID '{}' (from step '{}') not found in pipeline '{}'. Cannot forward.",
                          streamId, nextLogicalStepId, completedLogicalStepId, pipelineName);
                // TODO: Error handling strategy (DLQ?)
                continue; // Skip this invalid route
            }

            // Find the Consul app name (serviceImplementation) for the next step
            String targetConsulAppName = nextStepConfigDto.getServiceImplementation();
            if (isNullOrBlank(targetConsulAppName)) {
                 log.error("Stream [{}]: Routing config error! Next step ID '{}' in pipeline '{}' has no serviceImplementation. Cannot forward.",
                           streamId, nextLogicalStepId, pipelineName);
                 // TODO: Error handling strategy
                continue; // Skip this invalid route
            }

            // Prepare PipeStream specifically for this target step
            // Create a *new* builder from the base to avoid modifying it for other parallel routes
            PipeStream streamForNextStep = baseOutgoingStreamBuilder.build().toBuilder()
                    .setCurrentPipestepId(nextLogicalStepId) // Set ID for the receiver
                    .build(); // Build the final stream for this specific forward

            log.info("Stream [{}]: Forwarding via gRPC from step '{}' to step '{}' (service: '{}')",
                     streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName);

            // Call executor (async, best effort)
            CompletableFuture<Void> forwardFuture = grpcExecutor.forwardPipeStreamAsync(targetConsulAppName, streamForNextStep);

            // Handle async result (log failure - best effort)
            forwardFuture.whenComplete((result, exception) -> {
                if (exception != null) {
                    // Log the failure to dispatch
                    log.error("Stream [{}]: Failed async gRPC dispatch from step '{}' to step '{}' (service: '{}'). Reason: {}",
                              streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName, exception.getMessage(), exception);
                    // Potential future: Trigger DLQ or monitoring alert based on exception type
                } else {
                     log.debug("Stream [{}]: Async gRPC dispatch acknowledged from step '{}' to step '{}' (service: '{}')",
                           streamId, completedLogicalStepId, nextLogicalStepId, targetConsulAppName);
                }
            });
        }
    }

    /**
     * Handles forwarding to downstream Kafka topics based on routing rules.
     */
    private void handleKafkaForwarding(PipelineConfigDto pipelineConfig,
                                       PipeStepConfigurationDto completedStepConfig,
                                       PipeStream.Builder baseOutgoingStreamBuilder, // Base state after current step
                                       List<KafkaRouteTarget> kafkaRoutes) { // Use the correct DTO list

        String pipelineName = pipelineConfig.getName();
        String completedLogicalStepId = completedStepConfig.getName();
        String streamId = baseOutgoingStreamBuilder.getStreamId(); // Assumes non-null

        for (KafkaRouteTarget route : kafkaRoutes) {
            String topicName = route.topic();
            String nextLogicalStepIdForKafka = route.targetPipeStepId(); // Get from DTO

            if (isNullOrBlank(topicName) || isNullOrBlank(nextLogicalStepIdForKafka)) {
                log.warn("Stream [{}]: Invalid Kafka route target from step '{}': Topic='{}', TargetStep='{}'. Skipping.",
                         streamId, completedLogicalStepId, topicName, nextLogicalStepIdForKafka);
                continue; // Skip invalid route
            }

            // Prepare stream with the pipestep_id intended for the Kafka consumer
            // Create a *new* builder from the base to avoid modifying it for other parallel routes
            PipeStream streamForKafka = baseOutgoingStreamBuilder.build().toBuilder()
                    .setCurrentPipestepId(nextLogicalStepIdForKafka) // Set ID for the consumer
                    .build();

            log.info("Stream [{}]: Forwarding via Kafka from step '{}' to topic '{}' (for next step '{}')",
                     streamId, completedLogicalStepId, topicName, nextLogicalStepIdForKafka);

            try {
                // Generate Kafka Key (assuming streamId is a UUID string)
                UUID kafkaKey = UUID.fromString(streamId);

                // Call your existing KafkaForwarder, passing topic, key, and prepared PipeStream
                // Adapt this call if your KafkaForwarder method signature is different
                // e.g., if it takes a Route object, you might need to construct a minimal one.
                // Assuming forwardToKafka(String topic, UUID key, PipeStream pipe) exists:
                kafkaForwarder.forwardToKafka(streamForKafka, topicName);

            } catch (IllegalArgumentException e) {
                 log.error("Stream [{}]: Invalid Stream ID '{}' - cannot parse as UUID for Kafka key. Skipping Kafka forward to topic '{}'.",
                           streamId, streamId, topicName, e);
            } catch (Exception e) {
                // Log synchronous errors from the forwarder call itself (e.g., Kafka client buffer full if sync)
                // Note: Actual Kafka network send errors are typically handled asynchronously by the Kafka client/producer.
                log.error("Stream [{}]: Exception during Kafka dispatch preparation or synchronous send from step '{}' to topic '{}': {}",
                          streamId, completedLogicalStepId, topicName, e.getMessage(), e);
            }
        }
    }

    // Simple helper for checking blank strings
    private boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    // Simple helper for checking null/empty lists
    private boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }
}
