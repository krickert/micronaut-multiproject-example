package com.krickert.search.config.consul.schema.service;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.schema.registry.*;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest // Starts an embedded Micronaut server with your gRPC service
@Property(name = "grpc.client.plaintext", value = "true") // Useful if your test server doesn't use TLS
class SchemaRegistryServiceImplTest {

    private static final Logger testLog = LoggerFactory.getLogger(SchemaRegistryServiceImplTest.class);
    // CORRECTED: This should match the prefix your ConsulSchemaRegistryDelegate uses internally.
    private static final String SCHEMA_KV_DELEGATE_PREFIX = "config/pipeline/schemas/";
    // Inject the gRPC client stub. Micronaut handles creating this client
    // configured to talk to the embedded gRPC server.
    @Inject
    @GrpcChannel(GrpcServerChannel.NAME) // Targets the in-process gRPC server
            SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub client;
    // Inject ConsulKvService to help with setup/cleanup if needed
    @Inject
    ConsulKvService consulKvService;

    @BeforeEach
    void setUp() {
        // Ensure a clean state for the schema prefix before each test.
        // This uses the ensureKeysDeleted method from your ConsulKvService.
        testLog.info("Cleaning up Consul prefix before test: {}", SCHEMA_KV_DELEGATE_PREFIX);
        // Note: ensureKeysDeleted should ideally handle the full path logic if SCHEMA_KV_DELEGATE_PREFIX is relative
        // For now, assuming SCHEMA_KV_DELEGATE_PREFIX is the full path the delegate uses.
        Boolean cleanupSuccess = consulKvService.ensureKeysDeleted(SCHEMA_KV_DELEGATE_PREFIX).block();
        assertEquals(Boolean.TRUE, cleanupSuccess, "Consul cleanup before test failed for prefix: " + SCHEMA_KV_DELEGATE_PREFIX);
        testLog.info("Cleanup complete for prefix: {}", SCHEMA_KV_DELEGATE_PREFIX);
    }

