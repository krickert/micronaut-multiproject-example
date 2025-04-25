package com.krickert.search.pipeline.config;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Setter
@Getter
@Singleton
@Slf4j
public class DefaultPipelineConfig {

    /**
     * Map of pipeline configurations, keyed by pipeline name.
     * -- SETTER --
     *  Sets the pipelines map. Used primarily for testing.
     *
     * @param pipelines The map of pipeline configurations

     */
    private Map<String, PipelineConfig> pipelines = new HashMap<>();

    @PostConstruct
    public void init() {
        Properties properties = new Properties();

        // Load properties from pipeline.default.properties file
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("pipeline.default.properties")) {
            if (input == null) {
                log.warn("Unable to find pipeline.default.properties file");
                return;
            }

            properties.load(input);
            log.info("Loaded pipeline.default.properties file");

            // Parse properties into pipelines map
            parsePipelineProperties(properties);

        } catch (IOException ex) {
            log.error("Error loading pipeline.default.properties file", ex);
        }
    }

    private void parsePipelineProperties(Properties properties) {
        // Store all unique pipeline and service names to ensure we capture everything
        Set<String> pipelineNames = new HashSet<>();
        Map<String, Set<String>> serviceNames = new HashMap<>();

        // First pass: Identify all pipeline and service names
        Pattern identifierPattern = Pattern.compile("pipeline\\.configs\\.(\\w+)\\.service\\.([-\\w]+)\\.");
        properties.forEach((key, value) -> {
            String keyStr = key.toString();
            Matcher matcher = identifierPattern.matcher(keyStr);
            if (matcher.find()) {
                String pipelineName = matcher.group(1);
                String serviceName = matcher.group(2);

                pipelineNames.add(pipelineName);
                serviceNames.computeIfAbsent(pipelineName, k -> new HashSet<>()).add(serviceName);
            }
        });

        log.info("Found pipeline names: {}", pipelineNames);
        serviceNames.forEach((pipeline, services) -> {
            log.info("Pipeline {} has services: {}", pipeline, services);
        });

        // Create pipeline configurations
        pipelineNames.forEach(pipelineName -> {
            PipelineConfig pipelineConfig = new PipelineConfig(pipelineName);
            Map<String, ServiceConfiguration> serviceConfigs = new HashMap<>();

            // Create service configurations for this pipeline
            Set<String> services = serviceNames.getOrDefault(pipelineName, Collections.emptySet());
            services.forEach(serviceName -> {
                ServiceConfiguration serviceConfig = new ServiceConfiguration(serviceName);

                // Set kafka-listen-topics if present
                String listenTopicsKey = String.format("pipeline.configs.%s.service.%s.kafka-listen-topics",
                        pipelineName, serviceName);
                if (properties.containsKey(listenTopicsKey)) {
                    String value = properties.getProperty(listenTopicsKey);
                    serviceConfig.setKafkaListenTopics(Arrays.asList(value.split(",")));
                }

                // Set kafka-publish-topics if present
                String publishTopicsKey = String.format("pipeline.configs.%s.service.%s.kafka-publish-topics",
                        pipelineName, serviceName);
                if (properties.containsKey(publishTopicsKey)) {
                    String value = properties.getProperty(publishTopicsKey);
                    serviceConfig.setKafkaPublishTopics(Arrays.asList(value.split(",")));
                }

                // Set grpc-forward-to if present
                String grpcForwardKey = String.format("pipeline.configs.%s.service.%s.grpc-forward-to",
                        pipelineName, serviceName);
                if (properties.containsKey(grpcForwardKey)) {
                    String value = properties.getProperty(grpcForwardKey);
                    if (!"null".equals(value)) {
                        serviceConfig.setGrpcForwardTo(Arrays.asList(value.split(",")));
                    }
                }

                // Add the service configuration to the map
                serviceConfigs.put(serviceName, serviceConfig);
            });

            // Set services and add pipeline to the map
            pipelineConfig.setService(serviceConfigs);
            pipelines.put(pipelineName, pipelineConfig);
        });

        log.info("Loaded {} pipelines from properties file", pipelines.size());

        // Debug logging to verify all services are included
        pipelines.forEach((pipelineName, pipelineConfig) -> {
            log.info("Pipeline: {} has {} services", pipelineName, pipelineConfig.getService().size());
            pipelineConfig.getService().forEach((serviceName, serviceConfig) -> {
                log.info("  Service: {}, Listen Topics: {}, Publish Topics: {}, Forward To: {}",
                        serviceName,
                        serviceConfig.getKafkaListenTopics(),
                        serviceConfig.getKafkaPublishTopics(),
                        serviceConfig.getGrpcForwardTo());
            });
        });
    }

}