package com.krickert.search.test;

import com.krickert.search.model.pipe.PipeDoc;
import com.krickert.search.test.registry.AbstractSchemaRegistrySerializationTest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concrete implementation of AbstractSchemaRegistrySerializationTest for testing the ApicurioSchemaRegistry.
 * This class tests the basic functionality of the ApicurioSchemaRegistry's serializer and deserializer classes.
 */
@MicronautTest(environments = "test")
public class ApicurioSchemaRegistrySerializationTest extends AbstractSchemaRegistrySerializationTest<PipeDoc> {
    private static final Logger log = LoggerFactory.getLogger(ApicurioSchemaRegistrySerializationTest.class);

    @Override
    protected PipeDoc createTestMessage() {
        return PipeDocExample.createFullPipeDoc();
    }

    /**
     * Additional test specific to the Apicurio implementation.
     * Verifies that the serializer and deserializer classes are Apicurio Registry classes.
     */
    @Test
    public void testApicurioSpecificSerializerClasses() {
        String serializerClass = schemaRegistry.getSerializerClass();
        String deserializerClass = schemaRegistry.getDeserializerClass();

        assertNotNull(serializerClass, "Serializer class should not be null");
        assertNotNull(deserializerClass, "Deserializer class should not be null");
        
        // Verify that the serializer and deserializer are Apicurio Registry classes
        assertTrue(serializerClass.contains("apicurio") || serializerClass.contains("Protobuf"), 
                "Serializer class should be an Apicurio Registry class");
        assertTrue(deserializerClass.contains("apicurio") || deserializerClass.contains("Protobuf"), 
                "Deserializer class should be an Apicurio Registry class");

        log.info("Apicurio serializer class: {}", serializerClass);
        log.info("Apicurio deserializer class: {}", deserializerClass);

        // Create a test message
        PipeDoc testMessage = createTestMessage();
        log.info("Test PipeDoc message: {}", testMessage);
    }
}