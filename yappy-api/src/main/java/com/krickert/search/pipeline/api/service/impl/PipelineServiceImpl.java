package com.krickert.search.pipeline.api.service.impl;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.mapper.PipelineMapper;
import com.krickert.search.pipeline.api.service.PipelineService;
import com.krickert.search.pipeline.api.service.ValidationService;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Implementation of PipelineService using ConsulBusinessOperationsService.
 */
@Singleton
public class PipelineServiceImpl implements PipelineService {
    
    private final ConsulBusinessOperationsService consulService;
    private final PipelineMapper pipelineMapper;
    private final ValidationService validationService;
    
    public PipelineServiceImpl(ConsulBusinessOperationsService consulService,
                               PipelineMapper pipelineMapper,
                               ValidationService validationService) {
        this.consulService = consulService;
        this.pipelineMapper = pipelineMapper;
        this.validationService = validationService;
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
                    
                    // For now, we'll use putValue to save the pipeline config
                    // In a real implementation, this would use a proper pipeline save method
                    String key = "clusters/" + cluster + "/pipelines/" + request.id();
                    return consulService.putValue(key, pipelineConfig)
                            .then(Mono.just(pipelineMapper.toPipelineView(pipelineConfig, cluster, 
                                    Map.of("displayName", request.name(),
                                           "description", request.description(),
                                           "tags", request.tags()))));
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
        // ConsulBusinessOperationsService doesn't have a delete method
        // In a real implementation, this would be added
        return Mono.empty();
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
}