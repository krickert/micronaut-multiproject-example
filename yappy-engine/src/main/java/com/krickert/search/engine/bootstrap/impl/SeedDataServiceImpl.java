package com.krickert.search.engine.bootstrap.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.bootstrap.SeedDataService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

/**
 * Implementation of SeedDataService that loads templates from resources and stores them via ConsulKvService.
 */
@Singleton
@Requires(property = "yappy.engine.seeding.enabled", value = "true", defaultValue = "false")
public class SeedDataServiceImpl implements SeedDataService {
    
    private static final Logger LOG = LoggerFactory.getLogger(SeedDataServiceImpl.class);
    
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final ResourceResolver resourceResolver;
    
    private static final String TEMPLATES_PATH = "classpath:seed-templates/";
    private static final Map<String, String> TEMPLATE_FILES = Map.of(
            "minimal", "minimal-pipeline.json",
            "standard", "standard-pipeline.json",
            "complex", "complex-pipeline.json",
            "test", "test-pipeline.json"
    );
    
    @Inject
    public SeedDataServiceImpl(ConsulKvService consulKvService,
                               ObjectMapper objectMapper,
                               ResourceResolver resourceResolver) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
        this.resourceResolver = resourceResolver;
    }
    
    @Override
    public Mono<PipelineClusterConfig> loadTemplate(String templateName) {
        return Mono.fromCallable(() -> {
            String fileName = TEMPLATE_FILES.get(templateName);
            if (fileName == null) {
                throw new IllegalArgumentException("Unknown template: " + templateName);
            }
            
            String resourcePath = TEMPLATES_PATH + fileName;
            Optional<URL> resourceUrl = resourceResolver.getResource(resourcePath);
            
            if (resourceUrl.isEmpty()) {
                // If template doesn't exist, create a default one
                LOG.warn("Template {} not found at {}, creating default configuration", templateName, resourcePath);
                return createDefaultTemplate(templateName);
            }
            
            try (InputStream is = resourceUrl.get().openStream()) {
                return objectMapper.readValue(is, PipelineClusterConfig.class);
            } catch (IOException e) {
                LOG.error("Failed to load template {}: {}", templateName, e.getMessage(), e);
                throw new RuntimeException("Failed to load template: " + templateName, e);
            }
        });
    }
    
    @Override
    public Mono<Void> seedCluster(String clusterName, String templateName) {
        return loadTemplate(templateName)
                .map(config -> new PipelineClusterConfig(
                        clusterName, // Override cluster name
                        config.pipelineGraphConfig(),
                        config.pipelineModuleMap(),
                        config.defaultPipelineName(),
                        config.allowedKafkaTopics(),
                        config.allowedGrpcServices()
                ))
                .flatMap(this::seedCluster);
    }
    
    @Override
    public Mono<Void> seedCluster(PipelineClusterConfig config) {
        LOG.info("Seeding cluster {} with configuration", config.clusterName());
        String configKey = "pipeline-configs/clusters/" + config.clusterName();
        
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(config))
                .flatMap(configJson -> consulKvService.putValue(configKey, configJson))
                .doOnSuccess(success -> {
                    if (success) {
                        LOG.info("Cluster {} seeded successfully", config.clusterName());
                    } else {
                        LOG.error("Failed to seed cluster {}", config.clusterName());
                    }
                })
                .then();
    }
    
    @Override
    public String[] getAvailableTemplates() {
        return TEMPLATE_FILES.keySet().toArray(new String[0]);
    }
    
    @Override
    public Mono<PipelineClusterConfig> createTestPipelineConfig(String clusterName) {
        return Mono.fromCallable(() -> {
            LOG.info("Creating test pipeline configuration for cluster: {}", clusterName);
            
            // Create module configurations
            Map<String, PipelineModuleConfiguration> availableModules = new HashMap<>();
            availableModules.put("tika-parser", new PipelineModuleConfiguration(
                    "Tika Parser",
                    "tika-parser",
                    new SchemaReference("tika-parser-config", 1),
                    null // No custom config for now
            ));
            availableModules.put("chunker", new PipelineModuleConfiguration(
                    "Text Chunker", 
                    "chunker",
                    new SchemaReference("chunker-config", 1),
                    null // No custom config for now
            ));
            
            // Create test pipeline steps
            Map<String, PipelineStepConfig> steps = new HashMap<>();
            steps.put("extract-text", new PipelineStepConfig(
                    "extract-text",
                    StepType.INITIAL_PIPELINE,
                    new PipelineStepConfig.ProcessorInfo("tika-parser", null),
                    new PipelineStepConfig.JsonConfigOptions(Map.of("maxContentLength", "10000000")) // 10MB max
            ));
            steps.put("chunk-text", new PipelineStepConfig(
                    "chunk-text",
                    StepType.PIPELINE,
                    new PipelineStepConfig.ProcessorInfo("chunker", null),
                    new PipelineStepConfig.JsonConfigOptions(Map.of("chunkSize", "1000", "chunkOverlap", "200"))
            ));
            
            // Create pipeline config
            Map<String, PipelineConfig> pipelines = new HashMap<>();
            pipelines.put("test-pipeline", new PipelineConfig(
                    "test-pipeline",
                    steps
            ));
            
            PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
            PipelineModuleMap moduleMap = new PipelineModuleMap(availableModules);
            
            return new PipelineClusterConfig(
                    clusterName, 
                    graphConfig, 
                    moduleMap,
                    "test-pipeline", // default pipeline
                    Collections.emptySet(), // No allowed Kafka topics yet
                    Collections.emptySet()  // No allowed gRPC services yet
            );
        });
    }
    
    private PipelineClusterConfig createDefaultTemplate(String templateName) {
        switch (templateName) {
            case "minimal":
                return createMinimalConfig("template-cluster");
            case "test":
                return createTestPipelineConfig("template-cluster").block();
            default:
                return createMinimalConfig("template-cluster");
        }
    }
    
    private PipelineClusterConfig createMinimalConfig(String clusterName) {
        return new PipelineClusterConfig(
                clusterName,
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                null, // No default pipeline
                Collections.emptySet(), // No allowed Kafka topics
                Collections.emptySet()  // No allowed gRPC services
        );
    }
}