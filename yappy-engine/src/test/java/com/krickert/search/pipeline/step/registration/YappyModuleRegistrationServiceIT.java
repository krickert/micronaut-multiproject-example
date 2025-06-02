package com.krickert.search.pipeline.step.registration; // Or your actual package

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
// Assuming your ConsulTestResourceProvider is in this package or accessible
import com.google.common.net.HostAndPort;
import com.google.protobuf.Struct;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.pipeline.engine.exception.GrpcEngineException;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.sdk.*;
import com.krickert.testcontainers.consul.ConsulTestResourceProvider;
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
import org.kiwiproject.consul.model.ConsulResponse;
import org.kiwiproject.consul.model.agent.Agent;
import org.kiwiproject.consul.model.agent.FullService;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.health.Service;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
// Ensure Testcontainers for Consul is active for this test.
// Your ConsulTestResourceProvider should make these properties available.
@Property(name = "testcontainers.consul.enabled", value = "true")
// If your main app auto-registers with Consul, disable it for this specific test
// to avoid interference with the YappyModuleRegistrationService's actions.
@Property(name = "consul.client.registration.enabled", value = "true")
class YappyModuleRegistrationServiceIT {

    private static final Logger LOG = LoggerFactory.getLogger(YappyModuleRegistrationServiceIT.class);

    // gRPC client to call the YappyModuleRegistrationService under test
    private YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceBlockingStub registrationClient;
    private ManagedChannel channel;

    // Consul client for test verification purposes (connects to the Testcontainer-managed Consul)
    private Consul verificationConsulClient;

    @Inject
    ApplicationContext applicationContext; // To get the dynamic port of the gRPC server

    @Inject
    ObjectMapper objectMapper; // Micronaut-configured ObjectMapper for general use

    @Inject
    GrpcChannelManager grpcChannelManager; // Inject the channel manager

    private ObjectMapper digestObjectMapper; // For calculating expected digests in the test

