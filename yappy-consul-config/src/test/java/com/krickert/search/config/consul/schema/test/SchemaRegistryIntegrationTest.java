package com.krickert.search.config.consul.schema.test;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.pipeline.model.SchemaReference;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the schema registry.
 * This test demonstrates how to use the SchemaRegistrySeeder to register schemas in Consul
 * before running tests, enabling true end-to-end testing.
 */
@MicronautTest
@Property(name = "consul.client.config.path", value = "config/test-pipeline")
public class SchemaRegistryIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryIntegrationTest.class);

    @Inject
    private SchemaRegistrySeeder schemaRegistrySeeder;

    @Inject
    private ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @BeforeEach
    void setUp() {
        // Seed the schema registry with test schemas
        schemaRegistrySeeder.seedSchemas().block();
    }

    @Test
    void testSchemasAreRegisteredAndCanBeRetrieved() {
        // List all schemas
        List<String> schemaIds = schemaRegistryDelegate.listSchemaIds().block();
        
        log.info("Found {} schemas in registry: {}", schemaIds.size(), schemaIds);
        
        // Verify that we have at least some schemas
        assertNotNull(schemaIds);
        assertFalse(schemaIds.isEmpty(), "No schemas found in registry");
        
        // Verify that we can retrieve each schema
        for (String schemaId : schemaIds) {
            log.info("Retrieving schema: {}", schemaId);
            
            Mono<String> schemaContentMono = schemaRegistryDelegate.getSchemaContent(schemaId);
            
            StepVerifier.create(schemaContentMono)
                    .assertNext(content -> {
                        assertNotNull(content);
                        assertFalse(content.isEmpty(), "Schema content is empty for " + schemaId);
                        log.info("Successfully retrieved schema: {} ({} characters)", schemaId, content.length());
                    })
                    .verifyComplete();
        }
    }

    @Test
    void testSchemaContentProviderFunction() {
        // Create a schema content provider function similar to what would be used in CustomConfigSchemaValidator
        Function<SchemaReference, java.util.Optional<String>> schemaContentProvider = schemaRef -> {
            try {
                return java.util.Optional.ofNullable(
                        schemaRegistryDelegate.getSchemaContent(schemaRef.subject()).block()
                );
            } catch (Exception e) {
                log.error("Error retrieving schema: {}", schemaRef, e);
                return java.util.Optional.empty();
            }
        };
        
        // List all schemas
        List<String> schemaIds = schemaRegistryDelegate.listSchemaIds().block();
        assertNotNull(schemaIds);
        
        // Convert to SchemaReference objects
        List<SchemaReference> schemaRefs = schemaIds.stream()
                .map(id -> new SchemaReference(id, 1))
                .collect(Collectors.toList());
        
        // Verify that we can retrieve each schema using the provider function
        for (SchemaReference schemaRef : schemaRefs) {
            log.info("Retrieving schema using provider function: {}", schemaRef);
            
            java.util.Optional<String> schemaContentOpt = schemaContentProvider.apply(schemaRef);
            
            assertTrue(schemaContentOpt.isPresent(), "Schema content not found for " + schemaRef);
            assertFalse(schemaContentOpt.get().isEmpty(), "Schema content is empty for " + schemaRef);
            log.info("Successfully retrieved schema using provider function: {} ({} characters)", 
                    schemaRef, schemaContentOpt.get().length());
        }
    }
}