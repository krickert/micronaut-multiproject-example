    package com.krickert.search.config.consul.model;

    import com.krickert.search.config.consul.validation.PipelineValidator;
    import io.micronaut.serde.annotation.Serdeable;
    import lombok.Getter;
    import lombok.Setter;

    import java.time.LocalDateTime;
    import java.util.*;
    import java.util.HashMap;

    /**
     * Data Transfer Object for pipeline configuration.
     * This class represents the configuration for an entire pipeline, containing multiple service configurations.
     */
    @Getter
    @Setter
    @Serdeable
    public class PipelineConfigDto {
        /**
         * The name of the pipeline.
         */
        private String name;

        /**
         * Map of service configurations, keyed by service name.
         */
        private Map<String, PipeStepConfigurationDto> services = new HashMap<>();

        /**
         * The version of the pipeline configuration.
         * This is incremented each time the pipeline is updated.
         */
        private long pipelineVersion = 0;

        /**
         * The timestamp when the pipeline was last updated.
         */
        private LocalDateTime pipelineLastUpdated = LocalDateTime.now();

        /**
         * Default constructor.
         */
        public PipelineConfigDto() {
        }

        /**
         * Constructor with pipeline name.
         *
         * @param name the name of the pipeline
         */
        public PipelineConfigDto(String name) {
            this.name = name;
            this.pipelineVersion = 0;
            this.pipelineLastUpdated = LocalDateTime.now();
        }

        /**
         * Copy constructor.
         * Creates a deep copy of the provided pipeline configuration.
         *
         * @param other the pipeline configuration to copy
         */
        public PipelineConfigDto(PipelineConfigDto other) {
            this.name = other.name;
            this.pipelineVersion = other.pipelineVersion;
            this.pipelineLastUpdated = other.pipelineLastUpdated;

            // Deep copy the services map
            this.services = new HashMap<>();
            for (Map.Entry<String, PipeStepConfigurationDto> entry : other.services.entrySet()) {
                PipeStepConfigurationDto serviceCopy = new PipeStepConfigurationDto();
                serviceCopy.setName(entry.getValue().getName());
                serviceCopy.setKafkaListenTopics(entry.getValue().getKafkaListenTopics() != null ?
                    new ArrayList<>(entry.getValue().getKafkaListenTopics()) : null);
                serviceCopy.setKafkaPublishTopics(entry.getValue().getKafkaPublishTopics() != null ?
                    new ArrayList<>(entry.getValue().getKafkaPublishTopics()) : null);
                serviceCopy.setGrpcForwardTo(entry.getValue().getGrpcForwardTo() != null ?
                    new ArrayList<>(entry.getValue().getGrpcForwardTo()) : null);
                serviceCopy.setServiceImplementation(entry.getValue().getServiceImplementation());

                // Copy config params if present
                if (entry.getValue().getConfigParams() != null) {
                    Map<String, String> configParamsCopy = new HashMap<>();
                    configParamsCopy.putAll(entry.getValue().getConfigParams());
                    serviceCopy.setConfigParams(configParamsCopy);
                }

                // Copy JSON config if present
                if (entry.getValue().getJsonConfig() != null) {
                    JsonConfigOptions jsonConfigCopy = new JsonConfigOptions(
                        entry.getValue().getJsonConfig().getJsonConfig(),
                        entry.getValue().getJsonConfig().getJsonSchema()
                    );
                    serviceCopy.setJsonConfig(jsonConfigCopy);
                }

                this.services.put(entry.getKey(), serviceCopy);
            }
        }

        /**
         * Increments the pipeline version and updates the last updated timestamp.
         * This should be called whenever the pipeline is updated.
         */
        public void incrementVersion() {
            this.pipelineVersion++;
            this.pipelineLastUpdated = LocalDateTime.now();
        }

        /**
         * Checks if the pipeline contains a service with the given name.
         *
         * @param serviceName the name of the service to check
         * @return true if the pipeline contains the service, false otherwise
         */
        public boolean containsService(String serviceName) {
            return services.containsKey(serviceName);
        }

        /**
         * Adds or updates a service configuration.
         *
         * @param serviceConfig the service configuration to add or update
         * @throws IllegalArgumentException if adding or updating the service would create a loop in the pipeline
         */
        public void addOrUpdateService(PipeStepConfigurationDto serviceConfig) {
            // Validate that no topic ends with "-dlq"
            if (serviceConfig.getKafkaListenTopics() != null) {
                for (String topic : serviceConfig.getKafkaListenTopics()) {
                    if (topic.endsWith("-dlq")) {
                        throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                    }
                }
            }

            if (serviceConfig.getKafkaPublishTopics() != null) {
                for (String topic : serviceConfig.getKafkaPublishTopics()) {
                    if (topic.endsWith("-dlq")) {
                        throw new IllegalArgumentException("Topic names cannot end with '-dlq' as this suffix is reserved for Dead Letter Queues: " + topic);
                    }
                }
            }

            // Check if adding or updating the service would create a loop
            if (PipelineValidator.hasLoop(this, serviceConfig)) {
                throw new IllegalArgumentException("Adding or updating service '" + serviceConfig.getName() +
                    "' would create a loop in the pipeline");
            }

            services.put(serviceConfig.getName(), serviceConfig);
        }

        /**
         * Removes a service configuration.
         *
         * @param serviceName the name of the service to remove
         * @return the removed service configuration, or null if not found
         */
        public PipeStepConfigurationDto removeService(String serviceName) {
            return services.remove(serviceName);
        }

        /**
         * Removes a service configuration and all services that depend on it.
         * A service depends on another service if it listens to a topic that the other service publishes to,
         * or if it is forwarded to by the other service via gRPC.
         *
         * @param serviceName the name of the service to remove
         * @return a set of service names that were removed, including the specified service
         */
        public Set<String> removeServiceWithDependents(String serviceName) {
            Set<String> removedServices = new HashSet<>();

            // Check if the service exists
            if (!services.containsKey(serviceName)) {
                return removedServices;
            }

            // Get all services that depend on this service
            Set<String> dependentServices = PipelineValidator.getDependentServices(this, serviceName);

            // Recursively remove dependent services
            for (String dependentService : dependentServices) {
                removedServices.addAll(removeServiceWithDependents(dependentService));
            }

            // Remove the service itself
            services.remove(serviceName);
            removedServices.add(serviceName);

            return removedServices;
        }
    }
