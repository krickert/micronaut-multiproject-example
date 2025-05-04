package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.model.*; // Import relevant models
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
// import org.testcontainers.junit.jupiter.Testcontainers; // Not needed if using singleton pattern

import java.util.*;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PipelineController focusing on adding/updating services
 * with custom JSON configurations validated against schemas.
 * Assumes PipelineService integrates schema validation for JsonConfigOptions.
 */
@MicronautTest(transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PipelineCustomConfigControllerTest implements TestPropertyProvider {

    private static final Logger log = LoggerFactory.getLogger(PipelineCustomConfigControllerTest.class);
    private static final String PIPELINE_API_BASE_PATH = "/api/pipelines";
    private static final String SCHEMA_API_BASE_PATH = "/api/schemas";
    private static final Argument<Map<String, Object>> MAP_TYPE_ARGUMENT = Argument.mapOf(String.class, Object.class);

    // --- Test Data Constants ---
    private static final String TEST_PIPELINE_NAME = "custom-config-pipeline";
    private static final String TEST_SERVICE_IMPL = "com.example.MyCustomService"; // Example service implementation class
    private static final String TEST_SERVICE_NAME = "myCustomServiceInstance"; // Example instance name

    // Schema requiring 'host' (string) and 'port' (integer)
    private static final String VALID_SERVICE_SCHEMA_JSON = """
    {
      "type": "object",
      "properties": {
        "host": {
          "type": "string",
          "description": "Hostname for the custom service"
        },
        "port": {
          "type": "integer",
          "minimum": 1,
          "maximum": 65535
        },
        "retries": {
          "type": "integer",
          "default": 3
        }
      },
      "required": ["host", "port"]
    }
    """;

    // Config matching the schema
    private static final String VALID_CONFIG_JSON = """
    {
      "host": "service.example.com",
      "port": 9090
    }
    """;

    // Config missing the required 'host' field
    private static final String INVALID_CONFIG_MISSING_REQUIRED_JSON = """
    {
      "port": 9091
    }
    """;

    // Config with 'port' having the wrong type (string instead of integer)
    private static final String INVALID_CONFIG_WRONG_TYPE_JSON = """
    {
      "host": "service.example.com",
      "port": "not-a-port"
    }
    """;

    // Malformed JSON string
    private static final String MALFORMED_CONFIG_JSON = "{ \"host\": \"service.example.com\", ";


    // Use the singleton TestContainer instance
    static ConsulTestContainer consulContainer = ConsulTestContainer.getInstance();

    @Inject @Client("/") HttpClient client;
    @Inject ObjectMapper objectMapper; // Micronaut provides a configured ObjectMapper bean
    @Inject ConsulKvService consulKvService; // For direct Consul interaction/verification

    /**
     * Ensure Consul is running and clean up Consul state before all tests in this class.
     */
    @BeforeAll
    void setupAll() {
        cleanupConsul();
    }

    /**
     * Clean up Consul state before each individual test method.
     */
    @BeforeEach
    void setUp() {
        cleanupConsul(); // Ensure clean state for each test
    }

    /**
     * Helper method to clear pipeline configurations and schemas from Consul KV store.
     * Uses the injected ConsulKvService.
     */
    private void cleanupConsul() {
        // Define the base paths used by the application for pipelines and schemas
        String pipelinePrefix = "config/pipeline/pipeline.configs/"; // Adjust if your base path differs
        String schemaPrefix = "config/pipeline/schemas/";
        try {
            // Delete keys under the pipeline config prefix (blocking is acceptable in test setup)
            consulKvService.deleteKeysWithPrefix(pipelinePrefix).block();
            // Delete keys under the schema config prefix
            consulKvService.deleteKeysWithPrefix(schemaPrefix).block();
            log.debug("Cleared Consul keys with prefixes: {}, {}", pipelinePrefix, schemaPrefix);
        } catch (Exception e) {
            // Log error but don't fail the test setup if cleanup has issues (might happen if keys don't exist)
            log.error("Error during Consul cleanup (may be normal if keys didn't exist)", e);
        }
    }

     /**
      * Helper method to create a basic pipeline via the API.
      * @param pipelineName The name of the pipeline to create.
      * @return The created PipelineConfigDto.
      */
     private PipelineConfigDto createTestPipeline(String pipelineName) {
         CreatePipelineRequest createReq = new CreatePipelineRequest(pipelineName);
         // Make POST request to create the pipeline
         HttpResponse<PipelineConfigDto> response = client.toBlocking().exchange(
                 HttpRequest.POST(PIPELINE_API_BASE_PATH, createReq)
                         .contentType(MediaType.APPLICATION_JSON),
                 PipelineConfigDto.class // Expecting PipelineConfigDto in response body
         );
         // Assert that creation was successful (HTTP 201 Created)
         assertEquals(HttpStatus.CREATED, response.getStatus(), "Failed to create pipeline " + pipelineName);
         assertTrue(response.getBody().isPresent(), "Response body missing for created pipeline " + pipelineName);
         // Return the DTO from the response body
         return response.getBody().get();
     }

    /**
     * Helper method to PUT a schema into Consul via the Schema API.
     * @param serviceImpl The service implementation name (used as schema key).
     * @param schemaJson The JSON schema string to put.
     */
    private void putSchema(String serviceImpl, String schemaJson) {
        // Create PUT request for the schema
        HttpRequest<?> request = HttpRequest.PUT(SCHEMA_API_BASE_PATH + "/" + serviceImpl, schemaJson)
                .contentType(MediaType.APPLICATION_JSON_TYPE); // Use JSON content type
        // Make the request, expecting a Map as the success response body (based on SchemaController)
        HttpResponse<Map<String, Object>> response = client.toBlocking().exchange(request, MAP_TYPE_ARGUMENT);
        // Assert that the schema PUT was successful (HTTP 200 OK)
        assertEquals(HttpStatus.OK, response.getStatus(), "Failed to PUT schema for " + serviceImpl);
    }

    // --- Test Cases ---

    @Test @Order(1)
    @DisplayName("Update Pipeline: Add Service with Valid Custom JSON")
    void testAddServiceWithValidCustomConfig() throws JsonProcessingException { // Add exception for objectMapper
        // 1. Arrange: Create a pipeline and register the schema for the service
        PipelineConfigDto initialPipeline = createTestPipeline(TEST_PIPELINE_NAME);
        putSchema(TEST_SERVICE_IMPL, VALID_SERVICE_SCHEMA_JSON);

        // 2. Act: Prepare the update request DTO
        // Create the service configuration DTO
        PipeStepConfigurationDto customService = new PipeStepConfigurationDto();
        customService.setServiceImplementation(TEST_SERVICE_IMPL);
        customService.setName(TEST_SERVICE_NAME);
        // Create JsonConfigOptions with the valid config and schema strings
        JsonConfigOptions configOptions = new JsonConfigOptions(VALID_CONFIG_JSON, VALID_SERVICE_SCHEMA_JSON);
        // Set the JsonConfigOptions on the service DTO using the correct setter
        customService.setJsonConfig(configOptions);

        // Add the new service to the pipeline DTO obtained after creation
        initialPipeline.getServices().put(TEST_SERVICE_NAME, customService); // Use put for Map
        // Set the expected version for the optimistic locking check during PUT
        initialPipeline.setPipelineVersion(1); // Version should be 1 after creation

        // Create the PUT request with the updated pipeline DTO as the body
        HttpRequest<?> updateRequest = HttpRequest.PUT(PIPELINE_API_BASE_PATH + "/" + TEST_PIPELINE_NAME, initialPipeline)
                .contentType(MediaType.APPLICATION_JSON);

        // Execute the PUT request
        HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(updateRequest, PipelineConfigDto.class);

        // 3. Assert: Check the response status and content
        assertEquals(HttpStatus.OK, updateResponse.getStatus(), "Pipeline update should succeed");
        assertTrue(updateResponse.getBody().isPresent(), "Updated pipeline DTO should be present in response");
        PipelineConfigDto updatedPipeline = updateResponse.getBody().get();
        // Verify version incremented due to successful update
        assertEquals(2, updatedPipeline.getPipelineVersion(), "Pipeline version should increment");
        // Verify the service was added
        assertEquals(1, updatedPipeline.getServices().size(), "Pipeline should contain one service");
        assertTrue(updatedPipeline.getServices().containsKey(TEST_SERVICE_NAME), "Pipeline should contain the added service");

        PipeStepConfigurationDto retrievedService = updatedPipeline.getServices().get(TEST_SERVICE_NAME);
        assertEquals(TEST_SERVICE_IMPL, retrievedService.getServiceImplementation());
        assertEquals(TEST_SERVICE_NAME, retrievedService.getName());
        // Verify the JsonConfigOptions were stored and retrieved correctly
        assertNotNull(retrievedService.getJsonConfig(), "Retrieved service should have jsonConfig");
        // Compare JSON content ignoring formatting differences
        assertEquals(objectMapper.readTree(VALID_CONFIG_JSON), objectMapper.readTree(retrievedService.getJsonConfig().getJsonConfig()), "Stored jsonConfig differs");
        assertEquals(objectMapper.readTree(VALID_SERVICE_SCHEMA_JSON), objectMapper.readTree(retrievedService.getJsonConfig().getJsonSchema()), "Stored jsonSchema differs");
    }

    @Test @Order(2)
    @DisplayName("Update Pipeline: Add Service with Invalid Custom JSON (Missing Required)")
    void testAddServiceWithInvalidCustomConfig_MissingRequired() {
        // 1. Arrange: Create pipeline and register the schema
        PipelineConfigDto initialPipeline = createTestPipeline(TEST_PIPELINE_NAME);
        putSchema(TEST_SERVICE_IMPL, VALID_SERVICE_SCHEMA_JSON);

        // 2. Act: Prepare update DTO with a service having invalid JSON config
        PipeStepConfigurationDto customService = new PipeStepConfigurationDto();
        customService.setServiceImplementation(TEST_SERVICE_IMPL);
        customService.setName(TEST_SERVICE_NAME);
        // Use the JSON config that violates the schema (missing 'host')
        JsonConfigOptions configOptions = new JsonConfigOptions(INVALID_CONFIG_MISSING_REQUIRED_JSON, VALID_SERVICE_SCHEMA_JSON);
        customService.setJsonConfig(configOptions); // Use correct setter

        initialPipeline.getServices().put(TEST_SERVICE_NAME, customService); // Use put for Map
        initialPipeline.setPipelineVersion(1); // Set expected version

        HttpRequest<?> updateRequest = HttpRequest.PUT(PIPELINE_API_BASE_PATH + "/" + TEST_PIPELINE_NAME, initialPipeline)
                .contentType(MediaType.APPLICATION_JSON);

        // Inside testAddServiceWithInvalidCustomConfig_MissingRequired()
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(updateRequest, PipelineConfigDto.class);
        }, "Update with invalid config (missing required) should fail");
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), "Status should be 400 Bad Request");

        Optional<String> bodyOpt = exception.getResponse().getBody(String.class);
        assertTrue(bodyOpt.isPresent(), "Error response body should be present as string");
        String errorBodyString = bodyOpt.get();
        log.debug("Raw error response body for MissingRequired: {}", errorBodyString);

        // Check raw string contains specific error (restoring this check)
        assertTrue(errorBodyString.contains("\"valid\":false") &&
                        errorBodyString.contains("required property 'host' not found"),
                "Error body string should contain validation failure details for missing host. Actual: " + errorBodyString);
    }

     @Test @Order(3)
     @DisplayName("Update Pipeline: Add Service with Invalid Custom JSON (Wrong Type)")
     void testAddServiceWithInvalidCustomConfig_WrongType() {
         // 1. Arrange: Create pipeline and register the schema
         PipelineConfigDto initialPipeline = createTestPipeline(TEST_PIPELINE_NAME);
         putSchema(TEST_SERVICE_IMPL, VALID_SERVICE_SCHEMA_JSON);

         // 2. Act: Prepare update DTO with service having JSON config with incorrect type
         PipeStepConfigurationDto customService = new PipeStepConfigurationDto();
         customService.setServiceImplementation(TEST_SERVICE_IMPL);
         customService.setName(TEST_SERVICE_NAME);
         // Use the JSON config that violates the schema (port is string, not integer)
         JsonConfigOptions configOptions = new JsonConfigOptions(INVALID_CONFIG_WRONG_TYPE_JSON, VALID_SERVICE_SCHEMA_JSON);
         customService.setJsonConfig(configOptions); // Use correct setter

         initialPipeline.getServices().put(TEST_SERVICE_NAME, customService); // Use put for Map
         initialPipeline.setPipelineVersion(1); // Set expected version

         HttpRequest<?> updateRequest = HttpRequest.PUT(PIPELINE_API_BASE_PATH + "/" + TEST_PIPELINE_NAME, initialPipeline)
                 .contentType(MediaType.APPLICATION_JSON);
         HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
             client.toBlocking().exchange(updateRequest, PipelineConfigDto.class); // Expect exception
         }, "Update with invalid config (wrong type) should fail");

         assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), "Status should be 400 Bad Request");

         // --- NEW: Assert on the raw response body string ---
         Optional<String> bodyOpt = exception.getResponse().getBody(String.class);
         assertTrue(bodyOpt.isPresent(), "Error response body should be present as string");
         String errorBodyString = bodyOpt.get();
         log.debug("Raw error response body for WrongType: {}", errorBodyString);

         // Check if the raw string contains the key parts of the expected message
         assertTrue(errorBodyString.contains("\"valid\":false") &&
                         errorBodyString.contains("\"$.port: string found, integer expected\""),
                 "Error body string should contain validation failure details for missing host. Actual: " + errorBodyString);
         // --- END NEW ASSERTION ---
     }

     @Test @Order(4)
     @DisplayName("Update Pipeline: Add Service with Malformed Custom JSON String")
     void testAddServiceWithMalformedCustomConfigJson() {
         // 1. Arrange: Create pipeline. Schema doesn't need to exist for this test.
         PipelineConfigDto initialPipeline = createTestPipeline(TEST_PIPELINE_NAME);

         // 2. Act: Prepare update DTO with service having a malformed JSON config string
         PipeStepConfigurationDto customService = new PipeStepConfigurationDto();
         customService.setServiceImplementation(TEST_SERVICE_IMPL);
         customService.setName(TEST_SERVICE_NAME);
         // Use the malformed JSON string. Schema content is irrelevant here.
         JsonConfigOptions configOptions = new JsonConfigOptions(MALFORMED_CONFIG_JSON, "{}");
         customService.setJsonConfig(configOptions); // Use correct setter

         initialPipeline.getServices().put(TEST_SERVICE_NAME, customService); // Use put for Map
         initialPipeline.setPipelineVersion(1); // Set expected version

         HttpRequest<?> updateRequest = HttpRequest.PUT(PIPELINE_API_BASE_PATH + "/" + TEST_PIPELINE_NAME, initialPipeline)
                 .contentType(MediaType.APPLICATION_JSON);

         // Inside testAddServiceWithMalformedCustomConfigJson()

         HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
             client.toBlocking().exchange(updateRequest, PipelineConfigDto.class); // Expect exception
         }, "Update with malformed JSON config should fail");

         assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), "Status should be 400 Bad Request");

         // --- NEW: Assert on the raw response body string ---
         Optional<String> bodyOpt = exception.getResponse().getBody(String.class);
         assertTrue(bodyOpt.isPresent(), "Error response body should be present as string");
         String errorBodyString = bodyOpt.get();
         log.debug("Raw error response body for Malformed: {}", errorBodyString);

         // Check if the raw string contains the key parts of the expected message
         // The message comes from the catch block we added in PipelineService
         //{"valid":false,"messages":["Invalid JSON: Unexpected end-of-input within/between Object entries\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 34]"]}
         assertThat(
                 "Error body string validation failed", // Optional reason message
                 errorBodyString,
                 allOf(
                         //{"valid":false,"messages":["Invalid JSON: Unexpected end-of-input within/between Object entries\n at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled); line: 1, column: 34]"]}
                         containsString("\"valid\":false"),
                         containsString("Invalid JSON: Unexpected end-of-input within/between Object entries"),
                         containsString("Source: REDACTED"),
                         containsString("INCLUDE_SOURCE_IN_LOCATION"),
                         containsString("line: 1, column: 34")
                         // Add more containsString(...) matchers here if needed
                 )
         );
