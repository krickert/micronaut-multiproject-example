package com.krickert.search.test.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Abstract test class for testing serialization and deserialization using a schema registry.
 * This class tests the basic functionality of the schema registry's serializer and deserializer classes.
 * Concrete implementations should extend this class and implement the necessary methods.
 */
public abstract class AbstractSchemaRegistrySerializationTest<T> extends AbstractSchemaRegistryTest {

    /**
     * Create a test message to use for serialization testing.
     * 
     * @return a test message
     */
    protected abstract T createTestMessage();

    /**
     * Verify that the serializer and deserializer classes are valid.
     */
    @Test
    public void testSerializerAndDeserializerClasses() {
        // Ensure schema registry is started
        schemaRegistry.start();

        // Log configuration
        log.info("Schema registry endpoint: {}", schemaRegistry.getEndpoint());
        log.info("Schema registry name: {}", schemaRegistry.getRegistryName());

        // Verify the serializer class is not null or empty
        String serializerClass = schemaRegistry.getSerializerClass();
        assertNotNull(serializerClass, "Serializer class should not be null");
        log.info("Using serializer: {}", serializerClass);

        // Verify the deserializer class is not null or empty
        String deserializerClass = schemaRegistry.getDeserializerClass();
        assertNotNull(deserializerClass, "Deserializer class should not be null");
        log.info("Using deserializer: {}", deserializerClass);

        // Get the properties from the registry
        Map<String, String> properties = schemaRegistry.getProperties();

        // Log some key properties for debugging
        log.info("Schema registry properties count: {}", properties.size());
        properties.forEach((key, value) -> {
            if (key.contains("serializer") || key.contains("deserializer") || 
                key.contains("schema") || key.contains("registry")) {
                log.info("Property: {} = {}", key, value);
            }
        });

        // Create a test message
        T testMessage = createTestMessage();
        log.info("Test message: {}", testMessage);
    }
}
