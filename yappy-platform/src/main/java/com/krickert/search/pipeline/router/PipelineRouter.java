package com.krickert.search.pipeline.router; // Or your preferred package

import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.model.PipeStepConfigurationDto;
import com.krickert.search.config.consul.service.ConfigurationService; // To look up next step's config
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.executor.GrpcPipelineStepExecutor;
import com.krickert.search.pipeline.kafka.KafkaForwarder; // Your existing forwarder
// Removed StepProcessingResult as routing doesn't seem to depend on success/fail explicitly based on DTOs
// Routing rules seem static per step for now (grpcForwardTo, kafkaPublishTopics)

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
    private final KafkaForwarder kafkaForwarder; // Your existing KafkaForwarder

    @Inject
    public PipelineRouter(ConfigurationService configurationService,
                          GrpcPipelineStepExecutor grpcExecutor,
                          KafkaForwarder kafkaForwarder) {
        this.configurationService = configurationService;
        this.grpcExecutor = grpcExecutor;
        this.kafkaForwarder = kafkaForwarder;
    }

    /**
     * Processes the routing rules for the completed step and forwards the PipeStream
     * asynchronously to the next destinations (gRPC and/or Kafka).
     *
     * @param pipelineName          The name of the current pipeline definition.
     * @param completedStepConfig   The configuration DTO of the step that just finished executing.
     * @param outgoingStreamBuilder The PipeStream builder, containing the results of the completed step
     * (updated doc, history, incremented hop, etc.), ready to be finalized
     * for forwarding. NOTE: current_pipestep_id should NOT be set yet.
     */
    public void routeAndForward(String pipelineName,
                                PipeStepConfigurationDto completedStepConfig,
                                PipeStream.Builder outgoingStreamBuilder) {

        String completedLogicalStepName = completedStepConfig.getName();
        String streamId = outgoingStreamBuilder.getStreamId(); // For logging

        log.debug("Stream [{}]: Routing after step '{}' in pipeline '{}'.", streamId, completedLogicalStepName, pipelineName);

        // Fetch the full pipeline config once if needed for multiple lookups
        PipelineConfigDto pipelineConfig = configurationService.getPipeline(pipelineName);
        if (pipelineConfig == null) {
            log.error("Stream [{}]: Cannot route - Pipeline configuration '{}' not found.", streamId, pipelineName);
            // Decide how to handle this critical error (e.g., log to DLQ, throw exception?)
            return;
        }

        // --- 1. Handle gRPC Forwarding ---
        List<String> nextGrpcSteps = completedStepConfig.getGrpcForwardTo();
        if (nextGrpcSteps != null && !nextGrpcSteps.isEmpty()) {
            for (String nextLogicalStepName : nextGrpcSteps) {
                if (nextLogicalStepName == null || nextLogicalStepName.isBlank()) {
                    log.warn("Stream [{}]: Blank or null gRPC forward target found in routing rules for step '{}'. Skipping.", streamId, completedLogicalStepName);
                    continue;
                }

                PipeStepConfigurationDto nextStepConfigDto = pipelineConfig.getServices().get(nextLogicalStepName);
                if (nextStepConfigDto == null) {
                    log.error("Stream [{}]: Routing configuration error! Next logical step '{}' (from step '{}') not found in pipeline '{}'. Cannot forward.",
                            streamId, nextLogicalStepName, completedLogicalStepName, pipelineName);
                    // TODO: Error handling strategy (e.g., send original stream to DLQ?)
                    continue; // Skip this invalid route
                }

                String targetConsulAppName = nextStepConfigDto.getServiceImplementation();
                if (targetConsulAppName == null || targetConsulAppName.isBlank()) {
                     log.error("Stream [{}]: Routing configuration error! Next logical step '{}' in pipeline '{}' has no serviceImplementation defined. Cannot forward.",
                            streamId, nextLogicalStepName, pipelineName);
                    // TODO: Error handling strategy
                    continue; // Skip this invalid route
                }

                // Prepare PipeStream specifically for this next step
                PipeStream streamForNextStep = outgoingStreamBuilder
                        .setCurrentPipestepId(nextLogicalStepName) // Set the ID for the receiver
                        .build(); // Build the final stream for this specific forward

                log.info("Stream [{}]: Forwarding via gRPC from step '{}' to step '{}' (service: '{}')",
                        streamId, completedLogicalStepName, nextLogicalStepName, targetConsulAppName);

                // Call executor (async, best effort)
                CompletableFuture<Void> forwardFuture = grpcExecutor.forwardPipeStreamAsync(targetConsulAppName, streamForNextStep);

                // Handle async result (logging errors on failure is our agreed strategy)
                forwardFuture.whenComplete((result, exception) -> {
                    if (exception != null) {
                        // Log the failure to dispatch
                        log.error("Stream [{}]: Failed to dispatch PipeStream via gRPC from step '{}' to step '{}' (service: '{}'). Reason: {}",
                                streamId, completedLogicalStepName, nextLogicalStepName, targetConsulAppName, exception.getMessage(), exception);
                        // DLQ logic could potentially be triggered here if needed in the future
                    } else {
                        log.debug("Stream [{}]: Successfully dispatched PipeStream via gRPC from step '{}' to step '{}' (service: '{}')",
                                streamId, completedLogicalStepName, nextLogicalStepName, targetConsulAppName);
                    }
                });
            }
        } else {
             log.debug("Stream [{}]: No gRPC forward targets defined for step '{}'.", streamId, completedLogicalStepName);
        }

        // --- 2. Handle Kafka Forwarding ---
        List<String> kafkaTopics = completedStepConfig.getKafkaPublishTopics();
        if (kafkaTopics != null && !kafkaTopics.isEmpty()) {
            // Prepare PipeStream for Kafka. Does the Kafka consumer need the next logical step ID?
            // If multiple consumers could read from the same topic for different logical steps, YES.
            // Let's assume for now the Kafka consumer logic is similar to gRPC receiver logic
            // and relies on routing rules to know the *next* step after Kafka.
            // So, when publishing, maybe clear or set a specific 'kafka-source-step' ID? TBD based on consumer needs.
            // For now, let's just send the stream state *after* the current step completed.

            // Build the stream state ONCE for all Kafka topics from this step
             PipeStream streamForKafka = outgoingStreamBuilder
                     // .clearCurrentPipestepId() // Or set a specific marker? Discuss if needed.
                     // Let's assume we send the state as-is, and Kafka consumer logic handles context.
                     .build();

            for (String topicName : kafkaTopics) {
                 if (topicName == null || topicName.isBlank()) {
                    log.warn("Stream [{}]: Blank or null Kafka publish topic found in routing rules for step '{}'. Skipping.", streamId, completedLogicalStepName);
                    continue;
                }
                log.info("Stream [{}]: Forwarding via Kafka from step '{}' to topic '{}'",
                        streamId, completedLogicalStepName, topicName);

                // Use your existing KafkaForwarder
                // Assuming KafkaForwarder doesn't need a Route object, just topic and PipeStream
                try {
                   kafkaForwarder.forwardToKafka(streamForKafka, topicName); // Adapt if method signature is different
                } catch (Exception e) {
                    // Log errors from KafkaForwarder if it throws synchronously
                    log.error("Stream [{}]: Exception while dispatching PipeStream via Kafka from step '{}' to topic '{}'. Reason: {}",
                           streamId, completedLogicalStepName, topicName, e.getMessage(), e);
                }
            }
        } else {
            log.debug("Stream [{}]: No Kafka publish topics defined for step '{}'.", streamId, completedLogicalStepName);
        }

         if ((nextGrpcSteps == null || nextGrpcSteps.isEmpty()) && (kafkaTopics == null || kafkaTopics.isEmpty())) {
              log.info("Stream [{}]: Step '{}' is a terminal step in pipeline '{}' (no gRPC or Kafka forward routes).", streamId, completedLogicalStepName, pipelineName);
         }
    }
     // Overload or separate method for handling failures / routing to DLQ if needed later
    // public void routeToDLQ(...) { ... }
}