package com.krickert.search.config.consul.schema.test;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.schema.model.test.ConsulSchemaRegistryTestHelper;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * This class is responsible for seeding the schema registry with test schemas.
 * It uses the ConsulSchemaRegistryTestHelper to load schemas from resources
 * and the ConsulSchemaRegistryDelegate to register them with Consul.
 */
@Singleton
@Requires(env = "test")
public class ConsulSchemaRegistrySeeder {
    private static final Logger log = LoggerFactory.getLogger(ConsulSchemaRegistrySeeder.class);
    private final ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @Inject
    public ConsulSchemaRegistrySeeder(ConsulSchemaRegistryDelegate schemaRegistryDelegate) {
        this.schemaRegistryDelegate = schemaRegistryDelegate;
    }

    /**
     * Seeds the schema registry with all JSON schemas found in the test resources.
     * This method uses the ConsulSchemaRegistryTestHelper to load schemas from resources
     * and the ConsulSchemaRegistryDelegate to register them with Consul.
     *
     * @return A Mono that completes when all schemas have been registered
     */
    public Mono<Void> seedSchemas() {
        log.info("Seeding schema registry with test schemas");

        // Load all schemas from resources
        Map<String, String> schemas = ConsulSchemaRegistryTestHelper.loadAllSchemas();

        if (schemas.isEmpty()) {
            log.warn("No schema files found in resources");
            return Mono.empty();
        }

        log.info("Found {} schema files to register", schemas.size());

        // Register each schema
        return Flux.fromIterable(schemas.entrySet())
                .flatMap(entry -> {
                    String schemaId = entry.getKey();
                    String schemaContent = entry.getValue();

                    log.info("Registering schema: {} with content length: {}", schemaId, schemaContent.length());

                    return schemaRegistryDelegate.saveSchema(schemaId, schemaContent)
                            .onErrorResume(e -> {
                                log.error("Failed to register schema: {}: {}", schemaId, e.getMessage(), e);
                                return Mono.empty();
                            });
                })
                .then();
    }

    /**
     * Registers a single schema with the schema registry.
     *
     * @param schemaId The ID to register the schema under
     * @param resourceName The name of the resource file containing the schema
     * @return A Mono that completes when the schema has been registered
     */
    public Mono<Void> registerSchema(String schemaId, String resourceName) {
        log.info("Registering schema: {} from resource: {}", schemaId, resourceName);

        String schemaContent = ConsulSchemaRegistryTestHelper.loadSchemaContent(resourceName);

        return schemaRegistryDelegate.saveSchema(schemaId, schemaContent)
                .onErrorResume(e -> {
                    log.error("Failed to register schema: {} from resource: {}: {}", schemaId, resourceName, e.getMessage(), e);
                    return Mono.error(e);
                });
    }

    /**
     * Registers a single schema with the schema registry using the provided schema content.
     *
     * @param schemaId The ID to register the schema under
     * @param schemaContent The schema content as a string
     * @return A Mono that completes when the schema has been registered
     */
    public Mono<Void> registerSchemaContent(String schemaId, String schemaContent) {
        log.info("Registering schema: {} with content directly", schemaId);

        return schemaRegistryDelegate.saveSchema(schemaId, schemaContent)
                .onErrorResume(e -> {
                    log.error("Failed to register schema: {} with content: {}", schemaId, e.getMessage(), e);
                    return Mono.error(e);
                });
    }
}
