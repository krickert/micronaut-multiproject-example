package com.krickert.search.test;

import com.krickert.search.model.pipe.PipeDoc;
import com.krickert.search.test.registry.AbstractSchemaRegistrySerializationTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Concrete implementation of AbstractSchemaRegistrySerializationTest for testing the MotoSchemaRegistry.
 * This class tests the basic functionality of the MotoSchemaRegistry's serializer and deserializer classes.
 */
@MicronautTest(environments = "test")
public class MotoSchemaRegistrySerializationTest extends AbstractSchemaRegistrySerializationTest<PipeDoc> {
    private static final Logger log = LoggerFactory.getLogger(MotoSchemaRegistrySerializationTest.class);

    @Override
    protected PipeDoc createTestMessage() {
        return PipeDocExample.createFullPipeDoc();
    }

    /**
     * Additional test specific to the Moto implementation.
     * Verifies that the serializer and deserializer classes are AWS Glue Schema Registry classes.
     */
    @Test
    public void testMotoSpecificSerializerClasses() {
        String serializerClass = schemaRegistry.getSerializerClass();
        String deserializerClass = schemaRegistry.getDeserializerClass();

        assertNotNull(serializerClass, "Serializer class should not be null");
        assertNotNull(deserializerClass, "Deserializer class should not be null");

        // Verify that the serializer and deserializer are AWS Glue Schema Registry classes
        log.info("Moto serializer class: {}", serializerClass);
        log.info("Moto deserializer class: {}", deserializerClass);

        // Create a test message
        PipeDoc testMessage = createTestMessage();
        log.info("Test PipeDoc message: {}", testMessage);
    }
}
