package com.krickert.search.pipeline.step.registration; // Or your actual package

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService; // If you need to mock/verify its calls directly
import com.krickert.testcontainers.consul.ConsulTestResourceProvider; // Assuming this is your provider for property names
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.agent.Agent;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// No Testcontainers direct imports needed here if Micronaut Test Resources handles it

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
// Add properties to ensure the main app doesn't try to register itself if it has that behavior,
// and to ensure Testcontainers for Consul is active for this test.
@Property(name = "micronaut.config-client.enabled", value = "false") // Often good for ITs
@Property(name = "consul.client.registration.enabled", value = "false") // If your app auto-registers
@Property(name = "testcontainers.consul.enabled", value = "true") // Ensure TestResources activates Consul
class YappyModuleRegistrationServiceIT {

    private static final Logger LOG = LoggerFactory.getLogger(YappyModuleRegistrationServiceIT.class);

    // gRPC client for the service under test
    private YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub registrationClient;
    private ManagedChannel channel;

    // Consul client for verification (this client is specific to the test)
    private Consul verificationConsulClient;

    @Inject
    ApplicationContext applicationContext; // To get dynamic port of gRPC server

    @Inject
    ObjectMapper objectMapper; // General purpose, Micronaut configured

    private ObjectMapper digestObjectMapper; // For calculating expected digests

