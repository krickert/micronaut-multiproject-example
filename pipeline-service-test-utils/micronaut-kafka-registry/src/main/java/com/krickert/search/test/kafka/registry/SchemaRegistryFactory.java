package com.krickert.search.test.kafka.registry;

import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Factory for creating SchemaRegistry instances based on configuration.
 * This factory uses the ServiceLoader mechanism to find SchemaRegistry implementations
 * and selects the appropriate one based on the configuration or environment variable.
 */
@Factory
public class SchemaRegistryFactory {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryFactory.class);

    // Environment variable and system property to control which schema registry to use
    private static final String SCHEMA_REGISTRY_TYPE_ENV = "SCHEMA_REGISTRY_TYPE";
    private static final String SCHEMA_REGISTRY_TYPE_PROP = "schema.registry.type";
    private static final String DEFAULT_REGISTRY_TYPE = "apicurio"; // Default to apicurio

    /**
     * Creates a SchemaRegistry instance based on the configuration, system property, or environment variable.
     * 
     * @param registryType the type of registry to create from property, can be overridden by system property or environment variable
     * @return a SchemaRegistry instance
     */
    @Singleton
    @Requires(property = "schema.registry.enabled", notEquals = "false")
    public SchemaRegistry schemaRegistry(
            @Property(name = SCHEMA_REGISTRY_TYPE_PROP, defaultValue = DEFAULT_REGISTRY_TYPE) String registryType) {

        // Check if system property is set and override property if it is
        String sysPropRegistryType = System.getProperty(SCHEMA_REGISTRY_TYPE_PROP);
        if (sysPropRegistryType != null && !sysPropRegistryType.trim().isEmpty()) {
            registryType = sysPropRegistryType;
        }

        // Check if environment variable is set and override property if it is
        String envRegistryType = System.getenv(SCHEMA_REGISTRY_TYPE_ENV);
        if (envRegistryType != null && !envRegistryType.trim().isEmpty()) {
            registryType = envRegistryType;
        }

        log.info("Creating SchemaRegistry of type: {}", registryType);

        // Use ServiceLoader to find all SchemaRegistry implementations
        ServiceLoader<SchemaRegistry> registries = ServiceLoader.load(SchemaRegistry.class);

        // Find the registry with the matching type
        for (SchemaRegistry registry : registries) {
            String className = registry.getClass().getSimpleName().toLowerCase();
            if (className.contains(registryType.toLowerCase())) {
                log.info("Found SchemaRegistry implementation: {}", registry.getClass().getName());
                return registry;
            }
        }

        // If no matching registry is found, log a warning and return the first one
        log.warn("No SchemaRegistry implementation found for type: {}. Using the first available.", registryType);
        SchemaRegistry firstRegistry = registries.iterator().next();
        if (firstRegistry != null) {
            log.info("Using SchemaRegistry implementation: {}", firstRegistry.getClass().getName());
            return firstRegistry;
        }

        // If no registry is found at all, throw an exception
        throw new IllegalStateException("No SchemaRegistry implementation found. Make sure you have at least one implementation in the classpath.");
    }
}
