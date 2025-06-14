package com.krickert.search.pipeline.api.controller;

import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import com.krickert.search.orchestrator.kafka.admin.KafkaTopicStatusService;
import com.krickert.search.orchestrator.kafka.admin.TopicOpts;
import com.krickert.search.orchestrator.kafka.admin.CleanupPolicy;
import com.krickert.search.orchestrator.kafka.admin.model.KafkaTopicStatus;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairRequest;
import com.krickert.search.pipeline.api.dto.kafka.KafkaTopicPairResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST controller for Kafka topic management operations.
 * Handles creation, deletion, and status monitoring of topic pairs (input + DLQ).
 */
@Controller("/api/v1/kafka/topics")
@Tag(name = "Kafka Topic Management", description = "Manage Kafka topic pairs for pipeline steps")
public class KafkaTopicController {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicController.class);

    private final KafkaAdminService kafkaAdminService;
    private final KafkaTopicStatusService topicStatusService;

    @Inject
    public KafkaTopicController(KafkaAdminService kafkaAdminService, 
                                KafkaTopicStatusService topicStatusService) {
        this.kafkaAdminService = kafkaAdminService;
        this.topicStatusService = topicStatusService;
    }

    /**
     * Create a topic pair (input + DLQ) for a pipeline step.
     */
    @Post
    @Operation(
        summary = "Create topic pair",
        description = "Creates input and DLQ topics for a pipeline step with automatic naming convention"
    )
    @ApiResponse(responseCode = "200", description = "Topics created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    @ApiResponse(responseCode = "409", description = "Topics already exist")
    @ApiResponse(responseCode = "500", description = "Topic creation failed")
    public HttpResponse<KafkaTopicPairResponse> createTopicPair(@Valid @Body KafkaTopicPairRequest request) {
        LOG.info("Creating topic pair for pipeline {} step {}", request.pipelineId(), request.stepId());
        
        try {
            String inputTopicName = request.inputTopicName();
            String dlqTopicName = request.dlqTopicName();
            
            // Check if topics already exist
            if (kafkaAdminService.doesTopicExist(inputTopicName)) {
                LOG.warn("Input topic {} already exists", inputTopicName);
                return HttpResponse.status(409, "Input topic already exists")
                    .body(KafkaTopicPairResponse.error(request.pipelineId(), request.stepId(), 
                        "Input topic " + inputTopicName + " already exists"));
            }
            
            if (request.createDlq() && kafkaAdminService.doesTopicExist(dlqTopicName)) {
                LOG.warn("DLQ topic {} already exists", dlqTopicName);
                return HttpResponse.status(409, "DLQ topic already exists")
                    .body(KafkaTopicPairResponse.error(request.pipelineId(), request.stepId(),
                        "DLQ topic " + dlqTopicName + " already exists"));
            }

            // Create topic configuration
            Map<String, String> additionalConfig = new HashMap<>();
            additionalConfig.put("retention.ms", request.retentionMs().toString());
            
            TopicOpts topicOpts = new TopicOpts(
                request.partitions(),
                request.replicationFactor(), 
                List.of(CleanupPolicy.DELETE),
                Optional.of(request.retentionMs()),
                Optional.empty(), // retentionBytes
                Optional.empty(), // compressionType
                Optional.empty(), // minInSyncReplicas
                additionalConfig
            );

            // Create input topic
            kafkaAdminService.createTopic(topicOpts, inputTopicName);
            
            // Create DLQ topic if requested
            if (request.createDlq()) {
                kafkaAdminService.createTopic(topicOpts, dlqTopicName);
            }

            LOG.info("Successfully created topic pair: input={}, dlq={}", 
                inputTopicName, request.createDlq() ? dlqTopicName : "none");

            return HttpResponse.ok(KafkaTopicPairResponse.success(
                request.pipelineId(),
                request.stepId(),
                inputTopicName,
                request.createDlq() ? dlqTopicName : null,
                request.partitions(),
                request.replicationFactor()
            ));

        } catch (Exception e) {
            LOG.error("Failed to create topic pair for pipeline {} step {}: {}", 
                request.pipelineId(), request.stepId(), e.getMessage(), e);
            
            return HttpResponse.serverError(KafkaTopicPairResponse.error(
                request.pipelineId(), 
                request.stepId(), 
                "Failed to create topics: " + e.getMessage()
            ));
        }
    }

    /**
     * Get status of topic pair for a pipeline step.
     */
    @Get("/{pipelineId}/{stepId}")
    @Operation(
        summary = "Get topic pair status",
        description = "Gets detailed status information for input and DLQ topics of a pipeline step"
    )
    @ApiResponse(responseCode = "200", description = "Topic status retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Topics not found")
    public HttpResponse<Map<String, KafkaTopicStatus>> getTopicPairStatus(
            @PathVariable String pipelineId, 
            @PathVariable String stepId) {
        
        LOG.info("Getting topic pair status for pipeline {} step {}", pipelineId, stepId);
        
        try {
            String inputTopicName = String.format("yappy.pipeline.%s.step.%s.input", pipelineId, stepId);
            String dlqTopicName = String.format("yappy.pipeline.%s.step.%s.dlq", pipelineId, stepId);
            
            // Check if topics exist
            boolean inputExists = kafkaAdminService.doesTopicExist(inputTopicName);
            boolean dlqExists = kafkaAdminService.doesTopicExist(dlqTopicName);
            
            if (!inputExists && !dlqExists) {
                LOG.warn("No topics found for pipeline {} step {}", pipelineId, stepId);
                return HttpResponse.notFound();
            }

            Map<String, KafkaTopicStatus> statusMap = new HashMap<>();
            if (inputExists) {
                statusMap.put("input", topicStatusService.getTopicStatus(inputTopicName));
            }
            if (dlqExists) {
                statusMap.put("dlq", topicStatusService.getTopicStatus(dlqTopicName));
            }

            return HttpResponse.ok(statusMap);

        } catch (Exception e) {
            LOG.error("Failed to get topic pair status for pipeline {} step {}: {}", 
                pipelineId, stepId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Delete topic pair for a pipeline step.
     */
    @Delete("/{pipelineId}/{stepId}")
    @Operation(
        summary = "Delete topic pair",
        description = "Deletes input and DLQ topics for a pipeline step"
    )
    @ApiResponse(responseCode = "200", description = "Topics deleted successfully")
    @ApiResponse(responseCode = "404", description = "Topics not found")
    @ApiResponse(responseCode = "500", description = "Topic deletion failed")
    public HttpResponse<KafkaTopicPairResponse> deleteTopicPair(
            @PathVariable String pipelineId, 
            @PathVariable String stepId,
            @QueryValue(defaultValue = "false") boolean deleteDlq) {
        
        LOG.info("Deleting topic pair for pipeline {} step {} (includeDlq={})", 
            pipelineId, stepId, deleteDlq);
        
        try {
            String inputTopicName = String.format("yappy.pipeline.%s.step.%s.input", pipelineId, stepId);
            String dlqTopicName = String.format("yappy.pipeline.%s.step.%s.dlq", pipelineId, stepId);
            
            // Check what exists
            boolean inputExists = kafkaAdminService.doesTopicExist(inputTopicName);
            boolean dlqExists = kafkaAdminService.doesTopicExist(dlqTopicName);
            
            if (!inputExists && !dlqExists) {
                LOG.warn("No topics found to delete for pipeline {} step {}", pipelineId, stepId);
                return HttpResponse.notFound();
            }

            // Delete topics that exist
            if (inputExists) {
                kafkaAdminService.deleteTopic(inputTopicName);
            }
            if (deleteDlq && dlqExists) {
                kafkaAdminService.deleteTopic(dlqTopicName);
            }
            
            List<String> deletedTopics = new ArrayList<>();
            if (inputExists) deletedTopics.add(inputTopicName);
            if (deleteDlq && dlqExists) deletedTopics.add(dlqTopicName);

            LOG.info("Successfully deleted topics: {}", deletedTopics);

            return HttpResponse.ok(KafkaTopicPairResponse.success(
                pipelineId, stepId, 
                inputExists ? inputTopicName : null,
                (deleteDlq && dlqExists) ? dlqTopicName : null,
                0, (short) 0  // Not relevant for deletion
            ));

        } catch (Exception e) {
            LOG.error("Failed to delete topic pair for pipeline {} step {}: {}", 
                pipelineId, stepId, e.getMessage(), e);
            
            return HttpResponse.serverError(KafkaTopicPairResponse.error(
                pipelineId, stepId, 
                "Failed to delete topics: " + e.getMessage()
            ));
        }
    }

    /**
     * List all topic pairs managed by YAPPY.
     */
    @Get
    @Operation(
        summary = "List all topic pairs",
        description = "Lists all YAPPY-managed topic pairs with their status"
    )
    @ApiResponse(responseCode = "200", description = "Topic pairs listed successfully")
    public HttpResponse<List<Map<String, Object>>> listTopicPairs() {
        LOG.info("Listing all YAPPY topic pairs");
        
        try {
            Set<String> allTopics = kafkaAdminService.listTopics();
            
            // Filter for YAPPY topics and group by pipeline/step
            List<Map<String, Object>> topicPairs = allTopics.stream()
                .filter(topic -> topic.startsWith("yappy.pipeline."))
                .map(topic -> {
                    String[] parts = topic.split("\\.");
                    if (parts.length >= 6) {
                        String pipelineId = parts[2];
                        String stepId = parts[4];
                        String type = parts[5]; // "input" or "dlq"
                        
                        return Map.<String, Object>of(
                            "pipelineId", pipelineId,
                            "stepId", stepId,
                            "topicName", topic,
                            "type", type
                        );
                    }
                    return null;
                })
                .filter(entry -> entry != null)
                .toList();

            return HttpResponse.ok(topicPairs);

        } catch (Exception e) {
            LOG.error("Failed to list topic pairs: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }
}