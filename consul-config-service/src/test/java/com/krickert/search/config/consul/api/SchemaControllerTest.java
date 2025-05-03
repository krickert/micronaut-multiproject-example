package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument; // <-- Import Micronaut Argument
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
import org.testcontainers.junit.jupiter.Testcontainers; // Keep if ConsulTestContainer uses it

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// @Testcontainers // May not be needed if ConsulTestContainer handles lifecycle
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaControllerTest implements TestPropertyProvider {

    private static final String API_BASE_PATH = "/api/schemas";

    // Assuming getInstance provides a singleton running container managed elsewhere
    ConsulTestContainer consulContainer = ConsulTestContainer.getInstance();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ConsulKvService consulKvService;

    // *** Use Micronaut Argument for Map<String, Object> ***
    private static final Argument<Map<String, Object>> MAP_TYPE_ARGUMENT = Argument.mapOf(String.class, Object.class);

    // --- Test Data ---
    private final String testServiceName = "my.test.Service";
    private final String testServiceName2 = "another.Service";
    private final String validSchemaJson = """
            {
              "type": "object",
              "properties": {
                "port": { "type": "integer", "minimum": 8000 },
                "host": { "type": "string", "format": "hostname" },
                "enabled": { "type": "boolean" }
              },
              "required": ["port", "host"]
            }
            """;
    private final String invalidSchemaJson = "{ this is not valid json }";
    // These maps now correctly use Integer/Boolean types
    private final Map<String, Object> validConfig = Map.of("port", 8080, "host", "localhost", "enabled", true);
    private final Map<String, Object> invalidConfigMissingRequired = Map.of("port", 9090);
    // Note: "not-a-number" *is* a string, the schema expects integer. This map is valid Map<String, Object>
    private final Map<String, Object> invalidConfigWrongType = Map.of("port", "not-a-number", "host", "localhost");


    @BeforeAll
    void setupAll() {
        if(consulContainer == null || !consulContainer.getContainer().isRunning()) {
            System.err.println("Warning: Consul container might not be running for tests.");
        }
        assertTrue(consulContainer.getContainer().isRunning(), "Consul container should be running");
        clearConsulSchemas();
    }

    private void clearConsulSchemas() {
        // Use exact prefix used by SchemaService
        String schemaPrefix = "config/pipeline/schemas/";
        consulKvService.deleteKeysWithPrefix(schemaPrefix).block();
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
        // Use exact key used by SchemaService
        String key = "config/pipeline/schemas/" + serviceName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return consulKvService.getValue(key).blockOptional()
                .flatMap(encodedOpt -> encodedOpt.map(encoded ->
                        new String(Base64.getDecoder().decode(encoded))
                ));
    }


    // --- Test Cases ---

    @Test
    @Order(1)
    @DisplayName("PUT /api/schemas/{serviceName} - Create New Schema - Success")
    void testCreateSchemaSuccess() {
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName, validSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        // Use exchange with the Micronaut Argument type
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        // *** Use Argument with getBody() on the success response ***
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent(), "Response body should be present");
        Map<String,Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals("Schema saved successfully for " + testServiceName, body.get("message"));

        // Verify directly in Consul
        Optional<String> storedSchema = getSchemaDirectlyFromConsul(testServiceName);
        assertTrue(storedSchema.isPresent(), "Schema should be stored in Consul after PUT");
        assertEquals(validSchemaJson.replaceAll("\\s", ""), storedSchema.get().replaceAll("\\s", ""));
    }

    @Test
    @Order(2)
    @DisplayName("PUT /api/schemas/{serviceName} - Update Existing Schema - Success")
    void testUpdateSchemaSuccess() {
        // Ensure schema exists
        // testCreateSchemaSuccess(); // Relying on order might be brittle, safer to create here
        HttpRequest<?> createRequest = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName, validSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        client.toBlocking().exchange(createRequest, MAP_TYPE_ARGUMENT);
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent(), "Schema must exist before update test");


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

        // Verify update in Consul
        Optional<String> storedSchema = getSchemaDirectlyFromConsul(testServiceName);
        assertTrue(storedSchema.isPresent(), "Updated schema should be stored in Consul");
        assertEquals(updatedSchemaJson.replaceAll("\\s", ""), storedSchema.get().replaceAll("\\s", ""));
    }

    @Test
    @Order(3)
    @DisplayName("PUT /api/schemas/{serviceName} - Invalid JSON Body - Bad Request")
    void testCreateSchemaInvalidJson() {
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName + "-invalid", invalidSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.STRING);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        // *** Use Argument with getBody on the exception's response ***
        Optional<Map<String, Object>> body = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(body.isPresent());
        assertTrue(((String) body.get().get("message")).contains("Schema definition is not valid JSON"));
    }

    @Test
    @Order(4)
    @DisplayName("PUT /api/schemas/{serviceName} - Empty JSON Body - Bad Request")
    void testCreateSchemaEmptyJson() {
        HttpRequest<?> request = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName + "-empty", "")
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.STRING);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        // Check status is usually sufficient for input validation errors
        // Optional: check body if handler guarantees it and parsing works
        Optional<Map<String, Object>> body = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(body.isPresent());
        assertTrue(((String) body.get().get("message")).contains("Service implementation name and schema JSON cannot be empty"));
    }


    @Test
    @Order(10)
    @DisplayName("GET /api/schemas/{serviceName} - Get Existing Schema - Success")
    void testGetSchemaSuccess() {
        String schemaContent = "{\"type\": \"string\"}";
        HttpRequest<?> putRequest = HttpRequest.PUT(API_BASE_PATH + "/" + testServiceName2, schemaContent)
                .contentType(MediaType.APPLICATION_JSON_TYPE);
        client.toBlocking().exchange(putRequest, MAP_TYPE_ARGUMENT);
        assertTrue(getSchemaDirectlyFromConsul(testServiceName2).isPresent(), "Schema should exist before GET");


        HttpRequest<?> getRequest = HttpRequest.GET(API_BASE_PATH + "/" + testServiceName2);
        HttpResponse<String> response = client.toBlocking().exchange(getRequest, Argument.STRING);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(Argument.STRING).isPresent());
        assertEquals(schemaContent, response.getBody(Argument.STRING).get());
    }

    @Test
    @Order(11)
    @DisplayName("GET /api/schemas/{serviceName} - Get Non-Existent Schema - Not Found")
    void testGetSchemaNotFound() {
        String nonExistentService = "non-existent-service-get." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(nonExistentService).isPresent());

        HttpRequest<?> request = HttpRequest.GET(API_BASE_PATH + "/" + nonExistentService);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.STRING);
        });

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        Optional<Map<String, Object>> body = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(body.isPresent());
        assertEquals("Not Found", body.get().get("error")); // Correct expected value
        assertTrue(((String) body.get().get("message")).contains("Schema not found for service implementation: " + nonExistentService));
    }

    @Test
    @Order(20)
    @DisplayName("POST /api/schemas/{serviceName}/validate - Valid Config - Success")
    void testValidateConfigSuccess() {
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent(), "Schema should exist for validation");

        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", toJson(validConfig))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        Map<String,Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals(true, body.get("valid"));
        assertTrue(((Collection<?>) body.get("messages")).isEmpty());
    }

    @Test
    @Order(21)
    @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid Config (Missing Required) - Bad Request")
    void testValidateConfigInvalidMissing() {
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent(), "Schema should exist for validation");

        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", toJson(invalidConfigMissingRequired))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String,Object> body = bodyOpt.get();
        assertEquals(false, body.get("valid"));
        Collection<?> messages = (Collection<?>) body.get("messages");
        assertNotNull(messages);
        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().anyMatch(msg -> ((String)msg).contains("required property 'host' not found")));
    }

    @Test
    @Order(22)
    @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid Config (Wrong Type) - Bad Request")
    void testValidateConfigInvalidType() {
        assertTrue(getSchemaDirectlyFromConsul(testServiceName).isPresent(), "Schema should exist for validation");

        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", toJson(invalidConfigWrongType))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        Map<String,Object> body = bodyOpt.get();
        assertEquals(false, body.get("valid"));
        Collection<?> messages = (Collection<?>) body.get("messages");
        assertNotNull(messages);
        assertFalse(messages.isEmpty());
        // Check specific message from json-schema-validator
        assertTrue(messages.stream().anyMatch(msg -> ((String)msg).contains("instance type (string) does not match any allowed primitive type")));
    }

    @Test
    @Order(23)
    @DisplayName("POST /api/schemas/{serviceName}/validate - No Schema Registered - Success (Implicit Pass)")
    void testValidateConfigNoSchema() {
        String serviceWithoutSchema = "no.schema.service." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(serviceWithoutSchema).isPresent());

        Map<String, Object> anyConfig = Map.of("any", "value", "number", 123);
        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + serviceWithoutSchema + "/validate", toJson(anyConfig))
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertTrue(response.getBody(MAP_TYPE_ARGUMENT).isPresent());
        Map<String, Object> body = response.getBody(MAP_TYPE_ARGUMENT).get();
        assertEquals(true, body.get("valid"));
        assertTrue(((Collection<?>) body.get("messages")).isEmpty());
    }

    @Test
    @Order(24)
    @DisplayName("POST /api/schemas/{serviceName}/validate - Invalid JSON Request Body - Bad Request")
    void testValidateConfigInvalidRequestBody() {
        HttpRequest<?> request = HttpRequest.POST(API_BASE_PATH + "/" + testServiceName + "/validate", invalidSchemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE);

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);
        });

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        Optional<Map<String, Object>> bodyOpt = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(bodyOpt.isPresent());
        assertTrue(bodyOpt.get().containsKey("message"));
        String message = (String) bodyOpt.get().get("message");
        assertTrue(message.contains("Error decoding JSON body") || message.contains("Invalid JSON") || message.contains("Failed to decode"), "Error message mismatch: "+message);
    }


    @Test
    @Order(30)
    @DisplayName("DELETE /api/schemas/{serviceName} - Delete Existing Schema - No Content")
    void testDeleteSchemaSuccess() {
        // Ensure schema exists
        assertTrue(getSchemaDirectlyFromConsul(testServiceName2).isPresent(), "Schema should exist before delete");

        HttpRequest<?> request = HttpRequest.DELETE(API_BASE_PATH + "/" + testServiceName2);
        HttpResponse<Void> response = client.toBlocking().exchange(request, Argument.VOID);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        assertFalse(response.getBody().isPresent());

        // Verify directly in Consul
        assertFalse(getSchemaDirectlyFromConsul(testServiceName2).isPresent(), "Schema should be deleted from Consul");
    }

    @Test
    @Order(31)
    @DisplayName("DELETE /api/schemas/{serviceName} - Delete Non-Existent Schema - Not Found")
    void testDeleteSchemaNotFound() {
        String nonExistent = "non-existent-to-delete." + UUID.randomUUID();
        assertFalse(getSchemaDirectlyFromConsul(nonExistent).isPresent());

        HttpRequest<?> request = HttpRequest.DELETE(API_BASE_PATH + "/" + nonExistent);

        // Assuming Service logic is fixed to signal SchemaNotFoundException
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(request, Argument.VOID);
        }, "Deleting a non-existent schema should throw HttpClientResponseException (404)");

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        Optional<Map<String, Object>> body = e.getResponse().getBody(MAP_TYPE_ARGUMENT);
        assertTrue(body.isPresent());
        assertEquals("Not Found", body.get().get("error")); // Correct assertion
    }


    @Override
    @NonNull
    public Map<String, String> getProperties() {
        if(consulContainer == null) {
            // Handle case where injection might fail or container is not setup
            System.err.println("ConsulTestContainer is null in getProperties");
            return Collections.emptyMap();
        }
        if (!consulContainer.getContainer().isRunning()) {
            consulContainer.getContainer().start();
        }
        return consulContainer.getProperties();
    }
}