package com.krickert.search.engine.core;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Interface for accessing pipeline configuration.
 * 
 * This abstracts the configuration source (Consul) and provides
 * reactive access to configuration with support for dynamic updates.
 * 
 * The implementation will use DynamicConfigurationManager from
 * yappy-consul-config.
 */
public interface PipelineConfigurationProvider {
    
    /**
     * Get the current cluster configuration.
     * 
     * @return Mono with the cluster config
     */
    Mono<PipelineClusterConfig> getClusterConfig();
    
    /**
     * Get configuration for a specific pipeline.
     * 
     * @param pipelineName The pipeline name
     * @return Mono with the pipeline config
     */
    Mono<PipelineConfig> getPipelineConfig(String pipelineName);
    
    /**
     * Get configuration for a specific step.
     * 
     * @param pipelineName The pipeline name
     * @param stepName The step name
     * @return Mono with the step config
     */
    Mono<PipelineStepConfig> getStepConfig(String pipelineName, String stepName);
    
    /**
     * Check if a Kafka topic is allowed.
     * 
     * This checks both:
     * 1. Explicit whitelist in allowedKafkaTopics
     * 2. Convention-based naming patterns
     * 
     * @param topic The Kafka topic name
     * @param pipelineName The current pipeline context
     * @param stepName The current step context
     * @return true if the topic is allowed
     */
    Mono<Boolean> isKafkaTopicAllowed(String topic, String pipelineName, String stepName);
    
    /**
     * Get the gRPC service name for a module.
     * 
     * @param moduleName The logical module name
     * @return The gRPC service name to use for discovery
     */
    Mono<Optional<String>> getGrpcServiceName(String moduleName);
    
    /**
     * Watch for configuration changes.
     * 
     * @return Flux of configuration change events
     */
    Flux<ConfigurationChangeEvent> watchConfigurationChanges();
    
    /**
     * Get connector mapping for a source identifier.
     * 
     * @param sourceIdentifier The source ID from connector request
     * @return The connector mapping if found
     */
    Mono<Optional<ConnectorMapping>> getConnectorMapping(String sourceIdentifier);
}