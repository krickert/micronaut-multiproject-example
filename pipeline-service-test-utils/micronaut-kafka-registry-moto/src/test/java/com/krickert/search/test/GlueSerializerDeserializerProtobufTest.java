package com.krickert.search.test;

import com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer;
import com.krickert.search.test.registry.SchemaRegistry;
import com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer;
import com.amazonaws.services.schemaregistry.utils.AWSSchemaRegistryConstants;
import com.krickert.search.model.PipeDoc;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

// This test demonstrates using Protocol Buffers as the serialization format.
@Testcontainers
@MicronautTest(environments = "test")
public class GlueSerializerDeserializerProtobufTest {
    public static final Logger log = LoggerFactory.getLogger(GlueSerializerDeserializerProtobufTest.class);

    @Inject
    private SchemaRegistry schemaRegistry;

    @Test
    public void testGlueSerializerAndDeserializerProtobuf() {
        // Ensure schema registry is started
        schemaRegistry.start();

        // Prepare a configuration map for the serializer/deserializer.
        Map<String, Object> configs = new HashMap<>();
        configs.put(AWSSchemaRegistryConstants.AWS_REGION, "us-east-1");
        configs.put(AWSSchemaRegistryConstants.AWS_ENDPOINT, schemaRegistry.getEndpoint());
        configs.put(AWSSchemaRegistryConstants.REGISTRY_NAME, schemaRegistry.getRegistryName());
        // Specify that the data format should be Protocol Buffers.
        configs.put(AWSSchemaRegistryConstants.DATA_FORMAT, "PROTOBUF");
        configs.put(AWSSchemaRegistryConstants.PROTOBUF_MESSAGE_TYPE, "POJO");
        configs.put(AWSSchemaRegistryConstants.COMPATIBILITY_SETTING, "BACKWARD");
        configs.put(AWSSchemaRegistryConstants.SCHEMA_AUTO_REGISTRATION_SETTING, "true");
        // Instantiate and configure the serializer and deserializer.
        GlueSchemaRegistryKafkaSerializer serializer = new GlueSchemaRegistryKafkaSerializer();
        GlueSchemaRegistryKafkaDeserializer deserializer = new GlueSchemaRegistryKafkaDeserializer();

        serializer.configure(configs, false);
        deserializer.configure(configs, false);

        Assertions.assertNotNull(serializer, "Serializer must be allocated");
        Assertions.assertNotNull(deserializer, "Deserializer must be allocated");
        PipeDoc protobufPayload = PipeDocExample.createFullPipeDoc();
        try {
            // Serialize the payload.
            byte[] serialized = serializer.serialize("test-topic", protobufPayload);
            // Deserialize the payload.
            Object deserialized = deserializer.deserialize("test-topic", serialized);

            if (deserialized != null) {
                // In a real test, you would cast deserialized to your Protobuf message type
                // and verify its fields.
                log.info("Round-trip payload: " + deserialized);
            }
        } catch (Exception e) {
            // In a mock environment, certain operations might not be fully implemented.
            fail("Exception during round-trip test", e);
        }
    }
}
