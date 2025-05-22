package com.krickert.search.config.service.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceAggregatedStatusTest {

    private static ObjectMapper objectMapper;
    private static JsonSchema schema;

    @BeforeAll
    static void setUpAll() throws Exception {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); // Good practice, though not strictly needed for current record
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // The @JsonInclude(JsonInclude.Include.NON_NULL) on the record handles null field omission.

        // Load the schema
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        try (InputStream schemaStream = ServiceAggregatedStatusTest.class.getResourceAsStream("/schemas/service-aggregated-status-schema.json")) {
            if (schemaStream == null) {
                throw new RuntimeException("Cannot find schema /schemas/service-aggregated-status-schema.json on classpath. Make sure it's in src/main/resources/schemas/ or src/test/resources/schemas/");
            }
            JsonNode schemaJsonNode = objectMapper.readTree(schemaStream);
            schema = schemaFactory.getSchema(schemaJsonNode);
        }
    }

    private ServiceAggregatedStatus createMinimalValidStatus() {
        return new ServiceAggregatedStatus(
                "minimal-service",
                ServiceOperationalStatus.DEFINED,
                null, // statusDetail is nullable
                System.currentTimeMillis(),
                0,
                0,
                false,
                null, // activeLocalInstanceId is nullable
                false,
                null, // proxyTargetInstanceId is nullable
                false,
                null, // activeClusterConfigVersion is nullable
                null, // reportedModuleConfigDigest is nullable
                null, // errorMessages -> becomes emptyList()
                null  // additionalAttributes -> becomes emptyMap()
        );
    }

    private ServiceAggregatedStatus createComprehensiveValidStatus() {
        return new ServiceAggregatedStatus(
                "echo-service",
                ServiceOperationalStatus.ACTIVE_HEALTHY,
                "All systems operational.",
                System.currentTimeMillis() - 10000,
                3,
                3,
                true,
                "echo-service-instance-1",
                false,
                null,
                false,
                "config-v1.2.3",
                "digest-abc123",
                List.of("Minor warning from last hour.", "Informational message."),
                Map.of("customMetric1", "value1", "region", "us-east-1")
        );
    }

    @Test
    @DisplayName("Serialization and Deserialization - Minimal Valid Instance")
    void testSerializationDeserialization_Minimal() throws JsonProcessingException {
        ServiceAggregatedStatus original = createMinimalValidStatus();

        String json = objectMapper.writeValueAsString(original);
        assertNotNull(json);
        System.out.println("Serialized Minimal JSON: " + json);

        // Check that nullable fields set to null are indeed omitted due to @JsonInclude(JsonInclude.Include.NON_NULL)
        assertFalse(json.contains("\"statusDetail\":null"));
        assertFalse(json.contains("\"activeLocalInstanceId\":null"));
        // errorMessages and additionalAttributes will be serialized as [] and {} because constructor makes them non-null empty collections.

        ServiceAggregatedStatus deserialized = objectMapper.readValue(json, ServiceAggregatedStatus.class);
        assertEquals(original, deserialized);
        assertTrue(deserialized.errorMessages().isEmpty()); // Verifying constructor behavior for null input
        assertTrue(deserialized.additionalAttributes().isEmpty()); // Verifying constructor behavior for null input
    }

    @Test
    @DisplayName("Serialization and Deserialization - Comprehensive Valid Instance")
    void testSerializationDeserialization_Comprehensive() throws JsonProcessingException {
        ServiceAggregatedStatus original = createComprehensiveValidStatus();

        String json = objectMapper.writeValueAsString(original);
        assertNotNull(json);
        System.out.println("Serialized Comprehensive JSON: " + json);

        ServiceAggregatedStatus deserialized = objectMapper.readValue(json, ServiceAggregatedStatus.class);
        assertEquals(original, deserialized);
        assertEquals(2, deserialized.errorMessages().size());
        assertEquals(2, deserialized.additionalAttributes().size());
    }

    @Test
    @DisplayName("Schema Validation - Valid Minimal Instance")
    void testSchemaValidation_ValidMinimalInstance() throws JsonProcessingException {
        ServiceAggregatedStatus status = createMinimalValidStatus();
        String json = objectMapper.writeValueAsString(status);
        JsonNode jsonNode = objectMapper.readTree(json);

        Set<ValidationMessage> errors = schema.validate(jsonNode);
        assertTrue(errors.isEmpty(), "Schema validation should pass for minimal valid instance. Errors: " + errors);
    }

    @Test
    @DisplayName("Schema Validation - Valid Comprehensive Instance")
    void testSchemaValidation_ValidComprehensiveInstance() throws JsonProcessingException {
        ServiceAggregatedStatus status = createComprehensiveValidStatus();
        String json = objectMapper.writeValueAsString(status);
        JsonNode jsonNode = objectMapper.readTree(json);

        Set<ValidationMessage> errors = schema.validate(jsonNode);
        assertTrue(errors.isEmpty(), "Schema validation should pass for comprehensive valid instance. Errors: " + errors);
    }

    // In ServiceAggregatedStatusTest.java

    // In ServiceAggregatedStatusTest.java

    @Test
    @DisplayName("Schema Validation - Invalid: Missing Required Field (serviceName)")
    void testSchemaValidation_Invalid_MissingRequiredField() throws JsonProcessingException {
        String invalidJson = """
                {
                  "operationalStatus": "ACTIVE_HEALTHY",
                  "lastCheckedByEngineMillis": 1678886400000,
                  "totalInstancesConsul": 1,
                  "healthyInstancesConsul": 1,
                  "isLocalInstanceActive": true,
                  "isProxying": false,
                  "isUsingStaleClusterConfig": false
                }
                """;
        JsonNode jsonNode = objectMapper.readTree(invalidJson);
        Set<ValidationMessage> errors = schema.validate(jsonNode);
        assertFalse(errors.isEmpty(), "Schema validation should fail when serviceName is missing.");

        // Corrected assertion:
        assertTrue(errors.stream().anyMatch(vm ->
                        vm.getMessage().contains("required property 'serviceName' not found") &&
                                "$".equals(vm.getInstanceLocation().toString()) // Check the instance location
                ),
                "Error message should indicate serviceName is required at the root. Errors: " + errors);
    }

    @Test
    @DisplayName("Schema Validation - Invalid: Wrong Type (totalInstancesConsul as string)")
    void testSchemaValidation_Invalid_WrongType() throws JsonProcessingException {
        String invalidJson = """
                {
                  "serviceName": "test-service",
                  "operationalStatus": "ACTIVE_HEALTHY",
                  "lastCheckedByEngineMillis": 1678886400000,
                  "totalInstancesConsul": "not-an-integer",
                  "healthyInstancesConsul": 1,
                  "isLocalInstanceActive": true,
                  "isProxying": false,
                  "isUsingStaleClusterConfig": false
                }
                """;
        JsonNode jsonNode = objectMapper.readTree(invalidJson);
        Set<ValidationMessage> errors = schema.validate(jsonNode);
        assertFalse(errors.isEmpty(), "Schema validation should fail for wrong type.");
        assertTrue(errors.stream().anyMatch(vm -> vm.getMessage().contains("totalInstancesConsul: string found, integer expected")),
                "Error message should indicate type mismatch for totalInstancesConsul. Errors: " + errors);
    }

    @Test
    @DisplayName("Constructor Validation - Null or Blank ServiceName")
    void testConstructorValidation_ServiceName() {
        Exception e1 = assertThrows(NullPointerException.class, () ->
                new ServiceAggregatedStatus(null, ServiceOperationalStatus.UNKNOWN, null, 0L, 0, 0, false, null, false, null, false, null, null, null, null)
        );
        assertEquals("serviceName cannot be null", e1.getMessage());

        Exception e2 = assertThrows(IllegalArgumentException.class, () ->
                new ServiceAggregatedStatus(" ", ServiceOperationalStatus.UNKNOWN, null, 0L, 0, 0, false, null, false, null, false, null, null, null, null)
        );
        assertEquals("serviceName cannot be blank", e2.getMessage());
    }

    @Test
    @DisplayName("Constructor Validation - Null OperationalStatus")
    void testConstructorValidation_OperationalStatus() {
        Exception e = assertThrows(NullPointerException.class, () ->
                new ServiceAggregatedStatus("test", null, null, 0L, 0, 0, false, null, false, null, false, null, null, null, null)
        );
        assertEquals("operationalStatus cannot be null", e.getMessage());
    }

    @Test
    @DisplayName("Constructor - Collections Initialized Correctly")
    void testConstructor_CollectionsInitialization() {
        // Test with null collections passed to constructor
        ServiceAggregatedStatus statusWithNullCollections = new ServiceAggregatedStatus(
                "collections-test", ServiceOperationalStatus.DEFINED, null, 0L, 0, 0, false, null, false, null, false, null, null,
                null, // errorMessages
                null  // additionalAttributes
        );
        assertNotNull(statusWithNullCollections.errorMessages(), "errorMessages should be initialized to empty list, not null");
        assertTrue(statusWithNullCollections.errorMessages().isEmpty(), "errorMessages should be empty");
        assertNotNull(statusWithNullCollections.additionalAttributes(), "additionalAttributes should be initialized to empty map, not null");
        assertTrue(statusWithNullCollections.additionalAttributes().isEmpty(), "additionalAttributes should be empty");

        // Test with non-null but empty collections
        ServiceAggregatedStatus statusWithEmptyCollections = new ServiceAggregatedStatus(
                "collections-test-empty", ServiceOperationalStatus.DEFINED, null, 0L, 0, 0, false, null, false, null, false, null, null,
                Collections.emptyList(),
                Collections.emptyMap()
        );
        assertTrue(statusWithEmptyCollections.errorMessages().isEmpty());
        assertTrue(statusWithEmptyCollections.additionalAttributes().isEmpty());

        // Test immutability of collections returned by getters
        assertThrows(UnsupportedOperationException.class, () -> statusWithNullCollections.errorMessages().add("new error"));
        assertThrows(UnsupportedOperationException.class, () -> statusWithNullCollections.additionalAttributes().put("key", "value"));
    }

    @Test
    @DisplayName("Deserialization - Missing Optional Collections")
    void testDeserialization_MissingOptionalCollections() throws JsonProcessingException {
        // JSON where errorMessages and additionalAttributes are completely missing
        String jsonMissingCollections = """
                {
                  "serviceName": "missing-collections",
                  "operationalStatus": "DEFINED",
                  "lastCheckedByEngineMillis": 1678886400000,
                  "totalInstancesConsul": 0,
                  "healthyInstancesConsul": 0,
                  "isLocalInstanceActive": false,
                  "isProxying": false,
                  "isUsingStaleClusterConfig": false
                }
                """;
        ServiceAggregatedStatus deserialized = objectMapper.readValue(jsonMissingCollections, ServiceAggregatedStatus.class);
        assertNotNull(deserialized.errorMessages(), "errorMessages should be initialized to empty list by constructor");
        assertTrue(deserialized.errorMessages().isEmpty());
        assertNotNull(deserialized.additionalAttributes(), "additionalAttributes should be initialized to empty map by constructor");
        assertTrue(deserialized.additionalAttributes().isEmpty());
    }

    @Test
    @DisplayName("Deserialization - Null Optional Collections in JSON")
    void testDeserialization_NullOptionalCollections() throws JsonProcessingException {
        // JSON where errorMessages and additionalAttributes are explicitly null
        String jsonNullCollections = """
                {
                  "serviceName": "null-collections",
                  "operationalStatus": "DEFINED",
                  "lastCheckedByEngineMillis": 1678886400000,
                  "totalInstancesConsul": 0,
                  "healthyInstancesConsul": 0,
                  "isLocalInstanceActive": false,
                  "isProxying": false,
                  "isUsingStaleClusterConfig": false,
                  "errorMessages": null,
                  "additionalAttributes": null
                }
                """;
        ServiceAggregatedStatus deserialized = objectMapper.readValue(jsonNullCollections, ServiceAggregatedStatus.class);
        assertNotNull(deserialized.errorMessages(), "errorMessages should be initialized to empty list by constructor even if JSON field is null");
        assertTrue(deserialized.errorMessages().isEmpty());
        assertNotNull(deserialized.additionalAttributes(), "additionalAttributes should be initialized to empty map by constructor even if JSON field is null");
        assertTrue(deserialized.additionalAttributes().isEmpty());
    }
}