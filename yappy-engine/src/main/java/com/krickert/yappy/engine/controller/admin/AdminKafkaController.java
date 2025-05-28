package com.krickert.yappy.engine.controller.admin;

import com.krickert.yappy.engine.controller.admin.dto.KafkaConsumerActionRequest;
import com.krickert.yappy.engine.controller.admin.dto.KafkaConsumerActionResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.validation.Validated;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.micronaut.http.annotation.Body; // Added annotation import
import io.micronaut.http.annotation.Consumes; // Added annotation import
import io.micronaut.http.annotation.Post; // Added annotation import


@Validated
@Controller("/api/kafka")
public class AdminKafkaController {

    private static final Logger LOG = LoggerFactory.getLogger(AdminKafkaController.class);

    private final KafkaListenerManager kafkaListenerManager;

    @Inject
    public AdminKafkaController(KafkaListenerManager kafkaListenerManager) {
        this.kafkaListenerManager = kafkaListenerManager;
    }

    @Get("/consumers")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List Active Kafka Consumers",
               description = "Retrieves the status of all active Kafka consumers managed by the Yappy engine.")
    @ApiResponse(responseCode = "200", description = "Successfully retrieved consumer statuses.")
    @ApiResponse(responseCode = "500", description = "Error retrieving consumer statuses.")
    public Collection<ConsumerStatus> listActiveKafkaConsumers() {
        try {
            Map<String, ConsumerStatus> consumerStatusesMap = kafkaListenerManager.getConsumerStatuses();
            if (consumerStatusesMap == null || consumerStatusesMap.isEmpty()) {
                return Collections.emptyList();
            }
            // The issue asks for "JSON map or array of ConsumerStatus objects".
            // Returning map.values() provides an array/list of ConsumerStatus objects.
            return consumerStatusesMap.values();
        } catch (Exception e) {
            LOG.error("Error retrieving Kafka consumer statuses: {}", e.getMessage(), e);
            // Depending on desired error handling, could throw a specific HTTP exception
            // or return an empty list to indicate failure to retrieve.
            return Collections.emptyList();
        }
    }

    @Post("/consumers/pause")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Pause a Kafka Consumer",
               description = "Pauses a specific Kafka consumer instance.")
    @ApiResponse(responseCode = "200", description = "Consumer action processed. Check 'success' field.")
    @ApiResponse(responseCode = "400", description = "Invalid request payload.")
    public KafkaConsumerActionResponse pauseKafkaConsumer(@Body @Valid KafkaConsumerActionRequest request) {
        LOG.info("Attempting to pause consumer for pipeline: {}, step: {}, topic: {}, group: {}",
            request.getPipelineName(), request.getStepName(), request.getTopic(), request.getGroupId());
        try {
            CompletableFuture<Void> future = kafkaListenerManager.pauseConsumer(
                request.getPipelineName(),
                request.getStepName(),
                request.getTopic(),
                request.getGroupId()
            );
            future.join(); // Block until the future completes
            LOG.info("Successfully paused consumer for pipeline: {}, step: {}, topic: {}, group: {}",
                request.getPipelineName(), request.getStepName(), request.getTopic(), request.getGroupId());
            return new KafkaConsumerActionResponse(true, "Consumer paused successfully.");
        } catch (Exception e) {
            // CompletableFuture.join() throws CompletionException if the future completes exceptionally.
            // The actual cause is wrapped.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.error("Failed to pause consumer for pipeline: {}, step: {}, topic: {}, group: {}. Error: {}",
                request.getPipelineName(), request.getStepName(), request.getTopic(), request.getGroupId(), cause.getMessage(), cause);
            return new KafkaConsumerActionResponse(false, "Failed to pause consumer: " + cause.getMessage());
        }
    }
}
