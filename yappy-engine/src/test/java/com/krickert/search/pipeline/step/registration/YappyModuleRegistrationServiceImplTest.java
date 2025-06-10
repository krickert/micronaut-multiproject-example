package com.krickert.search.pipeline.step.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.kiwiproject.consul.model.agent.Registration;
import org.mockito.ArgumentCaptor; // Keep this import
// import org.mockito.Captor; // REMOVE THIS IMPORT if it was there
import org.mockito.Mockito; // For static methods like verify
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock; // For the @MockBean factory method
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MicronautTest(environments = "test-registration")
class YappyModuleRegistrationServiceImplTest {

    @Inject
    private YappyModuleRegistrationServiceImpl registrationService;

    @Inject
    private ConsulBusinessOperationsService consulBusinessOpsService; // Mocked via @MockBean

    @Inject
    private ObjectMapper objectMapper; // Used for test setup, Micronaut provides this

    private ObjectMapper digestObjectMapper; // For calculating expected digests in tests


    @BeforeEach
    void setUp() {
        // MockitoAnnotations.openMocks(this); // Not needed with @Inject and @MockBean for JUnit 5
        reset(consulBusinessOpsService); // Reset the mock before each test
        when(consulBusinessOpsService.registerService(any(Registration.class)))
                .thenReturn(Mono.empty()); // Default successful registration

        // Initialize digestObjectMapper for tests, mirroring the service's setup
        digestObjectMapper = new ObjectMapper();
        digestObjectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);

    }

    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulBusinessOpsService() {
        return mock(ConsulBusinessOperationsService.class);
    }

    // Helper to calculate MD5 digest for test verification
    private String calculateTestMd5Digest(String jsonConfigString) throws NoSuchAlgorithmException, JsonProcessingException {
        // Step 1: Parse the input JSON string into a generic Java Object (e.g., Map/List structure)
        //         using an ObjectMapper that is as "neutral" as possible or, ideally,
        //         the one configured for digest output if its parsing behavior is also simple.
        //         For simplicity and to ensure the structure is what digestObjectMapper expects,
        //         let's parse it into a generic Object type using the digestObjectMapper itself.
        Object parsedJsonAsObject = digestObjectMapper.readValue(jsonConfigString, Object.class);

        // Step 2: Serialize this generic Java Object back to a JSON string using the
        //         digestObjectMapper, which is configured for canonical output (sorted keys, no indent).
        String canonicalJson = digestObjectMapper.writeValueAsString(parsedJsonAsObject);

        // Log for debugging in tests
        // System.out.println("Test Helper - Input: " + jsonConfigString);
        // System.out.println("Test Helper - Canonical: " + canonicalJson);

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digestBytes = md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digestBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Should produce same digest for logically identical JSON with different key orders")
    void testRegisterModule_jsonCanonicalization() throws Exception {
        String jsonUnordered = "{\"value\":123, \"name\":\"test\"}"; // Input with different order
        String jsonOrderedAndExpected = "{\"name\":\"test\",\"value\":123}"; // This is what ORDER_MAP_ENTRIES_BY_KEYS should produce

        // Calculate digest based on the unordered version, simulating module input
        String expectedDigest = calculateTestMd5Digest(jsonUnordered); // The service will receive this and canonicalize

        // The service's digestObjectMapper should turn jsonUnordered into jsonOrderedAndExpected before hashing
        // So, the digest of jsonOrderedAndExpected (when calculated by our test helper) should match expectedDigest.
        assertEquals(expectedDigest, calculateTestMd5Digest(jsonOrderedAndExpected),
                "Digests of unordered and pre-ordered JSON should match after canonicalization by helper");

        // Test with the unordered JSON
        RegisterModuleRequest requestUnordered = RegisterModuleRequest.newBuilder()
                .setImplementationId("canon-test-id")
                .setInstanceServiceName("canon-service-unordered")
                .setHost("localhost").setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP).setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(jsonUnordered)
                .build();
        TestStreamObserver<RegisterModuleResponse> observerUnordered = new TestStreamObserver<>();
        registrationService.registerModule(requestUnordered, observerUnordered);
        assertTrue(observerUnordered.awaitCompletion(5, TimeUnit.SECONDS));
        RegisterModuleResponse responseUnordered = observerUnordered.getResponse();
        assertTrue(responseUnordered.getSuccess(), "Registration with unordered JSON should succeed");
        assertEquals(expectedDigest, responseUnordered.getCalculatedConfigDigest(), "Digest from unordered JSON is incorrect");
        assertEquals(Base64.getEncoder().encodeToString(jsonOrderedAndExpected.getBytes(StandardCharsets.UTF_8)),
                responseUnordered.getCanonicalConfigJsonBase64(), "Canonical JSON from unordered input is incorrect");

        // Test with the already ordered JSON (should yield the same result)
        RegisterModuleRequest requestOrdered = RegisterModuleRequest.newBuilder()
                .setImplementationId("canon-test-id")
                .setInstanceServiceName("canon-service-ordered")
                .setHost("localhost").setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP).setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(jsonOrderedAndExpected)
                .build();
        TestStreamObserver<RegisterModuleResponse> observerOrdered = new TestStreamObserver<>();
        registrationService.registerModule(requestOrdered, observerOrdered);
        assertTrue(observerOrdered.awaitCompletion(5, TimeUnit.SECONDS));
        RegisterModuleResponse responseOrdered = observerOrdered.getResponse();
        assertTrue(responseOrdered.getSuccess(), "Registration with ordered JSON should succeed");
        assertEquals(expectedDigest, responseOrdered.getCalculatedConfigDigest(), "Digest from ordered JSON should match the one from unordered");
        assertEquals(Base64.getEncoder().encodeToString(jsonOrderedAndExpected.getBytes(StandardCharsets.UTF_8)),
                responseOrdered.getCanonicalConfigJsonBase64(), "Canonical JSON from ordered input is incorrect");
    }


    @Test
    @DisplayName("Should validate required fields and return error for missing fields")
    void testRegisterModule_missingRequiredFields() throws Exception {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("") // Missing implementation ID
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"key\":\"value\"}") // Now required
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Missing or invalid required fields"));
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
    }

    @ParameterizedTest
    @EnumSource(value = HealthCheckType.class, names = {"HTTP", "GRPC", "TCP", "TTL"})
    @DisplayName("Should successfully register module for each valid HealthCheckType")
    void testRegisterModule_successForEachHealthCheckType(HealthCheckType healthCheckType) throws Exception {
        String customConfigJson = "{\"setting\":\"enabled\", \"value\":42}";
        String expectedDigest = calculateTestMd5Digest(customConfigJson);
        JsonNode configNode = objectMapper.readTree(customConfigJson);
        String expectedCanonicalJson = digestObjectMapper.writeValueAsString(configNode);

        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(healthCheckType)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(customConfigJson)
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        ArgumentCaptor<Registration> registrationCaptor = ArgumentCaptor.forClass(Registration.class);

        // Ensure the mock is set up to complete the Mono, which triggers the service's onCompleted
        when(consulBusinessOpsService.registerService(registrationCaptor.capture()))
                .thenReturn(Mono.empty()); // This signals success to the service's subscribe block

        registrationService.registerModule(request, responseObserver);

        // Wait for the service to call onCompleted on the observer
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS), "gRPC call did not complete in time");
        assertNull(responseObserver.getError(), "gRPC call should not have errored. Error: " + responseObserver.getError());

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Response success should be true. Message: " + response.getMessage());
        assertTrue(response.getMessage().contains("Module registered successfully"));
        assertFalse(response.getRegisteredServiceId().isEmpty());
        assertEquals(expectedDigest, response.getCalculatedConfigDigest());
        assertTrue(response.hasCanonicalConfigJsonBase64());
        assertEquals(expectedCanonicalJson,
                new String(Base64.getDecoder().decode(response.getCanonicalConfigJsonBase64()), StandardCharsets.UTF_8));

        verify(consulBusinessOpsService).registerService(registrationCaptor.getValue()); // Capture and verify
        Registration registration = registrationCaptor.getValue();
        // ... rest of your assertions on the 'registration' object ...
        assertTrue(registration.getTags().stream().anyMatch(tag -> tag.equals("yappy-config-digest=" + expectedDigest)));
    }


    @Test
    @DisplayName("Should handle instance_id_hint when provided")
    void testRegisterModule_withInstanceIdHint() throws Exception {
        String instanceIdHint = "custom-instance-id";
        String customConfigJson = "{\"id_hint_config\":true}";
        String expectedDigest = calculateTestMd5Digest(customConfigJson);

        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(customConfigJson)
                .setInstanceIdHint(instanceIdHint)
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        ArgumentCaptor<Registration> registrationCaptor = ArgumentCaptor.forClass(Registration.class);
        when(consulBusinessOpsService.registerService(registrationCaptor.capture())).thenReturn(Mono.empty());
        registrationService.registerModule(request, responseObserver);

        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));
        assertNull(responseObserver.getError());

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.getRegisteredServiceId().startsWith(instanceIdHint));
        assertEquals(expectedDigest, response.getCalculatedConfigDigest());

        verify(consulBusinessOpsService).registerService(any(Registration.class));
        Registration registration = registrationCaptor.getValue();
        assertTrue(registration.getId().startsWith(instanceIdHint));
    }

    @Test
    @DisplayName("Should generate correct tags for the registration including calculated digest")
    void testRegisterModule_correctTagGeneration() throws Exception {
        String implementationId = "test-implementation-id";
        String customConfigJson = "{\"version_tag_config\":\"v1\"}";
        String expectedDigest = calculateTestMd5Digest(customConfigJson);
        String moduleVersion = "1.0.0";

        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId(implementationId)
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(customConfigJson)
                .setModuleSoftwareVersion(moduleVersion)
                .putAdditionalTags("custom-tag-key", "custom-tag-value")
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        ArgumentCaptor<Registration> registrationCaptor = ArgumentCaptor.forClass(Registration.class);
        when(consulBusinessOpsService.registerService(registrationCaptor.capture())).thenReturn(Mono.empty());
        registrationService.registerModule(request, responseObserver);

        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));
        assertNull(responseObserver.getError());

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertEquals(expectedDigest, response.getCalculatedConfigDigest());

        verify(consulBusinessOpsService).registerService(any(Registration.class));
        Registration registration = registrationCaptor.getValue();
        List<String> tags = registration.getTags();
        assertNotNull(tags);
        assertTrue(tags.contains("yappy-module=true"));
        assertTrue(tags.contains("yappy-module-implementation-id=" + implementationId));
        assertTrue(tags.contains("yappy-config-digest=" + expectedDigest)); // Verify the calculated digest
        assertTrue(tags.contains("yappy-module-version=" + moduleVersion));
        assertTrue(tags.contains("custom-tag-key=custom-tag-value"));
    }

    @Test
    @DisplayName("Should handle error from ConsulBusinessOperationsService")
    void testRegisterModule_consulError() throws Exception {
        String customConfigJson = "{\"error_case_config\":true}";
        String expectedDigest = calculateTestMd5Digest(customConfigJson); // Digest calculation should still happen

        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson(customConfigJson)
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        // Mock the registerService method to return an error
        when(consulBusinessOpsService.registerService(any(Registration.class)))
                .thenReturn(Mono.error(new RuntimeException("Consul registration failed")));

        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));
        assertNull(responseObserver.getError()); // gRPC call completes, error in payload

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Failed to register module instance with Consul"));
        assertEquals(expectedDigest, response.getCalculatedConfigDigest(), "Calculated digest should be present even if Consul call fails later");
    }

    @Test
    @DisplayName("Should reject registration with unknown health check type")
    void testRegisterModule_unknownHealthCheckType() throws Exception {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HEALTH_CHECK_TYPE_UNKNOWN)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"valid_json\":true}")
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Missing or invalid required fields"));
        // Digest calculation happens after this initial validation, so it should be empty
        assertTrue(response.getCalculatedConfigDigest().isEmpty(), "Digest should not be calculated if validation fails early");
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"implementationId", "instanceServiceName", "host", "instanceCustomConfigJson"})
    @DisplayName("Should validate each required string field (now including instanceCustomConfigJson)")
    void testRegisterModule_missingRequiredStringFields(String fieldName) throws Exception {
        RegisterModuleRequest.Builder requestBuilder = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"default_config\":true}");

        switch (fieldName) {
            case "implementationId": requestBuilder.setImplementationId(""); break;
            case "instanceServiceName": requestBuilder.setInstanceServiceName(""); break;
            case "host": requestBuilder.setHost(""); break;
            case "instanceCustomConfigJson": requestBuilder.setInstanceCustomConfigJson(""); break;
        }

        RegisterModuleRequest request = requestBuilder.build();
        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Missing or invalid required fields"));
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
    }

    @Test
    @DisplayName("Should validate port is greater than 0")
    void testRegisterModule_invalidPort() throws Exception {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-implementation-id")
                .setInstanceServiceName("test-service")
                .setHost("localhost")
                .setPort(0) // Invalid port
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{\"port_test_config\":true}")
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Missing or invalid required fields"));
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
    }

    @Test
    @DisplayName("Should fail if instance_custom_config_json is invalid JSON")
    void testRegisterModule_invalidJsonConfig() throws Exception {
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId("test-impl-id")
                .setInstanceServiceName("test-service-invalid-json")
                .setHost("localhost")
                .setPort(8080)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint("/health")
                .setInstanceCustomConfigJson("{not_valid_json:") // Invalid JSON
                .build();

        TestStreamObserver<RegisterModuleResponse> responseObserver = new TestStreamObserver<>();
        registrationService.registerModule(request, responseObserver);
        assertTrue(responseObserver.awaitCompletion(5, TimeUnit.SECONDS));

        RegisterModuleResponse response = responseObserver.getResponse();
        assertNotNull(response);
        assertFalse(response.getSuccess());
        assertTrue(response.getMessage().contains("Invalid JSON format for instance_custom_config_json"));
        assertTrue(response.getCalculatedConfigDigest().isEmpty());
        assertFalse(response.hasCanonicalConfigJsonBase64() && !response.getCanonicalConfigJsonBase64().isEmpty());
        verify(consulBusinessOpsService, never()).registerService(any(Registration.class));
    }

    // TestStreamObserver helper class remains the same
    private static class TestStreamObserver<T> implements StreamObserver<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private T response;
        private Throwable error;

        @Override
        public void onNext(T value) {
            this.response = value;
        }

        @Override
        public void onError(Throwable t) {
            this.error = t;
            latch.countDown();
        }

        @Override
        public void onCompleted() {
            latch.countDown();
        }

        public T getResponse() {
            return response;
        }

        public Throwable getError() {
            return error;
        }

        public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}