package com.krickert.search.config.schema.model.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.io.IOException;
import java.util.function.Function;

/**
 * Utility class for testing schema registry model classes.
 * Provides methods for serialization, deserialization, and validation.
 */
public class SchemaRegistryTestUtils {

    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    /**
     * Creates an ObjectMapper configured for schema registry model classes.
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return objectMapper;
    }

    /**
     * Serializes a schema registry model object to JSON.
     *
     * @param object The object to serialize
     * @return The JSON string
     * @throws JsonProcessingException If serialization fails
     */
    public static String toJson(Object object) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(object);
    }

    /**
     * Deserializes a JSON string to a schema registry model object.
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
     * Performs a serialization test on a schema registry model object.
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
     * Performs a serialization test on a schema registry model object with a custom equality check.
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