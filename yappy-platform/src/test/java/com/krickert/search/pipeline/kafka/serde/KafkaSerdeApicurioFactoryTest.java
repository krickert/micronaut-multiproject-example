package com.krickert.search.pipeline.kafka.serde;

import com.krickert.search.config.grpc.ReloadServiceEndpoint; // Assuming needed
// Import PipeStream instead of PipeDoc
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.protobuf.PipeDocExample; // Import the example generator
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer; // Import Serializer
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the KafkaSerdeFactory implementations when the 'apicurio-test' environment is active.
 * These tests verify that the Apicurio Protobuf serdes are selected and function correctly,
 * including tests for PipeStream serialization/deserialization. // Updated description
 */
@MicronautTest(environments = "apicurio-test") // Ensure Apicurio configuration is loaded
@DisplayName("Kafka Serde Provider Tests (Apicurio Environment)")
class KafkaSerdeApicurioFactoryTest {

    @Inject
    private KafkaSerdeProvider serdeProvider;

    // Topic name for PipeStream tests
    private static final String TEST_PIPESTREAM_TOPIC = "apicurio-pipestream-topic";

    /**
     * Test that the DefaultKafkaSerdeProvider is injected.
     */
    @Test
    @DisplayName("Should Inject DefaultKafkaSerdeProvider")
    void testProviderInjection() {
        assertNotNull(serdeProvider, "Serde provider should be injected");
        assertInstanceOf(DefaultKafkaSerdeProvider.class, serdeProvider, "Injected provider should be DefaultKafkaSerdeProvider");
    }

    /**
     * Test that the DefaultKafkaSerdeProvider returns non-null Apicurio serdes.
     * This test uses a generic topic name.
     */
    @Test
    @DisplayName("Should Provide Non-Null Apicurio Serdes")
    void testGetApicurioSerdes() {
        Serializer<?> keySerializer = null;
        Deserializer<?> keyDeserializer = null;
        Serializer<?> valueSerializer = null;
        Deserializer<?> valueDeserializer = null;
        try {
            // Get Key Serdes
            keySerializer = serdeProvider.getKeySerializer();
            assertNotNull(keySerializer, "Key serializer should not be null");
            keyDeserializer = serdeProvider.getKeyDeserializer();
            assertNotNull(keyDeserializer, "Key deserializer should not be null");

            // Get Value Serdes
            valueSerializer = serdeProvider.getValueSerializer();
            assertNotNull(valueSerializer, "Value serializer should not be null");
            valueDeserializer = serdeProvider.getValueDeserializer();
            assertNotNull(valueDeserializer, "Value deserializer should not be null");

            // Basic check for Apicurio specific classes (adjust if your implementation differs)
            assertTrue(valueSerializer.getClass().getName().contains("io.apicurio.registry.serde"),
                    "Value Serializer should be an Apicurio type, but was: " + valueSerializer.getClass().getName());
            assertTrue(valueDeserializer.getClass().getName().contains("io.apicurio.registry.serde"),
                    "Value Deserializer should be an Apicurio type, but was: " + valueDeserializer.getClass().getName());

        } finally {
            // Close the serdes to release resources
            if (keySerializer != null) keySerializer.close();
            if (keyDeserializer != null) keyDeserializer.close();
            if (valueSerializer != null) valueSerializer.close();
            if (valueDeserializer != null) valueDeserializer.close();
        }
    }


