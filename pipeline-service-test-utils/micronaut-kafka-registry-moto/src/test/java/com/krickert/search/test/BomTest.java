package com.krickert.search.test;

import com.krickert.search.test.moto.MotoSchemaRegistry;
import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that the BOM works correctly.
 * This test checks that the library can be loaded as a dependency and that the SchemaRegistry is properly configured.
 */
@MicronautTest
public class BomTest {
    private static final Logger log = LoggerFactory.getLogger(BomTest.class);

    @Inject
    private SchemaRegistry schemaRegistry;

    @Test
    void testBomConfiguration() {
        log.info("[DEBUG_LOG] Testing BOM configuration");
        assertNotNull(schemaRegistry, "SchemaRegistry should not be null");
        assertInstanceOf(MotoSchemaRegistry.class, schemaRegistry, "Default SchemaRegistry should be MotoSchemaRegistry, but was: " + schemaRegistry.getClass().getName());
        log.info("[DEBUG_LOG] SchemaRegistry is: {}", schemaRegistry.getClass().getName());
        
        // Verify that the registry is running
        assertTrue(schemaRegistry.isRunning(), "SchemaRegistry should be running");
        log.info("[DEBUG_LOG] SchemaRegistry endpoint: {}", schemaRegistry.getEndpoint());
        log.info("[DEBUG_LOG] SchemaRegistry name: {}", schemaRegistry.getRegistryName());
        
        // Verify that the serializer and deserializer classes are set correctly
        assertNotNull(schemaRegistry.getSerializerClass(), "Serializer class should not be null");
        assertNotNull(schemaRegistry.getDeserializerClass(), "Deserializer class should not be null");
        log.info("[DEBUG_LOG] Serializer class: {}", schemaRegistry.getSerializerClass());
        log.info("[DEBUG_LOG] Deserializer class: {}", schemaRegistry.getDeserializerClass());
        
        // Verify that the properties are set correctly
        Map<String, String> properties = schemaRegistry.getProperties();
        assertFalse(properties.isEmpty(), "Properties should not be empty");
        log.info("[DEBUG_LOG] Properties size: {}", properties.size());
        
        // Verify that the registry can be configured
        ApplicationContext context = ApplicationContext.builder()
                .properties(Map.of("schema.registry.type", "moto"))
                .build();

        try (context) {
            context.start();
            SchemaRegistry registry = context.getBean(SchemaRegistry.class);
            assertNotNull(registry, "SchemaRegistry should not be null");
            assertInstanceOf(MotoSchemaRegistry.class, registry, "Configured SchemaRegistry should be MotoSchemaRegistry, but was: " + registry.getClass().getName());
            log.info("[DEBUG_LOG] Configured SchemaRegistry is: {}", registry.getClass().getName());
        }
    }
}