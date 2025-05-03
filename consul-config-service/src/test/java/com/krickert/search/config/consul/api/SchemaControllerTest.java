package com.krickert.search.config.consul.api;

// Keep imports...
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// @Testcontainers // May not be needed if ConsulTestContainer handles lifecycle
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaControllerTest implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(SchemaControllerTest.class);
    private static final String API_BASE_PATH = "/api/schemas";
    ConsulTestContainer consulContainer = ConsulTestContainer.getInstance();
    @Inject @Client("/") HttpClient client;
    @Inject ObjectMapper objectMapper;
    @Inject ConsulKvService consulKvService;

    private static final Argument<Map<String, Object>> MAP_TYPE_ARGUMENT = Argument.mapOf(String.class, Object.class);

    private final String testServiceName = "my.test.Service";
    private final String testServiceName2 = "another.Service";
    // Modify this constant in SchemaControllerTest.java around line 50
    // In SchemaControllerTest.java
    private static final String validSchemaJson = """
    {
      "type": "object",
      "properties": {
        "host": {
          "type": "string"
        },
        "port": {
          "type": "integer"
        },
        "enabled": {
          "type": "boolean"
        }
      },
      "required": ["host", "port"]
    }
    """;
    private final String invalidSchemaJson = "{ this is not valid json }";
    private final Map<String, Object> validConfig = Map.of("port", 8080, "host", "localhost", "enabled", true);
    private final Map<String, Object> invalidConfigMissingRequired = Map.of("port", 9090);
    private final Map<String, Object> invalidConfigWrongType = Map.of("port", "not-a-number", "host", "localhost");

    @BeforeAll
    void setupAll() {
        if (consulContainer == null || !consulContainer.getContainer().isRunning()) {
            System.err.println("Warning: Consul container might not be running for tests.");
        }
        assertTrue(consulContainer.getContainer().isRunning(), "Consul container should be running");
        clearConsulSchemas();
    }

    private void clearConsulSchemas() {
        String schemaPrefix = "config/pipeline/schemas/";
        consulKvService.deleteKeysWithPrefix(schemaPrefix).block(); // Okay to block in test setup
        System.out.println("Cleared keys with prefix: " + schemaPrefix);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            fail("Failed to serialize object to JSON", e);
            return null;
        }
    }

    private Optional<String> getSchemaDirectlyFromConsul(String serviceName) {
        String key = "config/pipeline/schemas/" + serviceName.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
        // Okay to block in test verification helper
        return consulKvService.getValue(key).blockOptional()
                .flatMap(encodedOpt -> encodedOpt.map(encoded ->
                        new String(Base64.getDecoder().decode(encoded))
                ));
    }

    // --- Test Cases ---

    @Test @Order(1) @DisplayName("PUT /api/schemas/{serviceName} - Create New Schema - Success")
    void testCreateSchemaSuccess() {
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName, validSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        Map<String, Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals("Schema saved successfully for " + testServiceName, body.get("message"));
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent());
        assertEquals(validSchemaJson.replaceAll("\\s", ""), getSchemaDirectlyFromConsul(testServiceName).get().replaceAll("\\s", ""));
    }

    @Test @Order(2) @DisplayName("PUT /api/schemas/{serviceName} - Update Existing Schema - Success")
    void testUpdateSchemaSuccess() {
        // Ensure schema exists
        client.toBlocking().exchange(HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName, validSchemaJson).contentType(MediaType.APPLICATION_JSON_TYPE), MAP_TYPE_ARGUMENT);
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent());

        String updatedSchemaJson = """
            {
              "type": "object",
              "properties": { "timeout": { "type": "integer" } }
            }
            """;
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName, updatedSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        assertEquals("Schema saved successfully for " + testServiceName, response.getBody(MAP_TYPE_ARGUMENT).get().get("message"));
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent());
        assertEquals(updatedSchemaJson.replaceAll("\\s", ""), getSchemaDirectlyFromConsul(testServiceName).get().replaceAll("\\s", ""));
    }

    @Test @Order(3) @DisplayName("PUT /api/schemas/{serviceName} - Invalid JSON Body - Bad Request")
    void testCreateSchemaInvalidJson() {
        // --- Use unique name ---
        String uniqueServiceName = testServiceName + "-invalid-" + UUID.randomUUID();
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + uniqueServiceName, invalidSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.STRING); // Use STRING for error body checking
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String, Object> errorBody = bodyOpt.get();
        assertTrue(((String) errorBody.get("message")).contains("Schema definition is not valid JSON."),
                "Error message should indicate invalid JSON format");
    }

    // Inside testCreateSchemaEmptyJson() in SchemaControllerTest.java
    @Test @Order(4) @DisplayName("PUT /api/schemas/{serviceName} - Empty JSON Body - Bad Request")
    void testCreateSchemaEmptyJson() {
        String serviceNameForEmptyTest = SERVICE_NAME + "-empty-" + UUID.randomUUID();

        HttpRequest<?> request = HttpRequest.PUT("/api/schemas/" + serviceNameForEmptyTest, "") // Send empty string
                .contentType(MediaType.APPLICATION_JSON);

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, String.class); // Expecting failure
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        // Expecting Micronaut's default VndError structure for required body validation
        Optional<Map<String, Object>> body = exception.getResponse().getBody(Argument.mapOf(String.class, Object.class));
        assertTrue(body.isPresent(), "Response body should be present");

        // --- ADJUST ASSERTION TO CHECK MICRONAUT'S DEFAULT ERROR ---
        // Extract the nested error message
        String actualErrorMessage = Optional.ofNullable(body.get().get("_embedded"))
                .filter(Map.class::isInstance).map(Map.class::cast)
                .map(embedded -> embedded.get("errors"))
                .filter(List.class::isInstance).map(List.class::cast)
                .filter(list -> !list.isEmpty()).map(list -> list.get(0))
                .filter(Map.class::isInstance).map(Map.class::cast)
                .map(errorMap -> (String) errorMap.get("message"))
                .orElse("Nested error message not found"); // Default if structure is wrong

        log.debug("Received error body: {}", body.get()); // Log the whole body for inspection
        log.debug("Extracted nested error message: {}", actualErrorMessage);

        // Assert based on the expected framework message
        assertTrue(actualErrorMessage.contains("Required Body") && actualErrorMessage.contains("not specified"),
                "Error message should indicate required body not specified. Actual: " + actualErrorMessage);
    }

    @Test @Order(10) @DisplayName("GET /api/schemas/{serviceName} - Get Existing Schema - Success")
    void testGetSchemaSuccess() {
        String schemaContent = "{\"type\": \"string\"}";
        // Ensure schema exists
        client.toBlocking().exchange(HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName2, schemaContent).contentType(MediaType.APPLICATION_JSON_TYPE), Argument.VOID);
        assertTrue(getSchemaDirectlyFromConsul(testServiceName2).isPresent());

        HttpRequest<?> getRequest = HttpRequest.GET(API_BASE_PATH + "/" + testServiceName2);
        // Expecting JSON string as body, but use Object and cast later if needed, or directly String
        HttpResponse<String> response = client.toBlocking().exchange(getRequest, Argument.STRING);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(Argument.STRING).isPresent());
        assertEquals(schemaContent, response.getBody(Argument.STRING).get());
    }

    @Test @Order(11) @DisplayName("GET /api/schemas/{serviceName} - Get Non-Existent Schema - Not Found")
    void testGetSchemaNotFound() {
        String nonExistentService = "non-existent-service-get." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(nonExistentService).isPresent());

        HttpRequest<?> request = HttpRequest.GET(API_BASE_PATH + "/" + nonExistentService);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.STRING); // Check error body
        });

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String, Object> errorBody = bodyOpt.get();
        assertEquals("Not Found", errorBody.get("error"));
        assertTrue(((String) errorBody.get("message")).contains("Schema not found for service implementation: " + nonExistentService));
    }

    @Test @Order(20) @DisplayName("POST /api/schemas/{serviceName}/validate - Valid Config - Success")
    void testValidateConfigSuccess() {
        // Ensure schema exists from previous test (or recreate here)
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent());

        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", toJson(validConfig))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        Map<String, Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals(true, body.get("valid"));
        assertTrue(((Collection<?>) body.get("messages")).isEmpty());
    }

    @Test @Order(21) @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid Config (Missing Required) - Bad Request")
    void testValidateConfigInvalidMissing() {
        // --- ADD THIS BLOCK ---

        // Ensure the schema exists before validating against it
        HttpRequest<?> createRequest = HttpRequest.PUT("/api/schemas/" + SERVICE_NAME, validSchemaJson) // Use SERVICE_NAME constant
                .contentType(MediaType.APPLICATION_JSON);
        HttpResponse<String> createResponse = client.toBlocking().exchange(createRequest, String.class);
        assertEquals(HttpStatus.OK, createResponse.getStatus()); // Or CREATED if that's what your PUT returns on success
        // --- END ADDED BLOCK ---

        Map<String, Object> invalidConfig = Map.of("port", 9090); // Missing host
        HttpRequest<?> validateRequest = HttpRequest.POST("/api/schemas/" + SERVICE_NAME + "/validate", invalidConfig)
                .contentType(MediaType.APPLICATION_JSON);

        // This assertion should now pass
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(validateRequest);
        });
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        // Optional: Add assertion for response body content
        Optional<Map> body = exception.getResponse().getBody(Map.class);
        assertTrue(body.isPresent());
        // Add more specific checks for the error message if desired
    }

    private static final String SERVICE_NAME = "my.test.Service";
    @Test @Order(22) @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid Config (Wrong Type) - Bad Request")
    // Inside testValidateConfigInvalidType() in SchemaControllerTest.java
    void testValidateConfigInvalidType() {
        // --- ADD THIS BLOCK ---
        // Ensure the schema exists before validating against it
        HttpRequest<?> createRequest = HttpRequest.PUT("/api/schemas/" + SERVICE_NAME, validSchemaJson) // Use SERVICE_NAME constant
                .contentType(MediaType.APPLICATION_JSON);
        HttpResponse<String> createResponse = client.toBlocking().exchange(createRequest, String.class);
        assertEquals(HttpStatus.OK, createResponse.getStatus()); // Or CREATED
        // --- END ADDED BLOCK ---


        Map<String, Object> invalidConfig = Map.of(
                "host", "localhost",
                "port", "not-a-number" // Wrong type
        );
        HttpRequest<?> validateRequest = HttpRequest.POST("/api/schemas/" + SERVICE_NAME + "/validate", invalidConfig)
                .contentType(MediaType.APPLICATION_JSON);

        // This assertion should now pass
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(validateRequest);
        });
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        // Optional: Add assertion for response body content
        Optional<Map> body = exception.getResponse().getBody(Map.class);
        assertTrue(body.isPresent());
    }

    @Test @Order(23) @DisplayName("POST /api/schemas/{serviceName}/validate - No Schema Registered - Success (Implicit Pass)")
    void testValidateConfigNoSchema() {
        String serviceWithoutSchema = "no.schema.service." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(serviceWithoutSchema).isPresent());

        Map<String, Object> anyConfig = Map.of("any", "value", "number", 123);
        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + serviceWithoutSchema + "/validate", toJson(anyConfig))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        // Expecting OK response
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        Map<String, Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals(true, body.get("valid"));
        assertTrue(((Collection<?>) body.get("messages")).isEmpty());
    }

    @Test @Order(24) @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid JSON Request Body - Bad Request")
    void testValidateConfigInvalidRequestBody() {
        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", invalidSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        // Invalid JSON in the body is handled by Micronaut before controller
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String, Object> errorBody = bodyOpt.get();
        assertTrue(errorBody.containsKey("message"));
        String message = (String) errorBody.get("message");
        assertTrue(message.contains("Error decoding JSON body") || message.contains("Invalid JSON") || message.contains("Failed to decode") || message.contains("required request body is missing"), "Error message mismatch for invalid JSON body: "+message);
    }

    @Test @Order(30) @DisplayName("DELETE /api/schemas/{serviceName} - Delete Existing Schema - No Content")
    void testDeleteSchemaSuccess() {
        // Ensure schema exists (re-using testServiceName2)
        client.toBlocking().exchange(HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName2, "{\"type\":\"string\"}").contentType(MediaType.APPLICATION_JSON_TYPE), Argument.VOID);
        assertTrue(getSchemaDirectlyFromConsul(testServiceName2).isPresent());

        HttpRequest<?> request = HttpRequest.DELETE(API_BASE_PATH + "/" + testServiceName2);
        // Delete returns Mono<Void>, results in 204 No Content on success
        HttpResponse<Void> response = client.toBlocking().exchange(request, Argument.VOID);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        assertFalse(response.getBody().isPresent());
        assertFalse(getSchemaDirectlyFromConsul(testServiceName2).isPresent());
    }

    @Test @Order(31) @DisplayName("DELETE /api/schemas/{serviceName} - Delete Non-Existent Schema - Not Found")
    void testDeleteSchemaNotFound() {
        String nonExistent = "non-existent-to-delete." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(nonExistent).isPresent());

        HttpRequest<?> request = HttpRequest.DELETE(API_BASE_PATH + "/" + nonExistent);

        // Service signals SchemaNotFoundException, handler returns 404
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.VOID); // Check error response
        }, "Deleting a non-existent schema should throw HttpClientResponseException (404)");

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String, Object> errorBody = bodyOpt.get();
        assertEquals("Not Found", errorBody.get("error"));
        assertTrue(((String) errorBody.get("message")).contains("Schema not found for service implementation: " + nonExistent));
    }


    @Override @NonNull
    public Map<String, String> getProperties() {
        if (consulContainer == null) {
            System.err.println("ConsulTestContainer is null in getProperties");
            return Collections.emptyMap();
        }
        if (!consulContainer.getContainer().isRunning()) {
            consulContainer.getContainer().start();
        }
        return consulContainer.getProperties();
    }
}