    /**
     * Test the serialization and deserialization of a PipeStream object using Apicurio serdes.
     */
    @Test
    @DisplayName("Should Serialize and Deserialize PipeStream Correctly with Apicurio")
    void testPipeStreamSerializationDeserializationWithApicurio() {
        // 1. Create a sample PipeStream object using the generator
        PipeStream originalPipeStream = PipeDocExample.createFullPipeStream(); // Use the PipeStream generator
        assertNotNull(originalPipeStream, "Generated PipeStream should not be null");
        assertFalse(originalPipeStream.getStreamId().isEmpty(), "Generated PipeStream should have a Stream ID");

        // 2. Get the appropriate value serializer and deserializer for PipeStream
        // Casting is necessary here based on provider logic
        Serializer<PipeStream> valueSerializer = serdeProvider.getValueSerializer();
        Deserializer<PipeStream> valueDeserializer = serdeProvider.getValueDeserializer();

        assertNotNull(valueSerializer, "PipeStream value serializer should not be null");
        assertNotNull(valueDeserializer, "PipeStream value deserializer should not be null");

        byte[] serializedData = null;
        PipeStream deserializedPipeStream = null;

        try (valueSerializer; valueDeserializer) {
            // 3. Serialize the PipeStream object
            serializedData = valueSerializer.serialize(TEST_PIPESTREAM_TOPIC, originalPipeStream);
            assertNotNull(serializedData, "Serialized PipeStream data should not be null");
            assertTrue(serializedData.length > 0, "Serialized PipeStream data should not be empty");

            System.out.println("[DEBUG_LOG] Apicurio Serialized PipeStream data length: " + serializedData.length);

            // 4. Deserialize the data back into a PipeStream object
            deserializedPipeStream = valueDeserializer.deserialize(TEST_PIPESTREAM_TOPIC, serializedData);
            assertNotNull(deserializedPipeStream, "Deserialized PipeStream should not be null");

            // 5. Assert equality
            // Protobuf generated classes have equals() methods based on content.
            assertEquals(originalPipeStream, deserializedPipeStream, "Deserialized PipeStream object should be equal to the original");

            // Optionally, assert specific fields for extra verification
            assertEquals(originalPipeStream.getStreamId(), deserializedPipeStream.getStreamId());
            assertEquals(originalPipeStream.getPipelineName(), deserializedPipeStream.getPipelineName());
            assertEquals(originalPipeStream.getCurrentHopNumber(), deserializedPipeStream.getCurrentHopNumber());
            assertEquals(originalPipeStream.getCurrentDoc(), deserializedPipeStream.getCurrentDoc()); // Compare nested PipeDoc
            assertEquals(originalPipeStream.getHistoryList(), deserializedPipeStream.getHistoryList()); // Compare list of history entries
            assertEquals(originalPipeStream.getInputBlob(), deserializedPipeStream.getInputBlob()); // Compare blob
            assertEquals(originalPipeStream.getContextParamsMap(), deserializedPipeStream.getContextParamsMap()); // Compare context map


            System.out.println("[DEBUG_LOG] Original PipeStream (Apicurio): " + originalPipeStream.getStreamId());
            System.out.println("[DEBUG_LOG] Deserialized PipeStream (Apicurio): " + deserializedPipeStream.getStreamId());

        }
        // Clean up: Close the serializer and deserializer
    }


    /**
     * Test the factory correctly identifies the Apicurio deserializer based on class name.
     * This test uses the PipeStream topic.
     */
    @Test
    @DisplayName("Should Identify Apicurio Deserializer by Class Name for PipeStream")
    void testFactoryBasedOnRegistryTypeIsApicurioForPipeStream() { // Updated method name
        try (Deserializer<?> valueDeserializer = serdeProvider.getValueDeserializer()) {
            // Get a value deserializer using the PipeStream topic
            // Use PipeStream topic
            assertNotNull(valueDeserializer, "Value deserializer for PipeStream topic should not be null");

            String deserializerClassName = valueDeserializer.getClass().getName();

            // Log the deserializer class for debugging
            System.out.println("[DEBUG_LOG] Apicurio Deserializer class (PipeStream Topic): " + deserializerClassName);

            // Verify the deserializer is an Apicurio Protobuf one (adjust class name if needed)
            assertTrue(deserializerClassName.contains("io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer") ||
                            deserializerClassName.contains("io.apicurio.registry.serde."), // More general check
                    "Expected an Apicurio Protobuf deserializer but got: " + deserializerClassName);

        }
        // Clean up: Close the deserializer
    }

}