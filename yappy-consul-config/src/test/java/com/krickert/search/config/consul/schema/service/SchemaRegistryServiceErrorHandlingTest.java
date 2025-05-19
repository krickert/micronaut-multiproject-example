package com.krickert.search.config.consul.schema.service;

import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.consul.schema.exception.SchemaDeleteException;
import com.krickert.search.schema.registry.*;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

@MicronautTest // Starts an embedded Micronaut server with your gRPC service
@Property(name = "grpc.client.plaintext", value = "true")
// You might not need ConsulKvService or the @BeforeEach cleanup
// if all delegate interactions are mocked.
class SchemaRegistryServiceErrorHandlingTest {

    private final String TEST_SCHEMA_ID = "test-error-schema";
    private final String TEST_SCHEMA_CONTENT = "{\"type\":\"string\"}";
    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub client;
    @Inject
    ConsulSchemaRegistryDelegate delegate; // This injected instance will be the mock

    // This will replace the actual ConsulSchemaRegistryDelegate bean with a mock
    @MockBean(ConsulSchemaRegistryDelegate.class)
    ConsulSchemaRegistryDelegate mockDelegate() {
        return Mockito.mock(ConsulSchemaRegistryDelegate.class);
    }

    @Test
    void registerSchema_delegateThrowsRuntimeException_returnsSuccessFalseWithGenericError() {
        // Arrange: Configure the mock delegate to throw an unexpected RuntimeException
        Mockito.when(delegate.saveSchema(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Unexpected delegate error during save")));

        RegisterSchemaRequest request = RegisterSchemaRequest.newBuilder()
                .setSchemaId(TEST_SCHEMA_ID)
                .setSchemaContent(TEST_SCHEMA_CONTENT)
                .build();

        // Act
        RegisterSchemaResponse response = client.registerSchema(request);

        // Assert
        assertNotNull(response);
        assertFalse(response.getSuccess(), "Registration should fail when delegate throws an unexpected error.");
        assertEquals(TEST_SCHEMA_ID, response.getSchemaId());
        assertFalse(response.getValidationErrorsList().isEmpty(), "Should have a validation error message.");
        assertEquals("An unexpected error occurred during registration.", response.getValidationErrors(0),
                "Error message should be the generic one for unexpected delegate errors.");
        assertTrue(response.hasTimestamp());

        // Verify mock interaction (optional, but good practice)
        Mockito.verify(delegate).saveSchema(TEST_SCHEMA_ID, TEST_SCHEMA_CONTENT);
    }

    @Test
    void getSchema_delegateThrowsGenericRuntimeException_returnsStatusInternal() {
        // Arrange
        String schemaId = "schema-internal-error";
        Mockito.when(delegate.getSchemaContent(schemaId))
                .thenReturn(Mono.error(new RuntimeException("Delegate internal failure")));

        GetSchemaRequest request = GetSchemaRequest.newBuilder().setSchemaId(schemaId).build();

        // Act & Assert
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.getSchema(request);
        });

        assertEquals(io.grpc.Status.Code.INTERNAL, exception.getStatus().getCode());
        assertTrue(exception.getStatus().getDescription().contains("Internal error: Delegate internal failure"));

        // Verify mock interaction
        Mockito.verify(delegate).getSchemaContent(schemaId);
    }

    @Test
    void deleteSchema_delegateThrowsSchemaDeleteException_returnsStatusInternal() {
        // Arrange
        String schemaId = "schema-delete-fail";
        SchemaDeleteException schemaDeleteCause = new SchemaDeleteException("Delegate failed to delete from Consul", new RuntimeException("Consul communication error"));
        Mockito.when(delegate.deleteSchema(schemaId))
                .thenReturn(Mono.error(schemaDeleteCause));

        DeleteSchemaRequest request = DeleteSchemaRequest.newBuilder().setSchemaId(schemaId).build();

        // Act & Assert
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.deleteSchema(request);
        });

        assertEquals(io.grpc.Status.Code.INTERNAL, exception.getStatus().getCode());
        // The service implementation wraps the original message in "Internal error: "
        assertTrue(exception.getStatus().getDescription().contains("Internal error: Delegate failed to delete from Consul"),
                "Description was: " + exception.getStatus().getDescription());


        // Verify mock interaction
        Mockito.verify(delegate).deleteSchema(schemaId);
    }

    @Test
    void listSchemas_delegateThrowsError_returnsStatusInternal() {
        // Arrange
        Mockito.when(delegate.listSchemaIds())
                .thenReturn(Mono.error(new RuntimeException("Delegate list retrieval error")));

        ListSchemasRequest request = ListSchemasRequest.newBuilder().build();

        // Act & Assert
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            client.listSchemas(request);
        });

        assertEquals(io.grpc.Status.Code.INTERNAL, exception.getStatus().getCode());
        assertTrue(exception.getStatus().getDescription().contains("Error listing schemas: Delegate list retrieval error"));

        // Verify mock interaction
        Mockito.verify(delegate).listSchemaIds();
    }
}