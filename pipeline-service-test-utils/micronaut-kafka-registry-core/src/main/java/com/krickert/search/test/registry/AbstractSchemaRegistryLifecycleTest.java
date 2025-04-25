package com.krickert.search.test.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract test class for testing the lifecycle of a schema registry.
 * This class tests the basic functionality of starting and checking the status of a schema registry.
 * Concrete implementations should extend this class.
 */
public abstract class AbstractSchemaRegistryLifecycleTest extends AbstractSchemaRegistryTest {

    /**
     * Test that the schema registry can be started and is running.
     */
    @Test
    public void testStartAndIsRunning() {
        // The registry is already started in setUp(), but we'll call it again to test idempotence
        schemaRegistry.start();
        
        // Verify the registry is running
        assertTrue(schemaRegistry.isRunning(), "Schema registry should be running after start");
        
        // Verify the endpoint is not null or empty
        assertNotNull(schemaRegistry.getEndpoint(), "Schema registry endpoint should not be null");
        assertFalse(schemaRegistry.getEndpoint().isEmpty(), "Schema registry endpoint should not be empty");
        
        // Verify the registry name is not null or empty
        assertNotNull(schemaRegistry.getRegistryName(), "Schema registry name should not be null");
        assertFalse(schemaRegistry.getRegistryName().isEmpty(), "Schema registry name should not be empty");
        
        log.info("Schema registry is running at: {}", schemaRegistry.getEndpoint());
    }
    
    /**
     * Test that the schema registry provides valid serializer and deserializer classes.
     */
    @Test
    public void testSerializerAndDeserializerClasses() {
        // Verify the serializer class is not null or empty
        assertNotNull(schemaRegistry.getSerializerClass(), "Serializer class should not be null");
        assertFalse(schemaRegistry.getSerializerClass().isEmpty(), "Serializer class should not be empty");
        
        // Verify the deserializer class is not null or empty
        assertNotNull(schemaRegistry.getDeserializerClass(), "Deserializer class should not be null");
        assertFalse(schemaRegistry.getDeserializerClass().isEmpty(), "Deserializer class should not be empty");
        
        log.info("Using serializer: {}", schemaRegistry.getSerializerClass());
        log.info("Using deserializer: {}", schemaRegistry.getDeserializerClass());
    }
    
    /**
     * Test that the schema registry provides valid properties.
     */
    @Test
    public void testProperties() {
        // Get the properties from the registry
        var properties = schemaRegistry.getProperties();
        
        // Verify the properties are not null or empty
        assertNotNull(properties, "Properties should not be null");
        assertFalse(properties.isEmpty(), "Properties should not be empty");
        
        // Log some key properties for debugging
        log.info("Schema registry properties count: {}", properties.size());
        properties.forEach((key, value) -> {
            if (key.contains("endpoint") || key.contains("registry")) {
                log.info("Property: {} = {}", key, value);
            }
        });
    }
}