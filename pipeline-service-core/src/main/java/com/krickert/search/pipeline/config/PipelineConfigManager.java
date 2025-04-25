package com.krickert.search.pipeline.config;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.discovery.config.ConfigurationClient;
import io.micronaut.discovery.consul.client.v1.ConsulClient;
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

@Setter
@Getter
@Singleton
@Slf4j
public class PipelineConfigManager {

    /**
     * Map of pipeline configurations, keyed by pipeline name.
     */
    private Map<String, PipelineConfig> pipelines = new HashMap<>();

    @SuppressWarnings("MnInjectionPoints")
    @Inject
    private Environment environment;

    @Inject
    private ConfigurationClient configurationClient;

    @Inject
    private ConsulClient consulClient;

    @Value("${consul.client.enabled:false}")
    private boolean consulEnabled;

    @Value("${consul.client.config.path:config/pipeline}")
    private String consulConfigPath;

    @PostConstruct
    public void init() {
        // Clear any existing pipelines
        pipelines.clear();

        // First try to load from Consul if available
        if (consulEnabled && loadPropertiesFromConsul()) {
            log.info("Successfully loaded configuration from Consul");
        } else {
            // Fall back to file-based configuration
            log.info("Consul configuration not available, falling back to file-based configuration");
            loadPropertiesFromFile("pipeline.properties");
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

            // Clear existing pipelines before parsing new properties
            pipelines.clear();

            // Parse properties into pipelines map
            parsePipelineProperties(properties);
            return true;

        } catch (Exception e) {
            log.error("Error loading configuration from environment", e);
            return false;
        }
    }

    /**
     * Updates a service configuration in Consul.
     * 
     * @param pipelineName The name of the pipeline
     * @param serviceConfig The service configuration to update
     * @return true if the configuration was updated successfully, false otherwise
     */
    public boolean updateServiceConfigInConsul(String pipelineName, ServiceConfiguration serviceConfig) {
        try {
            log.info("Updating service configuration in Consul for pipeline: {}, service: {}", 
                    pipelineName, serviceConfig.getName());

            // Check if Consul is enabled
            if (!consulEnabled) {
                log.warn("Consul is not enabled in the environment");
                return false;
            }

            // Build the property keys and values
            String serviceName = serviceConfig.getName();
            String baseKey = String.format("pipeline.configs.%s.service.%s", pipelineName, serviceName);

            // Update kafka-listen-topics
            updateListPropertyInConsul(baseKey + ".kafka-listen-topics", serviceConfig.getKafkaListenTopics());

            // Update kafka-publish-topics
            updateListPropertyInConsul(baseKey + ".kafka-publish-topics", serviceConfig.getKafkaPublishTopics());

            // Update grpc-forward-to
            updateListPropertyInConsul(baseKey + ".grpc-forward-to", serviceConfig.getGrpcForwardTo());

            log.info("Successfully updated service configuration in Consul");
            return true;

        } catch (Exception e) {
            log.error("Error updating service configuration in Consul", e);
            return false;
        }
    }

    /**
     * Updates a list property in Consul.
     * If the list has only one element, it's stored as a single property.
     * If the list has multiple elements, they're stored as indexed properties.
     * 
     * @param baseKey The base key for the property
     * @param values The list of values
     */
    private void updateListPropertyInConsul(String baseKey, List<String> values) {
        if (values == null || values.isEmpty()) {
            log.debug("No values to update for key: {}", baseKey);
            return;
        }

        try {
            if (values.size() == 1) {
                // Store as a single property
                String consulKey = consulConfigPath + "/" + baseKey;
                String value = values.getFirst();
                log.debug("Updating property in Consul: {} = {}", consulKey, value);

                // Use the Environment to update the property
                environment.addPropertySource(PropertySource.of(consulKey, Map.of(baseKey, value)));

            } else {
                // Store as indexed properties
                for (int i = 0; i < values.size(); i++) {
                    String indexedKey = baseKey + "[" + i + "]";
                    String consulKey = consulConfigPath + "/" + indexedKey;
                    String value = values.get(i);
                    log.debug("Updating indexed property in Consul: {} = {}", consulKey, value);

                    // Use the Environment to update the property
                    environment.addPropertySource(PropertySource.of(consulKey, Map.of(indexedKey, value)));
                }
            }
        } catch (Exception e) {
            log.error("Error updating list property in Consul: {}", baseKey, e);
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

            // Clear existing pipelines before parsing new properties
            pipelines.clear();

            // Parse properties into pipelines map
            parsePipelineProperties(properties);
            return true;

        } catch (IOException ex) {
            log.error("Error loading {} file", filename, ex);
            return false;
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
        serviceNames.forEach((pipeline, services) -> log.info("Pipeline {} has services: {}", pipeline, services));

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

                // Initialize with empty lists to avoid NullPointerException
                serviceConfig.setKafkaListenTopics(new ArrayList<>());
                serviceConfig.setKafkaPublishTopics(new ArrayList<>());
                serviceConfig.setGrpcForwardTo(new ArrayList<>());

                // Check for direct property without array index
                if (properties.containsKey(listenTopicsKeyBase)) {
                    String value = properties.getProperty(listenTopicsKeyBase);
                    serviceConfig.setKafkaListenTopics(new ArrayList<>(Arrays.asList(value.split(","))));
                } else {
                    // Check for properties with array indices
                    List<String> listenTopics = new ArrayList<>();
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(listenTopicsKeyBase) + "\\[(\\d+)]");

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
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(publishTopicsKeyBase) + "\\[(\\d+)]");

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
                String grpcForwardToKey = String.format("pipeline.configs.%s.service.%s.grpc-forward-to",
                        pipelineName, serviceName);
                if (properties.containsKey(grpcForwardToKey)) {
                    String value = properties.getProperty(grpcForwardToKey);
                    if (!"null".equals(value)) {
                        serviceConfig.setGrpcForwardTo(Arrays.asList(value.split(",")));
                    }
                } else {
                    // Check for properties with array indices
                    List<String> grpcForwardTo = new ArrayList<>();
                    Pattern arrayPattern = Pattern.compile(Pattern.quote(grpcForwardToKey) + "\\[(\\d+)]");

                    properties.forEach((key, value) -> {
                        String keyStr = key.toString();
                        Matcher matcher = arrayPattern.matcher(keyStr);
                        if (matcher.matches()) {
                            grpcForwardTo.add(value.toString());
                        }
                    });

                    if (!grpcForwardTo.isEmpty()) {
                        serviceConfig.setGrpcForwardTo(grpcForwardTo);
                    }
                }

                // Add the service configuration to the map
                serviceConfigs.put(serviceName, serviceConfig);
            });

            // Set the service configurations for this pipeline
            pipelineConfig.setService(serviceConfigs);

            // Add the pipeline configuration to the map
            pipelines.put(pipelineName, pipelineConfig);
        });
    }
}