    @Test
    void registerAndGetSchema_success() {
        String schemaId = "test-schema-" + System.currentTimeMillis();
        String schemaContent = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}}";

        // 1. Register Schema
        RegisterSchemaRequest registerRequest = RegisterSchemaRequest.newBuilder()
                .setSchemaId(schemaId)
                .setSchemaContent(schemaContent)
                .build();

        testLog.info("Registering schema: {}", schemaId);
        RegisterSchemaResponse registerResponse = client.registerSchema(registerRequest);

        // Assert Register Response
        assertNotNull(registerResponse, "RegisterSchemaResponse should not be null");
        assertTrue(registerResponse.getSuccess(), "Registration should be successful");
        assertEquals(schemaId, registerResponse.getSchemaId(), "Schema ID in response should match request");
        assertTrue(registerResponse.getValidationErrorsList().isEmpty(), "There should be no validation errors on successful registration");
        assertTrue(registerResponse.hasTimestamp(), "Response should have a timestamp");

        // 2. Get Schema
        GetSchemaRequest getRequest = GetSchemaRequest.newBuilder().setSchemaId(schemaId).build();
        testLog.info("Getting schema: {}", schemaId);
        GetSchemaResponse getResponse = client.getSchema(getRequest);

        // Assert Get Response
        assertNotNull(getResponse, "GetSchemaResponse should not be null");
        assertTrue(getResponse.hasSchemaInfo(), "Response should contain SchemaInfo");
        SchemaInfo schemaInfo = getResponse.getSchemaInfo();
        assertEquals(schemaId, schemaInfo.getSchemaId(), "Schema ID in SchemaInfo should match");
        assertEquals(schemaContent, schemaInfo.getSchemaContent(), "Schema content should match");
        assertTrue(schemaInfo.hasCreatedAt(), "SchemaInfo should have created_at timestamp");
        assertTrue(schemaInfo.hasUpdatedAt(), "SchemaInfo should have updated_at timestamp");
    }

    @Test
    void getSchema_notFound() {
        String nonExistentSchemaId = "does-not-exist-" + System.currentTimeMillis();
        GetSchemaRequest getRequest = GetSchemaRequest.newBuilder().setSchemaId(nonExistentSchemaId).build();

        testLog.info("Attempting to get non-existent schema: {}", nonExistentSchemaId);
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.getSchema(getRequest);
        });

        assertEquals(io.grpc.Status.Code.NOT_FOUND, exception.getStatus().getCode());
        assertNotNull(exception.getStatus().getDescription());
        assertTrue(exception.getStatus().getDescription().contains(nonExistentSchemaId));
    }

    @Test
    void registerSchema_invalidContent() {
        String schemaId = "invalid-schema-" + System.currentTimeMillis();
        String invalidSchemaContent = "{\"type\": \"object\""; // Malformed JSON

        RegisterSchemaRequest registerRequest = RegisterSchemaRequest.newBuilder()
                .setSchemaId(schemaId)
                .setSchemaContent(invalidSchemaContent)
                .build();

        testLog.info("Attempting to register schema with invalid content: {}", schemaId);
        RegisterSchemaResponse registerResponse = client.registerSchema(registerRequest);

        assertNotNull(registerResponse, "RegisterSchemaResponse should not be null");
        assertFalse(registerResponse.getSuccess(), "Registration should fail for invalid content");
        assertEquals(schemaId, registerResponse.getSchemaId(), "Schema ID in response should match request");
        assertFalse(registerResponse.getValidationErrorsList().isEmpty(), "Should have validation errors");
        String firstError = registerResponse.getValidationErrors(0);
        assertTrue(
                firstError.contains("Schema content is not a valid JSON Schema") || firstError.contains("Invalid JSON syntax"),
                "Error message should indicate invalid JSON or schema structure. Actual: " + firstError
        );
        assertTrue(registerResponse.hasTimestamp(), "Response should have a timestamp");

        GetSchemaRequest getRequest = GetSchemaRequest.newBuilder().setSchemaId(schemaId).build();
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.getSchema(getRequest);
        }, "Schema with invalid content should not have been saved");
        assertEquals(io.grpc.Status.Code.NOT_FOUND, exception.getStatus().getCode());
    }

    @Test
    void deleteSchema_success() {
        String schemaId = "to-be-deleted-" + System.currentTimeMillis();
        String schemaContent = "{\"type\": \"string\"}";

        RegisterSchemaResponse registerSchemaResponse = client.registerSchema(RegisterSchemaRequest.newBuilder().setSchemaId(schemaId).setSchemaContent(schemaContent).build());
        assertTrue(registerSchemaResponse.getSuccess(), "Registration should be successful before delete");

        DeleteSchemaRequest deleteRequest = DeleteSchemaRequest.newBuilder().setSchemaId(schemaId).build();
        testLog.info("Deleting schema: {}", schemaId);
        DeleteSchemaResponse deleteResponse = client.deleteSchema(deleteRequest);

        assertNotNull(deleteResponse);
        assertEquals(Empty.getDefaultInstance(), deleteResponse.getAcknowledgement());

        GetSchemaRequest getRequest = GetSchemaRequest.newBuilder().setSchemaId(schemaId).build();
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.getSchema(getRequest);
        });
        assertEquals(io.grpc.Status.Code.NOT_FOUND, exception.getStatus().getCode());
    }

    // --- New Tests ---

    @Test
    void listSchemas_empty() {
        testLog.info("Listing schemas when none are registered");
        ListSchemasRequest listRequest = ListSchemasRequest.newBuilder().build(); // No filter
        ListSchemasResponse listResponse = client.listSchemas(listRequest);

        assertNotNull(listResponse);
        assertTrue(listResponse.getSchemasList().isEmpty(), "Schema list should be empty");
    }

    @Test
    void listSchemas_withData() {
        String schemaId1 = "list-schema-1-" + System.currentTimeMillis();
        String schemaContent1 = "{\"type\": \"integer\"}";
        String schemaId2 = "list-schema-2-" + System.currentTimeMillis();
        String schemaContent2 = "{\"type\": \"boolean\"}";

        RegisterSchemaResponse register1Response = client.registerSchema(RegisterSchemaRequest.newBuilder().setSchemaId(schemaId1).setSchemaContent(schemaContent1).build());
        assertTrue(register1Response.getSuccess(), "First registration should be successful");
        RegisterSchemaResponse register2Response = client.registerSchema(RegisterSchemaRequest.newBuilder().setSchemaId(schemaId2).setSchemaContent(schemaContent2).build());
        assertTrue(register2Response.getSuccess(), "Second registration should be successful");

        testLog.info("Listing schemas after registering two");
        ListSchemasRequest listRequest = ListSchemasRequest.newBuilder().build();
        ListSchemasResponse listResponse = client.listSchemas(listRequest);

        assertNotNull(listResponse);
        assertEquals(2, listResponse.getSchemasCount(), "Should find 2 schemas");

        List<String> foundIds = listResponse.getSchemasList().stream()
                .map(SchemaInfo::getSchemaId)
                .toList();
        assertTrue(foundIds.contains(schemaId1), "Should contain schemaId1");
        assertTrue(foundIds.contains(schemaId2), "Should contain schemaId2");

        // Optionally, check other fields if your listSchemas populates them
        // For example, if created_at is populated:
        listResponse.getSchemasList().forEach(schemaInfo -> {
            assertTrue(schemaInfo.hasCreatedAt(), "Listed schema should have created_at");
            assertTrue(schemaInfo.hasUpdatedAt(), "Listed schema should have updated_at");
            // Note: schema_content is typically not included in list views for performance
            assertTrue(schemaInfo.getSchemaContent().isEmpty(), "Schema content should be empty in list view by default");
        });
    }

    @Test
    void validateSchemaContent_valid() {
        String validSchemaContent = "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"number\"}}}";
        ValidateSchemaContentRequest request = ValidateSchemaContentRequest.newBuilder()
                .setSchemaContent(validSchemaContent)
                .build();

        testLog.info("Validating a valid schema content");
        ValidateSchemaContentResponse response = client.validateSchemaContent(request);

        assertNotNull(response);
        assertTrue(response.getIsValid(), "Schema content should be valid");
        assertTrue(response.getValidationErrorsList().isEmpty(), "There should be no validation errors for valid content");
    }

    @Test
    void validateSchemaContent_invalidJsonSyntax() {
        String invalidJson = "{\"type\": \"string\""; // Missing closing brace
        ValidateSchemaContentRequest request = ValidateSchemaContentRequest.newBuilder()
                .setSchemaContent(invalidJson)
                .build();

        testLog.info("Validating schema content with invalid JSON syntax");
        ValidateSchemaContentResponse response = client.validateSchemaContent(request);

        assertNotNull(response);
        assertFalse(response.getIsValid(), "Schema content should be invalid due to JSON syntax");
        assertFalse(response.getValidationErrorsList().isEmpty(), "Should have validation errors");
        assertTrue(response.getValidationErrors(0).contains("Invalid JSON syntax"), "Error message should indicate JSON syntax error");
    }

    // In SchemaRegistryServiceImplTest.java
    @Test
    void validateSchemaContent_invalidSchemaStructure() {
        String invalidSchema = "{\"type\": \"invalid_type_value\"}"; // Same invalid schema as the previous test
        ValidateSchemaContentRequest request = ValidateSchemaContentRequest.newBuilder()
                .setSchemaContent(invalidSchema)
                .build();

        testLog.info("Validating schema content with invalid JSON Schema structure");
        ValidateSchemaContentResponse response = client.validateSchemaContent(request);

        assertNotNull(response);
        assertFalse(response.getIsValid(), "Schema content should be invalid due to schema structure");
        assertFalse(response.getValidationErrorsList().isEmpty(), "Should have validation errors");

        // Corrected assertion: Check for the actual error from the validator
        String firstError = response.getValidationErrors(0);
        assertTrue(firstError.contains("does not have a value in the enumeration") || firstError.contains("string found, array expected"),
                "Error message should indicate the specific schema structure error (e.g., enum mismatch for 'type'). Actual: " + firstError);
    }


    @Test
    void registerSchema_emptySchemaId() {
        String schemaContent = "{\"type\": \"string\"}";
        RegisterSchemaRequest request = RegisterSchemaRequest.newBuilder()
                .setSchemaId("") // Empty schema ID
                .setSchemaContent(schemaContent)
                .build();

        testLog.info("Attempting to register schema with empty ID");
        // Expecting the service to return a response with success=false and validation errors
        // or a gRPC INVALID_ARGUMENT status, depending on server implementation for this.
        // Based on previous fixes, it should be a success=false response.
        RegisterSchemaResponse response = client.registerSchema(request);

        assertNotNull(response);
        assertFalse(response.getSuccess(), "Registration should fail for empty schema ID");
        assertFalse(response.getValidationErrorsList().isEmpty(), "Should have validation errors for empty schema ID");
        assertTrue(response.getValidationErrors(0).toLowerCase().contains("schema id and content cannot be empty") ||
                        response.getValidationErrors(0).toLowerCase().contains("schema id cannot be empty"), // Delegate might throw this
                "Error message should indicate empty schema ID. Actual: " + response.getValidationErrors(0));
    }

    @Test
    void registerSchema_updateExisting() {
        String schemaId = "update-test-" + System.currentTimeMillis();
        String initialContent = "{\"type\": \"string\", \"description\": \"Initial version\"}";
        String updatedContent = "{\"type\": \"string\", \"description\": \"Updated version\"}";

        // 1. Register initial version
        RegisterSchemaResponse r1 = client.registerSchema(RegisterSchemaRequest.newBuilder().setSchemaId(schemaId).setSchemaContent(initialContent).build());
        assertTrue(r1.getSuccess(), "Initial registration failed");

        // 2. Get and verify initial version
        GetSchemaResponse g1 = client.getSchema(GetSchemaRequest.newBuilder().setSchemaId(schemaId).build());
        assertEquals(initialContent, g1.getSchemaInfo().getSchemaContent());
        // Consider checking created_at vs updated_at if your service populates them distinctly on create vs update

        // 3. Register updated version
        testLog.info("Updating schema: {}", schemaId);
        RegisterSchemaResponse r2 = client.registerSchema(RegisterSchemaRequest.newBuilder().setSchemaId(schemaId).setSchemaContent(updatedContent).build());
        assertTrue(r2.getSuccess(), "Update registration failed");
        assertEquals(schemaId, r2.getSchemaId());

        // 4. Get and verify updated version
        GetSchemaResponse g2 = client.getSchema(GetSchemaRequest.newBuilder().setSchemaId(schemaId).build());
        assertEquals(updatedContent, g2.getSchemaInfo().getSchemaContent());
        // Optionally, verify that updated_at timestamp has changed if your service handles this.
    }

    @Test
    void deleteSchema_nonExistent() {
        String nonExistentSchemaId = "non-existent-delete-" + System.currentTimeMillis();
        DeleteSchemaRequest deleteRequest = DeleteSchemaRequest.newBuilder().setSchemaId(nonExistentSchemaId).build();

        testLog.info("Attempting to delete non-existent schema: {}", nonExistentSchemaId);

        // Test that deleting a non-existent schema returns NOT_FOUND
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.deleteSchema(deleteRequest);
        });
        assertEquals(io.grpc.Status.Code.NOT_FOUND, exception.getStatus().getCode());
        assertNotNull(exception.getStatus().getDescription());
        assertTrue(exception.getStatus().getDescription().contains(nonExistentSchemaId));
    }

    // In SchemaRegistryServiceImplTest.java
    @Test
    void validateSchemaContent_validJsonButPotentiallyUnusableSchemaStructure() { // Renamed for clarity
        String schemaWithUnknownType = "{\"type\": \"invalid_type_value\"}";
        ValidateSchemaContentRequest request = ValidateSchemaContentRequest.newBuilder()
                .setSchemaContent(schemaWithUnknownType)
                .build();

        testLog.info("Validating schema content with a non-standard type value");
        ValidateSchemaContentResponse response = client.validateSchemaContent(request);

        assertNotNull(response);
        // Corrected assertions:
        assertFalse(response.getIsValid(), "Schema content with a non-standard 'type' value should be considered invalid by meta-schema validation.");
        assertFalse(response.getValidationErrorsList().isEmpty(), "Should have validation errors for a non-standard 'type' value.");
        // Check for the specific error message from the networknt library
        assertTrue(response.getValidationErrors(0).contains("does not have a value in the enumeration"),
                "Error message should indicate the 'type' value is not in the allowed enumeration. Actual: " + response.getValidationErrors(0));
    }


}