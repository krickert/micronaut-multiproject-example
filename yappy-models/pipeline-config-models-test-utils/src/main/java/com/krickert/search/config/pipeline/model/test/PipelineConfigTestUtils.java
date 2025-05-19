package com.krickert.search.config.pipeline.model.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.krickert.search.config.pipeline.model.GrpcTransportConfig;
import com.krickert.search.config.pipeline.model.KafkaTransportConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/**
 * Utility class for testing pipeline configuration models.
 * Provides methods for serialization, deserialization, and validation.
 */
public class PipelineConfigTestUtils {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /**
     * Creates an ObjectMapper configured for pipeline configuration models.
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return objectMapper;
    }

    /**
     * Serializes a pipeline configuration object to JSON.
     *
     * @param object The object to serialize
     * @return The JSON string
     * @throws JsonProcessingException If serialization fails
     */
    public static String toJson(Object object) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(object);
    }

    /**
     * Deserializes a JSON string to a pipeline configuration object.
     *
     * @param json      The JSON string
     * @param valueType The class of the object to deserialize to
     * @param <T>       The type of the object
     * @return The deserialized object
     * @throws IOException If deserialization fails
     */
    public static <T> T fromJson(String json, Class<T> valueType) throws IOException {
        return OBJECT_MAPPER.readValue(json, valueType);
    }

    /**
     * Performs a round-trip serialization and deserialization of an object.
     * This is useful for testing that an object can be correctly serialized and deserialized.
     *
     * @param object    The object to serialize and deserialize
     * @param valueType The class of the object
     * @param <T>       The type of the object
     * @return The deserialized object
     * @throws IOException If serialization or deserialization fails
     */
    public static <T> T roundTrip(T object, Class<T> valueType) throws IOException {
        String json = toJson(object);
        return fromJson(json, valueType);
    }

    /**
     * Creates a JsonConfigOptions object from a JSON string.
     *
     * @param jsonString The JSON string
     * @return The JsonConfigOptions object
     * @throws JsonProcessingException If parsing the JSON fails
     */
    public static PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(String jsonString) throws JsonProcessingException {
        if (jsonString == null || jsonString.isBlank()) {
            return new PipelineStepConfig.JsonConfigOptions(null, Map.of());
        }
        return new PipelineStepConfig.JsonConfigOptions(OBJECT_MAPPER.readTree(jsonString), Map.of());
    }

    /**
     * Creates a JsonConfigOptions object from a JsonNode.
     *
     * @param jsonNode The JsonNode
     * @return The JsonConfigOptions object
     */
    public static PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(JsonNode jsonNode) {
        return new PipelineStepConfig.JsonConfigOptions(jsonNode, Map.of());
    }

    /**
     * Creates a JsonConfigOptions object from a map of configuration parameters.
     *
     * @param configParams The configuration parameters
     * @return The JsonConfigOptions object
     */
    public static PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(Map<String, String> configParams) {
        return new PipelineStepConfig.JsonConfigOptions(null, configParams);
    }

    /**
     * Creates a ProcessorInfo object for a gRPC service.
     *
     * @param grpcServiceName The name of the gRPC service
     * @return The ProcessorInfo object
     */
    public static PipelineStepConfig.ProcessorInfo createGrpcProcessorInfo(String grpcServiceName) {
        return new PipelineStepConfig.ProcessorInfo(grpcServiceName, null);
    }

    /**
     * Creates a ProcessorInfo object for an internal processor.
     *
     * @param internalProcessorBeanName The name of the internal processor bean
     * @return The ProcessorInfo object
     */
    public static PipelineStepConfig.ProcessorInfo createInternalProcessorInfo(String internalProcessorBeanName) {
        return new PipelineStepConfig.ProcessorInfo(null, internalProcessorBeanName);
    }

    /**
     * Creates an OutputTarget object for a Kafka transport.
     *
     * @param targetStepName          The name of the target step
     * @param topic                   The Kafka topic
     * @param kafkaProducerProperties The Kafka producer properties
     * @return The OutputTarget object
     */
    public static PipelineStepConfig.OutputTarget createKafkaOutputTarget(
            String targetStepName, String topic, Map<String, String> kafkaProducerProperties) {
        KafkaTransportConfig kafkaTransport = new KafkaTransportConfig(topic, kafkaProducerProperties);
        return new PipelineStepConfig.OutputTarget(targetStepName, TransportType.KAFKA, null, kafkaTransport);
    }

    /**
     * Creates an OutputTarget object for a gRPC transport.
     *
     * @param targetStepName       The name of the target step
     * @param serviceName          The gRPC service name
     * @param grpcClientProperties The gRPC client properties
     * @return The OutputTarget object
     */
    public static PipelineStepConfig.OutputTarget createGrpcOutputTarget(
            String targetStepName, String serviceName, Map<String, String> grpcClientProperties) {
        GrpcTransportConfig grpcTransport = new GrpcTransportConfig(serviceName, grpcClientProperties);
        return new PipelineStepConfig.OutputTarget(targetStepName, TransportType.GRPC, grpcTransport, null);
    }

    /**
     * Creates an OutputTarget object for an internal transport.
     *
     * @param targetStepName The name of the target step
     * @return The OutputTarget object
     */
    public static PipelineStepConfig.OutputTarget createInternalOutputTarget(String targetStepName) {
        return new PipelineStepConfig.OutputTarget(targetStepName, TransportType.INTERNAL, null, null);
    }

    /**
     * Performs a serialization test on a pipeline configuration object.
     * Serializes the object to JSON, deserializes it back, and compares the result with the original.
     *
     * @param object    The object to test
     * @param valueType The class of the object
     * @param <T>       The type of the object
     * @return True if the test passes, false otherwise
     */
    public static <T> boolean testSerialization(T object, Class<T> valueType) {
        try {
            T roundTripped = roundTrip(object, valueType);
            return object.equals(roundTripped);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Performs a serialization test on a pipeline configuration object with a custom equality check.
     * Serializes the object to JSON, deserializes it back, and compares the result with the original using the provided equality function.
     *
     * @param object           The object to test
     * @param valueType        The class of the object
     * @param equalityFunction A function that compares two objects for equality
     * @param <T>              The type of the object
     * @return True if the test passes, false otherwise
     */
    public static <T> boolean testSerialization(T object, Class<T> valueType, Function<T, Boolean> equalityFunction) {
        try {
            T roundTripped = roundTrip(object, valueType);
            return equalityFunction.apply(roundTripped);
        } catch (IOException e) {
            return false;
        }
    }
}