    // These properties will be injected by Micronaut Test Resources,
    // assuming your ConsulTestResourceProvider resolves them.
    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_HOST)
    String consulHostForVerification;

    @Property(name = ConsulTestResourceProvider.PROPERTY_CONSUL_CLIENT_PORT)
    int consulPortForVerification; // Or String if your provider gives a string

    @BeforeEach
    void setUpClientsAndMappers() {
        // 1. Setup gRPC client to call the YappyModuleRegistrationService
        // Get the gRPC server instance, not the HTTP server
        io.micronaut.grpc.server.GrpcEmbeddedServer grpcServer = applicationContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
        if (!grpcServer.isRunning()) {
            LOG.info("Starting Micronaut gRPC server...");
            grpcServer.start();
        }
        int grpcPort = grpcServer.getPort();
        LOG.info("Micronaut gRPC server (service under test) running on port: {}", grpcPort);

        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext()
                .build();
        registrationClient = YappyModuleRegistrationServiceGrpc.newBlockingStub(channel);

        // 2. Setup ObjectMapper for digest calculation within the test
        digestObjectMapper = new ObjectMapper();
        digestObjectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);

        // 3. Setup Consul client for test verification
        // This client connects to the Consul instance managed by Micronaut Test Resources.
        LOG.info("Setting up verification Consul client to connect to Testcontainer Consul at: {}:{}",
                consulHostForVerification, consulPortForVerification);
        try {
            verificationConsulClient = Consul.builder()
                    .withHostAndPort(HostAndPort.fromParts(consulHostForVerification, consulPortForVerification))
                    .build();
            // Perform a quick check to ensure the verification client can connect
            LOG.info("Verification Consul client connected. Consul leader: {}", verificationConsulClient.statusClient().getLeader());
        } catch (Exception e) {
            LOG.error("Failed to initialize or connect verificationConsulClient to Testcontainer Consul at {}:{}. Error: {}",
                    consulHostForVerification, consulPortForVerification, e.getMessage(), e);
            fail("Failed to set up verification Consul client. Check Testcontainer Consul status and property injection.", e);
        }
        LOG.info("Test setup complete.");
    }

    @AfterEach
    void tearDownChannel() {
        if (channel != null && !channel.isShutdown()) {
            try {
                LOG.info("Shutting down gRPC client channel...");
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                LOG.info("gRPC client channel shut down.");
            } catch (InterruptedException e) {
                LOG.warn("gRPC channel shutdown interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
        // The Testcontainer Consul lifecycle (and its client within the application context)
        // is managed by Micronaut Test Resources.
        // The verificationConsulClient might not need explicit shutdown if kiwiproject.Consul handles it well,
        // but it's good practice if it has a close/destroy method.
        if (verificationConsulClient != null) {
            try {
                // kiwiproject.consul.Consul doesn't have a close() but a destroy()
                // verificationConsulClient.destroy(); // If needed, but usually Testcontainers handles the underlying resources
                LOG.debug("Verification Consul client resources will be cleaned up with Testcontainer.");
            } catch (Exception e) {
                LOG.warn("Error destroying verificationConsulClient: {}", e.getMessage());
            }
        }
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

    // --- Your Test Methods Will Go Here ---
    // Example:
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

        // 1. Get the map of all services known to the local agent to verify basic service registration details
        Map<String, org.kiwiproject.consul.model.health.Service> agentServices =
                verificationConsulClient.agentClient().getServices();

        assertTrue(agentServices.containsKey(registeredServiceId),
                "Service ID '" + registeredServiceId + "' should be registered in Consul. Available service IDs: " + agentServices.keySet());

        org.kiwiproject.consul.model.health.Service consulService = agentServices.get(registeredServiceId);
        assertNotNull(consulService, "Consul service details should not be null for ID: " + registeredServiceId);

        // Verify basic service properties
        assertEquals(instanceServiceName, consulService.getService(), "Consul service name mismatch");
        assertEquals(host, consulService.getAddress(), "Consul service address mismatch");
        assertEquals(port, consulService.getPort(), "Consul service port mismatch");
        assertEquals(registeredServiceId, consulService.getId(), "Consul service ID mismatch in retrieved service details");

        // Verify Tags
        List<String> tags = consulService.getTags();
        LOG.info("Consul service tags for {}: {}", registeredServiceId, tags);
        assertTrue(tags.contains("yappy-module=true"), "Missing yappy-module tag");
        assertTrue(tags.contains("yappy-module-implementation-id=" + implementationId), "Missing implementation_id tag");
        assertTrue(tags.contains("yappy-config-digest=" + expectedDigest), "Missing or incorrect config_digest tag");
        assertTrue(tags.contains("yappy-module-version=" + moduleVersion), "Missing module_version tag");
        assertTrue(tags.contains("dc=test-dc"), "Missing additional tag 'dc'");

        // 2. Verify Health Check Existence and Association
        Map<String, org.kiwiproject.consul.model.health.HealthCheck> agentChecks =
                verificationConsulClient.agentClient().getChecks();

        LOG.info("All agent checks available: {}", agentChecks.keySet());

        List<org.kiwiproject.consul.model.health.HealthCheck> serviceAssociatedChecks = agentChecks.values().stream()
                .filter(check -> check.getServiceId().isPresent() && check.getServiceId().get().equals(registeredServiceId))
                .collect(Collectors.toList());

        assertFalse(serviceAssociatedChecks.isEmpty(), "At least one health check should be registered for service ID: " + registeredServiceId);
        // Your service registers exactly one check as per YappyModuleRegistrationServiceImpl
        assertEquals(1, serviceAssociatedChecks.size(), "Expected exactly one health check registered for service ID: " + registeredServiceId);

        org.kiwiproject.consul.model.health.HealthCheck registeredCheck = serviceAssociatedChecks.get(0);
        assertNotNull(registeredCheck, "The health check object should not be null");

        // 3. Verify properties available on org.kiwiproject.consul.model.health.HealthCheck
        assertEquals(Optional.of(registeredServiceId), registeredCheck.getServiceId(), "HealthCheck's ServiceID mismatch");
        assertEquals(Optional.of(instanceServiceName), registeredCheck.getServiceName(), "HealthCheck's ServiceName mismatch");
        assertNotNull(registeredCheck.getName(), "HealthCheck name should not be null");
        assertNotNull(registeredCheck.getCheckId(), "HealthCheck ID should not be null");

        // Log details for manual inspection or if more specific name assertions are possible
        LOG.info("Registered HealthCheck: ID='{}', Name='{}', Status='{}', Notes='{}', Output='{}'",
                registeredCheck.getCheckId(),
                registeredCheck.getName(),
                registeredCheck.getStatus(),
                registeredCheck.getNotes().orElse("N/A"),
                registeredCheck.getOutput().orElse("N/A"));

        // Infer check type from the auto-generated name (this is a convention, not a guarantee)
        // Consul typically names checks like "Service '<service-name>' check" or includes type.
        // Your YappyModuleRegistrationServiceImpl doesn't explicitly name the check in Registration.RegCheck,
        // so Consul auto-generates it.
        String checkName = registeredCheck.getName().toLowerCase();
        HealthCheckType expectedType = request.getHealthCheckType();

        // This assertion is more robust than checking for "http" substring if Consul's naming is consistent.
        // However, the exact auto-generated name can vary.
        // A more reliable way if needed would be to give the check a specific name during registration.
        if (expectedType == HealthCheckType.HTTP) {
            // Example: Consul might name it "Service 'it-module-instance-alpha' check"
            // or if it includes type: "Service 'it-module-instance-alpha' HTTP check"
            // For now, let's just check if the service name is in the check name.
            assertTrue(checkName.contains(instanceServiceName.toLowerCase()),
                    "Check name should contain the service name. Name: " + registeredCheck.getName());
            // If you know the exact format Consul uses for HTTP check names, you can be more specific.
            // e.g., if it *always* includes "http" for http checks:
            // assertTrue(checkName.contains("http"), "Check name for HTTP type should ideally contain 'http'. Name: " + registeredCheck.getName());
        } else if (expectedType == HealthCheckType.GRPC) {
            assertTrue(checkName.contains(instanceServiceName.toLowerCase()),
                    "Check name should contain the service name. Name: " + registeredCheck.getName());
            // assertTrue(checkName.contains("grpc"), "Check name for GRPC type should ideally contain 'grpc'. Name: " + registeredCheck.getName());
        } // Add similar for TCP, TTL if a reliable naming pattern is known

        LOG.info("Successfully verified registration and basic health check association for {}", registeredServiceId);
    }

    @Test
    @DisplayName("Should fail to connect before registration, succeed after, and allow gRPC call")
    void testRegisterAndForwardToEchoService() throws Exception {
        String echoServiceNameForConsul = "echo-service-it-" + System.currentTimeMillis(); // Unique name for this test run
        String echoImplementationId = "echo-module-for-it";
        io.micronaut.grpc.server.GrpcEmbeddedServer grpcServer = applicationContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
        int grpcServerPort = grpcServer.getPort();
        String grpcServerHost = "localhost"; // Service is running in the same JVM

        // --- 1. Attempt to get channel via GrpcChannelManager BEFORE registration ---
        LOG.info("Attempting to get channel for '{}' BEFORE registration...", echoServiceNameForConsul);
        GrpcEngineException ex = assertThrows(GrpcEngineException.class, () -> {
            grpcChannelManager.getChannel(echoServiceNameForConsul);
        }, "Should throw GrpcEngineException when service is not found in Consul");
        LOG.info("Correctly failed to get channel before registration: {}", ex.getMessage());
        assertTrue(ex.getMessage().contains("Service not found via discovery") || ex.getMessage().contains("No instances found"),
                "Exception message should indicate service not found or no instances.");

        // --- 2. Register the EchoService using YappyModuleRegistrationService ---
        String echoCustomConfigJson = "{\"log_prefix\":\"IT_ECHO: \"}"; // Example config for Echo
        String echoExpectedDigest = calculateTestMd5Digest(echoCustomConfigJson);

        RegisterModuleRequest registrationReq = RegisterModuleRequest.newBuilder()
                .setImplementationId(echoImplementationId)
                .setInstanceServiceName(echoServiceNameForConsul) // This is the name GrpcChannelManager will look up
                .setHost(grpcServerHost) // EchoService is running on localhost in the test EmbeddedServer
                .setPort(grpcServerPort)
                .setHealthCheckType(HealthCheckType.TCP) // TCP check is simple for an in-process server
                // .setHealthCheckEndpoint("") // Not needed for TCP
                .setInstanceCustomConfigJson(echoCustomConfigJson)
                .setModuleSoftwareVersion("1.0-echo-it")
                .build();

        LOG.info("Registering '{}' via YappyModuleRegistrationService...", echoServiceNameForConsul);
        RegisterModuleResponse registrationResp = registrationClient.registerModule(registrationReq);
        assertTrue(registrationResp.getSuccess(), "EchoService registration should be successful. Msg: " + registrationResp.getMessage());
        String registeredEchoServiceId = registrationResp.getRegisteredServiceId();
        LOG.info("EchoService registered with ID: {}", registeredEchoServiceId);

        // Brief pause for Consul registration to propagate if needed, though usually quick for local agent
        TimeUnit.MILLISECONDS.sleep(500); // Adjust if necessary

        // --- 3. Attempt to get channel via GrpcChannelManager AFTER registration ---
        LOG.info("Attempting to get channel for '{}' AFTER registration...", echoServiceNameForConsul);
        ManagedChannel echoChannelAfterRegistration = null;
        try {
            echoChannelAfterRegistration = grpcChannelManager.getChannel(echoServiceNameForConsul);
        } catch (Exception e) {
            fail("Should successfully get channel for '" + echoServiceNameForConsul + "' after registration", e);
        }
        assertNotNull(echoChannelAfterRegistration, "Channel to EchoService should now be obtainable");
        LOG.info("Successfully obtained channel to '{}' after registration. Authority: {}", echoServiceNameForConsul, echoChannelAfterRegistration.authority());

        // --- 4. Make a gRPC call to the EchoService using the obtained channel ---
        PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClientStub =
                PipeStepProcessorGrpc.newBlockingStub(echoChannelAfterRegistration);

        PipeDoc docToEcho = PipeDoc.newBuilder().setId("echo-test-doc").setTitle("Hello Registered Echo").build();

        // --- Corrected JSON parsing for customConfig ---
        Struct.Builder structBuilder = Struct.newBuilder();
        try {
            com.google.protobuf.util.JsonFormat.parser()
                    .ignoringUnknownFields() // Keep this if you want to ignore unknown fields
                    .merge(echoCustomConfigJson, structBuilder);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            // Handle the parsing exception, perhaps fail the test or log an error
            // For this example, we'll rethrow as a runtime exception to make the test fail clearly
            // if the JSON is truly invalid, though your sample JSON is valid.
            throw new RuntimeException("Failed to parse echoCustomConfigJson into Struct", e);
        }
        // Now structBuilder is populated with the data from echoCustomConfigJson


        ProcessRequest echoProcessRequest = ProcessRequest.newBuilder()
                .setDocument(docToEcho)
                .setMetadata(ServiceMetadata.newBuilder()
                        .setStreamId("echo-stream-1")
                        .setPipelineName("test-forward-pipeline")
                        .setPipeStepName(echoServiceNameForConsul) // Using service name as step name for this call
                        .build())
                .setConfig(ProcessConfiguration.newBuilder() // Send the custom config to EchoService
                        .setCustomJsonConfig(structBuilder.build()) // Use the populated and built Struct
                        .build())
                .build();


        LOG.info("Calling EchoService on dynamically obtained channel...");
        ProcessResponse echoActualResponse = echoClientStub.processData(echoProcessRequest);

        assertNotNull(echoActualResponse, "Response from EchoService should not be null");
        assertTrue(echoActualResponse.getSuccess(), "EchoService call should be successful");
        assertEquals(docToEcho, echoActualResponse.getOutputDoc(), "EchoService should echo the document");
        LOG.info("Successfully called EchoService. Log: {}", echoActualResponse.getProcessorLogsList());
        assertTrue(echoActualResponse.getProcessorLogsList().stream().anyMatch(log -> log.startsWith("IT_ECHO: ")),
                "EchoService log should contain the custom prefix.");

        // --- 5. Verify Consul registration details for the Echo service (optional, but good) ---
        Map<String, org.kiwiproject.consul.model.health.Service> agentServices =
                verificationConsulClient.agentClient().getServices();
        assertTrue(agentServices.containsKey(registeredEchoServiceId), "Echo service should still be registered");
        org.kiwiproject.consul.model.health.Service echoConsulService = agentServices.get(registeredEchoServiceId);
        assertEquals(echoServiceNameForConsul, echoConsulService.getService());
        assertEquals(grpcServerHost, echoConsulService.getAddress());
        assertEquals(grpcServerPort, echoConsulService.getPort());
        assertTrue(echoConsulService.getTags().contains("yappy-config-digest=" + echoExpectedDigest));

        LOG.info("Test testRegisterAndForwardToEchoService completed successfully.");

        // Cleanup the channel if it's different from the main test channel
        if (echoChannelAfterRegistration != null && !echoChannelAfterRegistration.isShutdown()) {
            echoChannelAfterRegistration.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}