// --- END NEW ASSERTION ---
    }

     @Test @Order(5)
     @DisplayName("Update Pipeline: Add Service with Valid Custom JSON but NO Schema Registered")
     void testAddServiceWithValidJsonButNoSchema() throws JsonProcessingException {
         // 1. Arrange: Create pipeline, ensure NO schema exists for the service impl
         PipelineConfigDto initialPipeline = createTestPipeline(TEST_PIPELINE_NAME);
         // DO NOT call putSchema for TEST_SERVICE_IMPL

         // 2. Act: Prepare update DTO with service using valid JSON, but schema is not registered
         PipeStepConfigurationDto customService = new PipeStepConfigurationDto();
         customService.setServiceImplementation(TEST_SERVICE_IMPL);
         customService.setName(TEST_SERVICE_NAME);
         // Provide valid JSON config and a schema string (even though it's not in Consul)
         JsonConfigOptions configOptions = new JsonConfigOptions(VALID_CONFIG_JSON, VALID_SERVICE_SCHEMA_JSON);
         customService.setJsonConfig(configOptions);

         initialPipeline.getServices().put(TEST_SERVICE_NAME, customService);
         initialPipeline.setPipelineVersion(1);

         HttpRequest<?> updateRequest = HttpRequest.PUT(PIPELINE_API_BASE_PATH + "/" + TEST_PIPELINE_NAME, initialPipeline)
                 .contentType(MediaType.APPLICATION_JSON);

         // Execute the PUT request
         HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(updateRequest, PipelineConfigDto.class);

         // 3. Assert: Update should SUCCEED because validation passes implicitly when no schema is found
         assertEquals(HttpStatus.OK, updateResponse.getStatus(), "Pipeline update should succeed when schema is missing");
         assertTrue(updateResponse.getBody().isPresent());
         PipelineConfigDto updatedPipeline = updateResponse.getBody().get();
         assertEquals(2, updatedPipeline.getPipelineVersion());
         assertTrue(updatedPipeline.getServices().containsKey(TEST_SERVICE_NAME));
         PipeStepConfigurationDto retrievedService = updatedPipeline.getServices().get(TEST_SERVICE_NAME);
         assertNotNull(retrievedService.getJsonConfig());
         // Verify config was still stored correctly
         assertEquals(objectMapper.readTree(VALID_CONFIG_JSON), objectMapper.readTree(retrievedService.getJsonConfig().getJsonConfig()));
     }


    // --- TestPropertyProvider Implementation ---
    // Provides Consul connection properties based on the Testcontainer
    @Override @NonNull
    public Map<String, String> getProperties() {
        // Get base properties from the singleton container helper method
        Map<String, String> properties = new HashMap<>(consulContainer.getProperties());

        // Ensure properties needed for this test context are set (can override defaults from container if needed)
        properties.putIfAbsent("micronaut.config-client.enabled", "false");
        properties.putIfAbsent("consul.data.seeding.enabled", "false"); // Important: Disable seeding for predictable test state
        properties.putIfAbsent("consul.client.registration.enabled", "false"); // Disable service registration for tests
        properties.putIfAbsent("consul.client.watch.enabled", "false"); // Disable config watching for tests

        log.debug("Providing test properties: {}", properties);
        return properties;
    }
}