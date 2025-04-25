package com.krickert.search.pipeline.config;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.discovery.config.ConfigurationClient;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of the pipeline configuration manager.
 * This class is responsible for loading pipeline configurations from various sources
 * (Consul KV store or properties files) and providing access to them.
 */
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

    @Inject
    private Environment environment;

    @Inject
    private ConfigurationClient configurationClient;

    @Value("${consul.client.enabled:false}")
    private boolean consulEnabled;

    @Value("${consul.client.config.path:config/pipeline}")
    private String consulConfigPath;

    /**
     * Default constructor.
     * Creates a new instance of DefaultPipelineConfig with an empty pipelines map.
     */
    public DefaultPipelineConfig() {
        // Initialize with empty map
        this.pipelines = new HashMap<>();
    }

    /**
     * Initializes the pipeline configuration.
     * This method is called automatically after the bean is constructed.
     * It attempts to load configuration from Consul if enabled, otherwise falls back to file-based configuration.
     */
    @PostConstruct
    public void init() {
        // First try to load from Consul if available
        if (consulEnabled && loadPropertiesFromConsul()) {
            log.info("Successfully loaded configuration from Consul");
        } else {
            // Fall back to file-based configuration
            log.info("Consul configuration not available, falling back to file-based configuration");
            loadPropertiesFromFile("pipeline.default.properties");
        }
    }

    /**
     * Loads properties from Consul KV store and parses them into the pipelines map.
     * 
     * @return true if properties were loaded successfully, false otherwise
     */
    public boolean loadPropertiesFromConsul() {
        try {
            log.info("Attempting to load configuration from Consul");

            // Get all properties with the pipeline.configs prefix
            Properties properties = new Properties();

            // Check if Consul is enabled in the environment
            if (!consulEnabled) {
                log.warn("Consul is not enabled in the environment");
                return false;
            }

            // Get all application properties and filter for pipeline.configs
            Map<String, Object> allProperties = environment.getProperties("pipeline.configs");
            if (allProperties.isEmpty()) {
                log.warn("No pipeline configuration found in environment");
                return false;
            }

            // Convert to Properties object
            for (Map.Entry<String, Object> entry : allProperties.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value != null) {
                    properties.setProperty(key, value.toString());
                    log.debug("Loaded property: {} = {}", key, value);
                }
            }

            log.info("Loaded {} properties from environment", properties.size());

            // Parse properties into pipelines map
            parsePipelineProperties(properties);
            return true;

        } catch (Exception e) {
            log.error("Error loading configuration from environment", e);
            return false;
        }
    }

    /**
     * Loads properties from a specified file and parses them into the pipelines map.
     * 
     * @param filename The name of the properties file to load
     * @return true if the file was loaded successfully, false otherwise
     */
    public boolean loadPropertiesFromFile(String filename) {
        Properties properties = new Properties();

        // Load properties from the specified file
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                log.warn("Unable to find {} file", filename);
                return false;
            }

            properties.load(input);
            log.info("Loaded {} file", filename);

            // Parse properties into pipelines map
            parsePipelineProperties(properties);
            return true;

        } catch (IOException ex) {
            log.error("Error loading {} file", filename, ex);
            return false;
        }
    }

    /**
     * Parses the properties into pipeline configurations.
     * This method extracts pipeline and service configurations from the properties
     * and populates the pipelines map.
     *
     * @param properties The properties to parse
     */
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
                String listenTopicsKeyBase = String.format("pipeline.configs.%s.service.%s.kafka-listen-topics",
                        pipelineName, serviceName);

                // Initialize with empty list to avoid NullPointerException
                serviceConfig.setKafkaListenTopics(new ArrayList<>());

                // Check for direct property without array index
                if (properties.containsKey(listenTopicsKeyBase)) {
                    String value = properties.getProperty(listenTopicsKeyBase);
                    serviceConfig.setKafkaListenTopics(new ArrayList<>(Arrays.asList(value.split(","))));
                } else {
                    // Check for properties with array indices
                    List<String> listenTopics = new ArrayList<>();
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(listenTopicsKeyBase) + "\\[(\\d+)\\]");

                    properties.forEach((key, value) -> {
                        String keyStr = key.toString();
                        Matcher matcher = arrayPattern.matcher(keyStr);
                        if (matcher.matches()) {
                            listenTopics.add(value.toString());
                        }
                    });

                    if (!listenTopics.isEmpty()) {
                        serviceConfig.setKafkaListenTopics(listenTopics);
                    }
                }

                // Set kafka-publish-topics if present
                String publishTopicsKeyBase = String.format("pipeline.configs.%s.service.%s.kafka-publish-topics",
                        pipelineName, serviceName);

                // Initialize with empty list to avoid NullPointerException
                serviceConfig.setKafkaPublishTopics(new ArrayList<>());

                // Check for direct property without array index
                if (properties.containsKey(publishTopicsKeyBase)) {
                    String value = properties.getProperty(publishTopicsKeyBase);
                    serviceConfig.setKafkaPublishTopics(new ArrayList<>(Arrays.asList(value.split(","))));
                } else {
                    // Check for properties with array indices
                    List<String> publishTopics = new ArrayList<>();
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(publishTopicsKeyBase) + "\\[(\\d+)\\]");

                    properties.forEach((key, value) -> {
                        String keyStr = key.toString();
                        Matcher matcher = arrayPattern.matcher(keyStr);
                        if (matcher.matches()) {
                            publishTopics.add(value.toString());
                        }
                    });

                    if (!publishTopics.isEmpty()) {
                        serviceConfig.setKafkaPublishTopics(publishTopics);
                    }
                }

                // Set grpc-forward-to if present
                String grpcForwardKeyBase = String.format("pipeline.configs.%s.service.%s.grpc-forward-to",
                        pipelineName, serviceName);

                // Initialize with empty list to avoid NullPointerException
                serviceConfig.setGrpcForwardTo(new ArrayList<>());

                // Check for direct property without array index
                if (properties.containsKey(grpcForwardKeyBase)) {
                    String value = properties.getProperty(grpcForwardKeyBase);
                    if (!"null".equals(value)) {
                        serviceConfig.setGrpcForwardTo(new ArrayList<>(Arrays.asList(value.split(","))));
                    }
                } else {
                    // Check for properties with array indices
                    List<String> forwardTo = new ArrayList<>();
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(grpcForwardKeyBase) + "\\[(\\d+)\\]");

                    properties.forEach((key, value) -> {
                        String keyStr = key.toString();
                        Matcher matcher = arrayPattern.matcher(keyStr);
                        if (matcher.matches() && !"null".equals(value.toString())) {
                            forwardTo.add(value.toString());
                        }
                    });

                    if (!forwardTo.isEmpty()) {
                        serviceConfig.setGrpcForwardTo(forwardTo);
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
