package com.krickert.search.pipeline.api.service.impl;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.mapper.PipelineMapper;
import com.krickert.search.pipeline.api.service.PipelineService;
import com.krickert.search.pipeline.api.service.ValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of PipelineService using ConsulBusinessOperationsService.
 */
@Singleton
public class PipelineServiceImpl implements PipelineService {
    
    private final ConsulBusinessOperationsService consulService;
    private final PipelineMapper pipelineMapper;
    private final ValidationService validationService;
    private final ObjectMapper objectMapper;
    
    public PipelineServiceImpl(ConsulBusinessOperationsService consulService,
                               PipelineMapper pipelineMapper,
                               ValidationService validationService,
                               ObjectMapper objectMapper) {
        this.consulService = consulService;
        this.pipelineMapper = pipelineMapper;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Flux<PipelineSummary> listPipelines(String cluster) {
        // Get pipeline names and then fetch each pipeline
        return consulService.listPipelineNames(cluster)
                .flatMapMany(Flux::fromIterable)
                .flatMap(name -> consulService.getSpecificPipelineConfig(cluster, name)
                        .map(opt -> opt.orElse(null))
                        .filter(config -> config != null)
                        .map(config -> pipelineMapper.toPipelineSummary(config, Map.of())));
    }
    
    @Override
    public Mono<PipelineView> getPipeline(String cluster, String pipelineId) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(config -> 
                        Mono.just(pipelineMapper.toPipelineView(config, cluster, Map.of()))
                ).orElse(Mono.empty()));
    }
    
    @Override
    public Mono<PipelineView> createPipeline(String cluster, CreatePipelineRequest request) {
        // First validate
        return validatePipeline(request)
                .flatMap(validation -> {
                    if (!validation.valid()) {
                        return Mono.error(new IllegalArgumentException("Invalid pipeline: " + validation.errors()));
                    }
                    
                    var pipelineConfig = pipelineMapper.toPipelineConfig(request);
                    
                    // Get the existing cluster config and update it with the new pipeline
                    return consulService.getPipelineClusterConfig(cluster)
                            .flatMap(clusterOptional -> {
                                // Get existing cluster config or create new one
                                var existingCluster = clusterOptional.orElse(
                                    com.krickert.search.config.pipeline.model.PipelineClusterConfig.builder()
                                            .clusterName(cluster)
                                            .pipelineGraphConfig(com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                                    .pipelines(Map.of())
                                                    .build())
                                            .build()
                                );
                                
                                // Get existing graph from cluster config
                                var existingGraph = existingCluster.pipelineGraphConfig();
                                var existingPipelines = (existingGraph != null && existingGraph.pipelines() != null) 
                                    ? existingGraph.pipelines() : Map.<String, com.krickert.search.config.pipeline.model.PipelineConfig>of();
                                
                                // Add the new pipeline to the graph
                                var updatedPipelines = new java.util.HashMap<>(existingPipelines);
                                updatedPipelines.put(request.id(), pipelineConfig);
                                
                                var updatedGraph = com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                        .pipelines(updatedPipelines)
                                        .build();
                                
                                // Create updated cluster config with the new graph
                                var updatedCluster = existingCluster.toBuilder()
                                        .pipelineGraphConfig(updatedGraph)
                                        .build();
                                
                                // Store the updated cluster config at the correct key
                                return consulService.storeClusterConfiguration(cluster, updatedCluster)
                                        .then(Mono.just(pipelineMapper.toPipelineView(pipelineConfig, cluster, 
                                                Map.of("displayName", request.name(),
                                                       "description", request.description(),
                                                       "tags", request.tags()))));
                            });
                });
    }
    
    @Override
    public Mono<PipelineView> updatePipeline(String cluster, String pipelineId, UpdatePipelineRequest request) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(existing -> {
                    // Update only provided fields
                    // For now, just return the existing config
                    // In a real implementation, this would merge the updates
                    String key = "clusters/" + cluster + "/pipelines/" + pipelineId;
                    return consulService.putValue(key, existing)
                            .then(Mono.just(pipelineMapper.toPipelineView(existing, cluster, Map.of())));
                }).orElse(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId))));
    }
    
    @Override
    public Mono<Void> deletePipeline(String cluster, String pipelineId) {
        return consulService.getPipelineClusterConfig(cluster)
                .flatMap(clusterOptional -> {
                    if (clusterOptional.isEmpty()) {
                        return Mono.error(new IllegalArgumentException("Cluster not found: " + cluster));
                    }
                    
                    var existingCluster = clusterOptional.get();
                    var existingGraph = existingCluster.pipelineGraphConfig();
                    
                    if (existingGraph == null || existingGraph.pipelines() == null || 
                        !existingGraph.pipelines().containsKey(pipelineId)) {
                        return Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId));
                    }
                    
                    // Remove the pipeline from the graph
                    var updatedPipelines = new java.util.HashMap<>(existingGraph.pipelines());
                    updatedPipelines.remove(pipelineId);
                    
                    var updatedGraph = com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                            .pipelines(updatedPipelines)
                            .build();
                    
                    var updatedCluster = existingCluster.toBuilder()
                            .pipelineGraphConfig(updatedGraph)
                            .build();
                    
                    // Store the updated cluster configuration
                    return consulService.storeClusterConfiguration(cluster, updatedCluster)
                            .then();
                });
    }
    
    @Override
    public Mono<TestPipelineResponse> testPipeline(String cluster, String pipelineId, TestPipelineRequest request) {
        // TODO: Implement pipeline testing
        return Mono.just(new TestPipelineResponse(
                true,
                java.time.Duration.ofSeconds(1),
                List.of(),
                List.of(),
                Map.of("result", "Test not implemented")
        ));
    }
    
    @Override
    public Mono<PipelineStatus> getPipelineStatus(String cluster, String pipelineId) {
        // TODO: Implement status retrieval from metrics/monitoring
        return Mono.just(new PipelineStatus(
                pipelineId,
                "active",
                java.time.Instant.now(),
                0L,
                0L,
                0L,
                List.of(),
                Map.of()
        ));
    }
    
    @Override
    public Mono<ValidationResponse> validatePipeline(CreatePipelineRequest request) {
        var pipelineConfig = pipelineMapper.toPipelineConfig(request);
        return validationService.validatePipelineConfig(pipelineConfig)
                .map(result -> new ValidationResponse(
                        result.valid(),
                        result.messages().stream()
                                .filter(m -> m.severity().equals("error"))
                                .map(m -> new ValidationResponse.ValidationError(m.field(), m.message(), m.rule()))
                                .toList(),
                        result.messages().stream()
                                .filter(m -> m.severity().equals("warning"))
                                .map(m -> new ValidationResponse.ValidationWarning(m.field(), m.message(), m.rule()))
                                .toList()
                ));
    }
    
    @Override
    public Flux<PipelineTemplate> getTemplates() {
        // TODO: Load from resources or configuration
        return Flux.just(
                new PipelineTemplate(
                        "nlp-basic",
                        "Basic NLP Pipeline",
                        "Extract, chunk, and embed text documents",
                        List.of("Document processing", "Search indexing"),
                        "text-processing",
                        List.of(
                                new CreatePipelineRequest.StepDefinition(
                                        "extract", "tika-parser", Map.of(), List.of("chunk")
                                ),
                                new CreatePipelineRequest.StepDefinition(
                                        "chunk", "chunker", Map.of("chunkSize", 512), List.of("embed")
                                ),
                                new CreatePipelineRequest.StepDefinition(
                                        "embed", "embedder", Map.of(), List.of()
                                )
                        ),
                        List.of(),
                        Map.of()
                )
        );
    }
    
    @Override
    public Mono<PipelineView> createFromTemplate(String cluster, CreateFromTemplateRequest request) {
        return getTemplates()
                .filter(template -> template.id().equals(request.templateId()))
                .next()
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Template not found: " + request.templateId())))
                .flatMap(template -> {
                    var createRequest = new CreatePipelineRequest(
                            request.pipelineId(),
                            request.pipelineName(),
                            "Created from template: " + template.name(),
                            template.steps(),
                            List.of("from-template", template.id()),
                            request.configOverrides()
                    );
                    return createPipeline(cluster, createRequest);
                });
    }
    
    @Override
    public Mono<PipelineConfig> getPipelineConfig(String cluster, String pipelineId) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty()));
    }
    
    @Override
    public Mono<com.krickert.search.config.pipeline.model.PipelineClusterConfig> getClusterConfig(String cluster) {
        // TODO: Implement when ConsulBusinessOperationsService exposes cluster config retrieval
        // For now, return empty to indicate not implemented
        return Mono.empty();
    }
    
    // ========================================
    // Pipeline Step Management Implementation
    // ========================================
    
    @Override
    public Mono<PipelineStepView> getPipelineStep(String cluster, String pipelineId, String stepId) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(config -> {
                    // Find the specific step
                    var step = config.pipelineSteps().get(stepId);
                    
                    if (step == null) {
                        return Mono.<PipelineStepView>error(new IllegalArgumentException("Step not found: " + stepId));
                    }
                    
                    // Find previous steps
                    var previousSteps = config.pipelineSteps().values().stream()
                            .filter(s -> s.outputs() != null && s.outputs().values().stream()
                                    .anyMatch(output -> output.targetStepName() != null && 
                                            output.targetStepName().equals(stepId)))
                            .map(s -> s.stepName())
                            .toList();
                    
                    // Create Kafka topics info
                    var kafkaTopics = new PipelineStepView.KafkaTopicsInfo(
                            generateKafkaTopicName(pipelineId, stepId, "input"),
                            generateKafkaTopicName(pipelineId, stepId, "output"),
                            generateKafkaTopicName(pipelineId, stepId, "error"),
                            false // TODO: Check if topics actually exist
                    );
                    
                    // Build next steps list from outputs
                    var nextSteps = step.outputs() != null ? 
                            step.outputs().values().stream()
                                    .map(output -> output.targetStepName())
                                    .filter(name -> name != null)
                                    .distinct()
                                    .toList() : List.<String>of();
                    
                    // Get module name from processorInfo
                    String moduleName = null;
                    if (step.processorInfo() != null) {
                        if (step.processorInfo().grpcServiceName() != null && !step.processorInfo().grpcServiceName().isBlank()) {
                            moduleName = step.processorInfo().grpcServiceName();
                        } else if (step.processorInfo().internalProcessorBeanName() != null && !step.processorInfo().internalProcessorBeanName().isBlank()) {
                            moduleName = step.processorInfo().internalProcessorBeanName();
                        }
                    }
                    
                    return Mono.just(new PipelineStepView(
                            step.stepName(),
                            moduleName,
                            jsonConfigOptionsToMap(step.customConfig()),
                            nextSteps,
                            previousSteps,
                            null, // Position not stored in current model
                            kafkaTopics,
                            Map.of("cluster", cluster, "pipeline", pipelineId)
                    ));
                }).orElse(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId))));
    }
    
    @Override
    public Mono<PipelineStepView> addPipelineStep(String cluster, String pipelineId, AddPipelineStepRequest request) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(existingPipeline -> {
                    // Check if step ID already exists
                    boolean stepExists = existingPipeline.pipelineSteps() != null && 
                            existingPipeline.pipelineSteps().containsKey(request.stepId());
                    
                    if (stepExists) {
                        return Mono.<PipelineStepView>error(new IllegalArgumentException("Step ID already exists: " + request.stepId()));
                    }
                    
                    // Create outputs for the new step based on next steps
                    var outputs = new java.util.HashMap<String, com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget>();
                    if (request.next() != null) {
                        for (String nextStep : request.next()) {
                            // For GRPC transport, we need to provide GrpcTransportConfig
                            var grpcConfig = new com.krickert.search.config.pipeline.model.GrpcTransportConfig(
                                    nextStep, // Service name is the target step name
                                    Map.of() // Empty properties for now
                            );
                            outputs.put("to_" + nextStep, new com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget(
                                    nextStep,
                                    com.krickert.search.config.pipeline.model.TransportType.GRPC,
                                    grpcConfig,
                                    null
                            ));
                        }
                    }
                    
                    // Create new step
                    var newStep = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                            .stepName(request.stepId())
                            .stepType(com.krickert.search.config.pipeline.model.StepType.PIPELINE)
                            .processorInfo(new com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo(
                                    request.module(), null
                            ))
                            .customConfig(mapToJsonConfigOptions(request.config()))
                            .outputs(outputs)
                            .description(null)
                            .customConfigSchemaId(null)
                            .kafkaInputs(List.of())
                            .maxRetries(0)
                            .retryBackoffMs(1000L)
                            .maxRetryBackoffMs(30000L)
                            .retryBackoffMultiplier(2.0)
                            .stepTimeoutMs(null)
                            .build();
                    
                    // Update existing steps to connect to new step
                    var updatedSteps = new java.util.HashMap<>(existingPipeline.pipelineSteps() != null ? 
                            existingPipeline.pipelineSteps() : Map.of());
                    
                    // Update previous steps to add output to the new step
                    if (request.previous() != null) {
                        for (String prevStepId : request.previous()) {
                            var prevStep = updatedSteps.get(prevStepId);
                            if (prevStep != null) {
                                var updatedOutputs = new java.util.HashMap<>(prevStep.outputs() != null ? 
                                        prevStep.outputs() : Map.of());
                                var grpcConfig = new com.krickert.search.config.pipeline.model.GrpcTransportConfig(
                                        request.stepId(),
                                        Map.of()
                                );
                                updatedOutputs.put("to_" + request.stepId(), new com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget(
                                        request.stepId(),
                                        com.krickert.search.config.pipeline.model.TransportType.GRPC,
                                        grpcConfig,
                                        null
                                ));
                                
                                var updatedPrevStep = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                                        .stepName(prevStep.stepName())
                                        .stepType(prevStep.stepType())
                                        .description(prevStep.description())
                                        .customConfigSchemaId(prevStep.customConfigSchemaId())
                                        .customConfig(prevStep.customConfig())
                                        .kafkaInputs(prevStep.kafkaInputs())
                                        .processorInfo(prevStep.processorInfo())
                                        .outputs(updatedOutputs)
                                        .maxRetries(prevStep.maxRetries())
                                        .retryBackoffMs(prevStep.retryBackoffMs())
                                        .maxRetryBackoffMs(prevStep.maxRetryBackoffMs())
                                        .retryBackoffMultiplier(prevStep.retryBackoffMultiplier())
                                        .stepTimeoutMs(prevStep.stepTimeoutMs())
                                        .build();
                                
                                updatedSteps.put(prevStepId, updatedPrevStep);
                            }
                        }
                    }
                    
                    // Add the new step
                    updatedSteps.put(request.stepId(), newStep);
                    
                    // Create updated pipeline
                    var updatedPipeline = new com.krickert.search.config.pipeline.model.PipelineConfig(
                            existingPipeline.name(),
                            updatedSteps
                    );
                    
                    // Store the updated pipeline through cluster config
                    return consulService.getPipelineClusterConfig(cluster)
                            .flatMap(clusterOptional -> {
                                var existingCluster = clusterOptional.orElse(
                                    com.krickert.search.config.pipeline.model.PipelineClusterConfig.builder()
                                            .clusterName(cluster)
                                            .pipelineGraphConfig(com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                                    .pipelines(Map.of())
                                                    .build())
                                            .build()
                                );
                                
                                var existingGraph = existingCluster.pipelineGraphConfig();
                                var existingPipelines = (existingGraph != null && existingGraph.pipelines() != null) 
                                    ? existingGraph.pipelines() : Map.<String, com.krickert.search.config.pipeline.model.PipelineConfig>of();
                                
                                var updatedPipelines = new java.util.HashMap<>(existingPipelines);
                                updatedPipelines.put(pipelineId, updatedPipeline);
                                
                                var updatedGraph = com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                        .pipelines(updatedPipelines)
                                        .build();
                                
                                var updatedCluster = existingCluster.toBuilder()
                                        .pipelineGraphConfig(updatedGraph)
                                        .build();
                                
                                return consulService.storeClusterConfiguration(cluster, updatedCluster)
                                        .then(getPipelineStep(cluster, pipelineId, request.stepId()));
                            });
                    
                }).orElse(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId))));
    }
    
    @Override
    public Mono<PipelineStepView> updatePipelineStep(String cluster, String pipelineId, String stepId, UpdatePipelineStepRequest request) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(existingPipeline -> {
                    // Find the step to update
                    var existingStep = existingPipeline.pipelineSteps() != null ? 
                            existingPipeline.pipelineSteps().get(stepId) : null;
                    
                    if (existingStep == null) {
                        return Mono.<PipelineStepView>error(new IllegalArgumentException("Step not found: " + stepId));
                    }
                    
                    // Create a copy of all steps
                    var updatedSteps = new java.util.HashMap<>(existingPipeline.pipelineSteps());
                    
                    // Update the target step
                    var updatedOutputs = existingStep.outputs() != null ? 
                            new java.util.HashMap<>(existingStep.outputs()) : new java.util.HashMap<String, com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget>();
                    
                    // Update outputs if next steps changed
                    if (request.next() != null) {
                        // Clear existing outputs and add new ones
                        updatedOutputs.clear();
                        for (String nextStep : request.next()) {
                            var grpcConfig = new com.krickert.search.config.pipeline.model.GrpcTransportConfig(
                                    nextStep,
                                    Map.of()
                            );
                            updatedOutputs.put("to_" + nextStep, new com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget(
                                    nextStep,
                                    com.krickert.search.config.pipeline.model.TransportType.GRPC,
                                    grpcConfig,
                                    null
                            ));
                        }
                    }
                    
                    // Build updated step
                    var stepBuilder = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                            .stepName(existingStep.stepName())
                            .stepType(existingStep.stepType())
                            .description(existingStep.description())
                            .customConfigSchemaId(existingStep.customConfigSchemaId())
                            .customConfig(request.config() != null ? mapToJsonConfigOptions(request.config()) : existingStep.customConfig())
                            .kafkaInputs(existingStep.kafkaInputs())
                            .outputs(updatedOutputs)
                            .maxRetries(existingStep.maxRetries())
                            .retryBackoffMs(existingStep.retryBackoffMs())
                            .maxRetryBackoffMs(existingStep.maxRetryBackoffMs())
                            .retryBackoffMultiplier(existingStep.retryBackoffMultiplier())
                            .stepTimeoutMs(existingStep.stepTimeoutMs());
                    
                    // Update processor info if module changed
                    if (request.module() != null) {
                        stepBuilder.processorInfo(new com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo(
                                request.module(), null
                        ));
                    } else {
                        stepBuilder.processorInfo(existingStep.processorInfo());
                    }
                    
                    var updatedStep = stepBuilder.build();
                    updatedSteps.put(stepId, updatedStep);
                    
                    // Update connections from other steps if previous was changed
                    if (request.previous() != null) {
                        // Remove this step from all outputs
                        for (var entry : updatedSteps.entrySet()) {
                            if (!entry.getKey().equals(stepId)) {
                                var step = entry.getValue();
                                if (step.outputs() != null) {
                                    var stepOutputs = new java.util.HashMap<>(step.outputs());
                                    stepOutputs.entrySet().removeIf(e -> stepId.equals(e.getValue().targetStepName()));
                                    
                                    if (!stepOutputs.equals(step.outputs())) {
                                        var rebuiltStep = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                                                .stepName(step.stepName())
                                                .stepType(step.stepType())
                                                .description(step.description())
                                                .customConfigSchemaId(step.customConfigSchemaId())
                                                .customConfig(step.customConfig())
                                                .kafkaInputs(step.kafkaInputs())
                                                .processorInfo(step.processorInfo())
                                                .outputs(stepOutputs)
                                                .maxRetries(step.maxRetries())
                                                .retryBackoffMs(step.retryBackoffMs())
                                                .maxRetryBackoffMs(step.maxRetryBackoffMs())
                                                .retryBackoffMultiplier(step.retryBackoffMultiplier())
                                                .stepTimeoutMs(step.stepTimeoutMs())
                                                .build();
                                        updatedSteps.put(entry.getKey(), rebuiltStep);
                                    }
                                }
                            }
                        }
                        
                        // Add connections from new previous steps
                        for (String prevStepId : request.previous()) {
                            var prevStep = updatedSteps.get(prevStepId);
                            if (prevStep != null) {
                                var prevOutputs = new java.util.HashMap<>(prevStep.outputs() != null ? 
                                        prevStep.outputs() : Map.of());
                                var grpcConfig = new com.krickert.search.config.pipeline.model.GrpcTransportConfig(
                                        stepId,
                                        Map.of()
                                );
                                prevOutputs.put("to_" + stepId, new com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget(
                                        stepId,
                                        com.krickert.search.config.pipeline.model.TransportType.GRPC,
                                        grpcConfig,
                                        null
                                ));
                                
                                var rebuiltPrevStep = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                                        .stepName(prevStep.stepName())
                                        .stepType(prevStep.stepType())
                                        .description(prevStep.description())
                                        .customConfigSchemaId(prevStep.customConfigSchemaId())
                                        .customConfig(prevStep.customConfig())
                                        .kafkaInputs(prevStep.kafkaInputs())
                                        .processorInfo(prevStep.processorInfo())
                                        .outputs(prevOutputs)
                                        .maxRetries(prevStep.maxRetries())
                                        .retryBackoffMs(prevStep.retryBackoffMs())
                                        .maxRetryBackoffMs(prevStep.maxRetryBackoffMs())
                                        .retryBackoffMultiplier(prevStep.retryBackoffMultiplier())
                                        .stepTimeoutMs(prevStep.stepTimeoutMs())
                                        .build();
                                
                                updatedSteps.put(prevStepId, rebuiltPrevStep);
                            }
                        }
                    }
                    
                    // Create updated pipeline
                    var updatedPipeline = new com.krickert.search.config.pipeline.model.PipelineConfig(
                            existingPipeline.name(),
                            updatedSteps
                    );
                    
                    // Store the updated pipeline through cluster config
                    return consulService.getPipelineClusterConfig(cluster)
                            .flatMap(clusterOptional -> {
                                var existingCluster = clusterOptional.orElse(
                                    com.krickert.search.config.pipeline.model.PipelineClusterConfig.builder()
                                            .clusterName(cluster)
                                            .pipelineGraphConfig(com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                                    .pipelines(Map.of())
                                                    .build())
                                            .build()
                                );
                                
                                var existingGraph = existingCluster.pipelineGraphConfig();
                                var existingPipelines = (existingGraph != null && existingGraph.pipelines() != null) 
                                    ? existingGraph.pipelines() : Map.<String, com.krickert.search.config.pipeline.model.PipelineConfig>of();
                                
                                var updatedPipelines = new java.util.HashMap<>(existingPipelines);
                                updatedPipelines.put(pipelineId, updatedPipeline);
                                
                                var updatedGraph = com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                        .pipelines(updatedPipelines)
                                        .build();
                                
                                var updatedCluster = existingCluster.toBuilder()
                                        .pipelineGraphConfig(updatedGraph)
                                        .build();
                                
                                return consulService.storeClusterConfiguration(cluster, updatedCluster)
                                        .then(getPipelineStep(cluster, pipelineId, stepId));
                            });
                    
                }).orElse(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId))));
    }
    
    @Override
    public Mono<Void> removePipelineStep(String cluster, String pipelineId, String stepId) {
        return consulService.getSpecificPipelineConfig(cluster, pipelineId)
                .flatMap(opt -> opt.map(existingPipeline -> {
                    // Check if step exists
                    var stepToRemove = existingPipeline.pipelineSteps() != null ? 
                            existingPipeline.pipelineSteps().get(stepId) : null;
                    
                    if (stepToRemove == null) {
                        return Mono.<Void>error(new IllegalArgumentException("Step not found: " + stepId));
                    }
                    
                    // Check if step has dependencies (outputs to other steps)
                    if (stepToRemove.outputs() != null && !stepToRemove.outputs().isEmpty()) {
                        return Mono.<Void>error(new IllegalArgumentException("Cannot remove step with dependencies: " + stepId));
                    }
                    
                    // Create a copy of all steps and remove the target step
                    var updatedSteps = new java.util.HashMap<>(existingPipeline.pipelineSteps());
                    updatedSteps.remove(stepId);
                    
                    // Remove references to the deleted step from other steps' outputs
                    for (var entry : updatedSteps.entrySet()) {
                        var step = entry.getValue();
                        if (step.outputs() != null) {
                            var stepOutputs = new java.util.HashMap<>(step.outputs());
                            boolean modified = stepOutputs.entrySet().removeIf(e -> 
                                    stepId.equals(e.getValue().targetStepName()));
                            
                            if (modified) {
                                var rebuiltStep = com.krickert.search.config.pipeline.model.PipelineStepConfig.builder()
                                        .stepName(step.stepName())
                                        .stepType(step.stepType())
                                        .description(step.description())
                                        .customConfigSchemaId(step.customConfigSchemaId())
                                        .customConfig(step.customConfig())
                                        .kafkaInputs(step.kafkaInputs())
                                        .processorInfo(step.processorInfo())
                                        .outputs(stepOutputs)
                                        .build();
                                updatedSteps.put(entry.getKey(), rebuiltStep);
                            }
                        }
                    }
                    
                    // Create updated pipeline
                    var updatedPipeline = new com.krickert.search.config.pipeline.model.PipelineConfig(
                            existingPipeline.name(),
                            updatedSteps
                    );
                    
                    // Store the updated pipeline through cluster config
                    return consulService.getPipelineClusterConfig(cluster)
                            .flatMap(clusterOptional -> {
                                var existingCluster = clusterOptional.orElse(
                                    com.krickert.search.config.pipeline.model.PipelineClusterConfig.builder()
                                            .clusterName(cluster)
                                            .pipelineGraphConfig(com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                                    .pipelines(Map.of())
                                                    .build())
                                            .build()
                                );
                                
                                var existingGraph = existingCluster.pipelineGraphConfig();
                                var existingPipelines = (existingGraph != null && existingGraph.pipelines() != null) 
                                    ? existingGraph.pipelines() : Map.<String, com.krickert.search.config.pipeline.model.PipelineConfig>of();
                                
                                var updatedPipelines = new java.util.HashMap<>(existingPipelines);
                                updatedPipelines.put(pipelineId, updatedPipeline);
                                
                                var updatedGraph = com.krickert.search.config.pipeline.model.PipelineGraphConfig.builder()
                                        .pipelines(updatedPipelines)
                                        .build();
                                
                                var updatedCluster = existingCluster.toBuilder()
                                        .pipelineGraphConfig(updatedGraph)
                                        .build();
                                
                                return consulService.storeClusterConfiguration(cluster, updatedCluster)
                                        .then();
                            });
                    
                }).orElse(Mono.error(new IllegalArgumentException("Pipeline not found: " + pipelineId))));
    }
    
    private String generateKafkaTopicName(String pipelineId, String stepId, String type) {
        return String.format("yappy.pipeline.%s.step.%s.%s", pipelineId, stepId, type);
    }
    
    private com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions mapToJsonConfigOptions(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            // When no config provided, return empty JSON but modules can use configParams for simple string properties
            return new com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions(
                    objectMapper.createObjectNode(), Map.of()
            );
        }
        
        // Check if all values are strings - if so, we could use configParams instead
        boolean allStrings = config.values().stream().allMatch(v -> v instanceof String);
        
        if (allStrings) {
            // Simple case: all values are strings, so we can use configParams
            Map<String, String> stringParams = config.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toString(),
                            (oldValue, newValue) -> oldValue,
                            LinkedHashMap::new
                    ));
            return new com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions(
                    objectMapper.createObjectNode(), stringParams
            );
        } else {
            // Complex case: use jsonConfig for nested objects, arrays, etc.
            try {
                var jsonNode = objectMapper.valueToTree(config);
                return new com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions(jsonNode, Map.of());
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to convert config to JSON", e);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonConfigOptionsToMap(com.krickert.search.config.pipeline.model.PipelineStepConfig.JsonConfigOptions configOptions) {
        if (configOptions == null) {
            return Map.of();
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        // First add configParams if present
        if (configOptions.configParams() != null) {
            result.putAll(configOptions.configParams());
        }
        
        // Then add jsonConfig if present (this may override configParams)
        if (configOptions.jsonConfig() != null) {
            try {
                Map<String, Object> jsonMap = objectMapper.convertValue(configOptions.jsonConfig(), Map.class);
                if (jsonMap != null) {
                    result.putAll(jsonMap);
                }
            } catch (Exception e) {
                // If conversion fails, ignore
            }
        }
        
        return result;
    }
}