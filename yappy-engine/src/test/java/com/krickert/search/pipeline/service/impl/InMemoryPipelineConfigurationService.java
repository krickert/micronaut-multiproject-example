package com.krickert.search.pipeline.service.impl;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.pipeline.service.PipelineConfigurationService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of PipelineConfigurationService for testing.
 */
public class InMemoryPipelineConfigurationService implements PipelineConfigurationService {
    
    private final Map<String, PipelineConfig> pipelines = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<PipelineConfig>> watchers = new ConcurrentHashMap<>();
    
    @Override
    public Mono<PipelineConfig> getPipelineConfig(String pipelineId) {
        return Mono.justOrEmpty(pipelines.get(pipelineId));
    }
    
    @Override
    public Mono<Void> savePipelineConfig(String pipelineId, PipelineConfig config) {
        return Mono.fromRunnable(() -> {
            pipelines.put(pipelineId, config);
            
            // Notify watchers
            Sinks.Many<PipelineConfig> sink = watchers.get(pipelineId);
            if (sink != null) {
                sink.tryEmitNext(config);
            }
        });
    }
    
    @Override
    public Mono<Void> deletePipelineConfig(String pipelineId) {
        return Mono.fromRunnable(() -> {
            pipelines.remove(pipelineId);
            
            // Complete watchers
            Sinks.Many<PipelineConfig> sink = watchers.remove(pipelineId);
            if (sink != null) {
                sink.tryEmitComplete();
            }
        });
    }
    
    @Override
    public Flux<PipelineConfig> listPipelineConfigs() {
        return Flux.fromIterable(pipelines.values());
    }
    
    @Override
    public Flux<PipelineConfig> watchPipelineConfig(String pipelineId) {
        return Flux.defer(() -> {
            Sinks.Many<PipelineConfig> sink = watchers.computeIfAbsent(
                    pipelineId, 
                    k -> Sinks.many().multicast().onBackpressureBuffer()
            );
            
            // Send current config if exists
            PipelineConfig current = pipelines.get(pipelineId);
            if (current != null) {
                sink.tryEmitNext(current);
            }
            
            return sink.asFlux();
        });
    }
    
    @Override
    public Mono<ValidationResult> validatePipelineConfig(PipelineConfig config) {
        return Mono.fromSupplier(() -> {
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            if (config == null) {
                errors.add("Pipeline configuration is null");
                return new ValidationResult(false, errors, warnings);
            }
            
            if (config.name() == null || config.name().isBlank()) {
                errors.add("Pipeline name is required");
            }
            
            if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
                warnings.add("Pipeline has no steps configured");
            } else {
                // Validate each step
                for (Map.Entry<String, PipelineStepConfig> entry : config.pipelineSteps().entrySet()) {
                    PipelineStepConfig step = entry.getValue();
                    if (step.processorInfo() == null || 
                        (step.processorInfo().grpcServiceName() == null || step.processorInfo().grpcServiceName().isBlank())) {
                        errors.add("Step " + entry.getKey() + " has no processor info");
                    }
                }
            }
            
            return errors.isEmpty() ? 
                    ValidationResult.createValid() : 
                    ValidationResult.createInvalid(errors);
        });
    }
    
    @Override
    public Mono<List<PipelineStepConfig>> getOrderedSteps(String pipelineId) {
        return getPipelineConfig(pipelineId)
                .map(config -> {
                    if (config.pipelineSteps() == null) {
                        return List.of();
                    }
                    
                    return config.pipelineSteps().values().stream()
                            .sorted(Comparator.comparing(PipelineStepConfig::stepName))
                            .collect(Collectors.toList());
                });
    }
    
    // Test helper methods
    public void clear() {
        pipelines.clear();
        watchers.values().forEach(sink -> sink.tryEmitComplete());
        watchers.clear();
    }
    
    public int getPipelineCount() {
        return pipelines.size();
    }
}