    // Inject properties resolved by ConsulTestResourceProvider for our verificationConsulClient
    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_HOST)
    String consulHostForVerification;

    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_PORT)
    int consulPortForVerification; // Assuming your provider resolves port as int or it can be parsed

    @BeforeEach
    void setUpClientsAndMappers() {
        // 1. Setup gRPC client to connect to the Micronaut gRPC server (service under test)
        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        if (!embeddedServer.isRunning()) {
            embeddedServer.start(); // Ensure gRPC server is running
        }
        int grpcPort = embeddedServer.getPort();
        LOG.info("Micronaut gRPC server (service under test) running on port: {}", grpcPort);

        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        registrationClient = YappyModuleRegistrationServiceGrpc.newBlockingStub(channel);

        // 2. Setup ObjectMapper for digest calculation
        digestObjectMapper = new ObjectMapper();
        digestObjectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);

        // 3. Setup Consul client for verification
        // This client connects to the Consul instance managed by Micronaut Test Resources.
        // The host and port are injected via @Property annotations above.
        LOG.info("Setting up verification Consul client to connect to: {}:{}", consulHostForVerification, consulPortForVerification);
        verificationConsulClient = Consul.builder()
                .withHostAndPort(consulHostForVerification, consulPortForVerification)
                .build();
        
        // Quick check to ensure the verification client can connect
        try {
            LOG.info("Verification Consul client connected. Leader: {}", verificationConsulClient.statusClient().getLeader());
        } catch (Exception e) {
            fail("Failed to connect verificationConsulClient to Testcontainer Consul at " + consulHostForVerification + ":" + consulPortForVerification, e);
        }
        LOG.info("Setup complete for test.");
    }

    @AfterEach
    void tearDownChannel() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.warn("gRPC channel shutdown interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
        // The Testcontainer Consul lifecycle is managed by Micronaut Test Resources.
    }

    // Helper to calculate MD5 digest for test verification
    private String calculateTestMd5Digest(String jsonConfigString) throws NoSuchAlgorithmException, JsonProcessingException {
        Object parsedJsonAsObject = digestObjectMapper.readValue(jsonConfigString, Object.class);
        String canonicalJson = digestObjectMapper.writeValueAsString(parsedJsonAsObject);
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digestBytes = md.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digestBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("Should register module successfully with Consul and return correct digest")
    void testRegisterModule_successfulRegistration() throws Exception {
        // --- Arrange ---
        String implementationId = "it-module-v1";
        String instanceServiceName = "it-module-instance-alpha";
        String host = "module-host.example.com";
        int port = 9091;
        String healthEndpoint = "/health";
        String customConfigJson = "{\"settingA\":\"valueA\", \"settingB\":100}";
        String moduleVersion = "1.2.3-IT";
        String instanceIdHint = "k8s-pod-it-123";

        String expectedDigest = calculateTestMd5Digest(customConfigJson);
        Object parsedConfig = digestObjectMapper.readValue(customConfigJson, Object.class);
        String expectedCanonicalJson = digestObjectMapper.writeValueAsString(parsedConfig);

        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
                .setImplementationId(implementationId)
                .setInstanceServiceName(instanceServiceName)
                .setHost(host)
                .setPort(port)
                .setHealthCheckType(HealthCheckType.HTTP)
                .setHealthCheckEndpoint(healthEndpoint)
                .setInstanceCustomConfigJson(customConfigJson)
                .setModuleSoftwareVersion(moduleVersion)
                .setInstanceIdHint(instanceIdHint)
                .putAdditionalTags("dc", "test-dc")
                .build();

        // --- Act ---
        LOG.info("Sending RegisterModuleRequest to service...");
        RegisterModuleResponse response = registrationClient.registerModule(request);
        LOG.info("Received RegisterModuleResponse: success={}, serviceId={}, digest={}",
                response.getSuccess(), response.getRegisteredServiceId(), response.getCalculatedConfigDigest());

        // --- Assert: Response from YappyModuleRegistrationService ---
        assertTrue(response.getSuccess(), "Registration RPC should be successful. Message: " + response.getMessage());
        assertFalse(response.getRegisteredServiceId().isEmpty(), "Registered Service ID should not be empty");
        assertEquals(expectedDigest, response.getCalculatedConfigDigest(), "Calculated config digest mismatch");
        assertTrue(response.hasCanonicalConfigJsonBase64(), "Canonical JSON Base64 should be present");
        assertEquals(expectedCanonicalJson,
                     new String(Base64.getDecoder().decode(response.getCanonicalConfigJsonBase64()), StandardCharsets.UTF_8),
                     "Canonical JSON string mismatch");

        // --- Assert: Verify service registration in Consul using verificationConsulClient ---
        String registeredServiceId = response.getRegisteredServiceId();
        LOG.info("Verifying Consul registration for service ID: {}", registeredServiceId);

        Agent agent = verificationConsulClient.agentClient().getAgent();
        assertNotNull(agent.getServices().get(registeredServiceId), "Service should be registered in Consul. Available: " + agent.getServices().keySet());

        org.kiwiproject.consul.model.agent.Service consulService = agent.getServices().get(registeredServiceId);
        assertEquals(instanceServiceName, consulService.getService(), "Consul service name mismatch");
        assertEquals(host, consulService.getAddress(), "Consul service address mismatch");
        assertEquals(port, consulService.getPort(), "Consul service port mismatch");
        assertTrue(registeredServiceId.startsWith(instanceIdHint), "Consul service ID should use hint");

        // Verify Tags
        List<String> tags = consulService.getTags();
        LOG.info("Consul service tags for {}: {}", registeredServiceId, tags);
        assertTrue(tags.contains("yappy-module=true"), "Missing yappy-module tag");
        assertTrue(tags.contains("yappy-module-implementation-id=" + implementationId), "Missing implementation_id tag");
        assertTrue(tags.contains("yappy-config-digest=" + expectedDigest), "Missing or incorrect config_digest tag");
        assertTrue(tags.contains("yappy-module-version=" + moduleVersion), "Missing module_version tag");
        assertTrue(tags.contains("dc=test-dc"), "Missing additional tag 'dc'");

        // Verify Health Check (basic check for presence and type)
        Optional<ServiceHealth> serviceHealthOpt = verificationConsulClient.healthClient().getServiceHealth(instanceServiceName, registeredServiceId);
        assertTrue(serviceHealthOpt.isPresent(), "Service health information should be available");
        ServiceHealth serviceHealth = serviceHealthOpt.get();
        assertFalse(serviceHealth.getChecks().isEmpty(), "Health checks should be registered in Consul");
        // You can add more detailed assertions on the health check definition if needed
        // e.g., by inspecting serviceHealth.getChecks().get(0).getDefinition().getHttp()

        LOG.info("Successfully verified registration for {}", registeredServiceId);
    }

    // TODO: Add more integration test cases as outlined previously
    // - Different health check types
    // - Missing optional fields (like instanceIdHint, moduleVersion, additionalTags)
    // - Edge cases for customConfigJson (e.g., empty JSON "{}")
}