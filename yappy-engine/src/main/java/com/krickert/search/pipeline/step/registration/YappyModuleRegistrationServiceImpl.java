package com.krickert.search.pipeline.step.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.agent.ImmutableRegCheck;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
// Removed unused imports: TransportType, ServiceOperationalStatus, Mono, Map, TimeUnit

@Singleton
@GrpcService
public class YappyModuleRegistrationServiceImpl extends YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(YappyModuleRegistrationServiceImpl.class);
    private static final String YAPPY_MODULE_TAG = "yappy-module";
    private static final String YAPPY_IMPLEMENTATION_ID_TAG_PREFIX = "yappy-module-implementation-id=";
    private static final String YAPPY_CONFIG_DIGEST_TAG_PREFIX = "yappy-config-digest=";
    private static final String YAPPY_MODULE_VERSION_TAG_PREFIX = "yappy-module-version=";

    private final ConsulBusinessOperationsService consulBusinessOpsService;
    private final ObjectMapper objectMapper; // For general JSON parsing if needed
    private final ObjectMapper digestObjectMapper; // For canonical JSON serialization for digests

    @Inject
    public YappyModuleRegistrationServiceImpl(
            ConsulBusinessOperationsService consulBusinessOpsService,
            ObjectMapper objectMapper) {
        this.consulBusinessOpsService = consulBusinessOpsService;
        this.objectMapper = objectMapper; // General purpose ObjectMapper

        // ObjectMapper dedicated to canonical serialization for digests
        this.digestObjectMapper = new ObjectMapper();
        this.digestObjectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);

        // Consider if other features are needed for canonical form, e.g.,
        // this.digestObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // this.digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false); // for compact output
    }

    @Override
    public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
        LOG.info("Received RegisterModule request for implementationId: {}, serviceName: {}",
                request.getImplementationId(), request.getInstanceServiceName());

        RegisterModuleResponse.Builder responseBuilder = RegisterModuleResponse.newBuilder();
        String generatedServiceId = null;
        String calculatedDigest = null;
        String canonicalJsonForResponse = null;

        try {
            // 1. Basic Request Validation
            if (request.getImplementationId().isBlank() || request.getInstanceServiceName().isBlank() ||
                    request.getHost().isBlank() || request.getPort() <= 0 ||
                    request.getHealthCheckType() == HealthCheckType.HEALTH_CHECK_TYPE_UNKNOWN ||
                    request.getInstanceCustomConfigJson().isBlank()) { // MODIFIED: Check instance_custom_config_json
                String message = "Missing or invalid required fields in registration request (implementationId, instanceServiceName, host, port, healthCheckType, instanceCustomConfigJson are mandatory).";
                LOG.warn("Registration failed: {}", message);
                responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                responseObserver.onCompleted();
                return;
            }

            // 2. Calculate Config Digest from instance_custom_config_json
            try {
                // Step 1: Parse the incoming JSON string into a generic Java Object (Map/List structure)
                //         using the digestObjectMapper itself. This ensures consistent parsing behavior
                //         aligned with the canonical serialization.
                //         Using Object.class allows Jackson to map to standard Map/List/primitive types.
                Object parsedJsonAsObject = digestObjectMapper.readValue(request.getInstanceCustomConfigJson(), Object.class);

                // Step 2: Serialize this generic Java Object back to a JSON string using the
                //         digestObjectMapper, which is configured for canonical output (sorted keys, no indent).
                String canonicalJson = digestObjectMapper.writeValueAsString(parsedJsonAsObject);

                // Log for debugging
                LOG.debug("Input JSON: {}", request.getInstanceCustomConfigJson());
                LOG.debug("Canonical JSON for digest: {}", canonicalJson); // Check this log output now!

                canonicalJsonForResponse = canonicalJson; // Store for potential inclusion in response
                calculatedDigest = generateMd5Digest(canonicalJson);
                LOG.debug("Calculated MD5 digest '{}' from canonical JSON for implementationId '{}'",
                        calculatedDigest, request.getImplementationId());
            } catch (JsonProcessingException e) {
                String message = "Invalid JSON format for instance_custom_config_json: " + e.getMessage();
                LOG.warn("Registration failed for {}: {}", request.getImplementationId(), message, e);
                responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                responseObserver.onCompleted();
                return;
            } catch (NoSuchAlgorithmException e) {
                String message = "MD5 algorithm not available for digest calculation: " + e.getMessage();
                LOG.error("Registration failed for {}: {}", request.getImplementationId(), message, e);
                responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                responseObserver.onCompleted();
                return;
            }
            responseBuilder.setCalculatedConfigDigest(calculatedDigest);
            if (canonicalJsonForResponse != null) {
                responseBuilder.setCanonicalConfigJsonBase64(
                        Base64.getEncoder().encodeToString(canonicalJsonForResponse.getBytes(StandardCharsets.UTF_8))
                );
            }


            // 3. Generate Consul Service ID
            String instanceIdHint = request.getInstanceIdHint();
            if (instanceIdHint != null && !instanceIdHint.isBlank()) {
                generatedServiceId = instanceIdHint + "-" + UUID.randomUUID().toString().substring(0, 8);
                LOG.debug("Generated Consul Service ID from hint: {}", generatedServiceId);
            } else {
                generatedServiceId = request.getImplementationId() + "-" + UUID.randomUUID().toString();
                LOG.debug("Generated Consul Service ID from implementationId and UUID: {}", generatedServiceId);
            }
            responseBuilder.setRegisteredServiceId(generatedServiceId);

            // 4. Construct Consul Registration Object
            ImmutableRegistration.Builder registrationBuilder = ImmutableRegistration.builder()
                    .id(generatedServiceId)
                    .name(request.getInstanceServiceName())
                    .address(request.getHost())
                    .port(request.getPort());

            // Configure Health Check (logic remains the same)
            ImmutableRegCheck.Builder checkBuilder = ImmutableRegCheck.builder();
            long checkIntervalSeconds = 10;
            long checkTimeoutSeconds = 5;
            long deregisterCriticalServiceAfterSeconds = 60;

            switch (request.getHealthCheckType()) {
                case HTTP:
                    String httpCheckUrl = String.format("http://%s:%d%s",
                            request.getHost(), request.getPort(), request.getHealthCheckEndpoint());
                    checkBuilder.http(httpCheckUrl)
                            .interval(checkIntervalSeconds + "s")
                            .timeout(checkTimeoutSeconds + "s");
                    LOG.debug("Configured HTTP health check: {}", httpCheckUrl);
                    break;
                case GRPC:
                    String grpcCheckAddress = request.getHost() + ":" + request.getPort();
                    checkBuilder.grpc(grpcCheckAddress)
                            .interval(checkIntervalSeconds + "s")
                            .timeout(checkTimeoutSeconds + "s");
                    if (!request.getHealthCheckEndpoint().isBlank()) {
                        registrationBuilder.addTags("grpc-health-service-name=" + request.getHealthCheckEndpoint());
                        LOG.debug("Added gRPC health service name tag: {}", request.getHealthCheckEndpoint());
                    }
                    LOG.debug("Configured gRPC health check targeting: {}", grpcCheckAddress);
                    break;
                case TCP:
                    String tcpCheckAddress = request.getHost() + ":" + request.getPort();
                    checkBuilder.tcp(tcpCheckAddress)
                            .interval(checkIntervalSeconds + "s")
                            .timeout(checkTimeoutSeconds + "s");
                    LOG.debug("Configured TCP health check: {}", tcpCheckAddress);
                    break;
                case TTL:
                    long defaultTtlIntervalSeconds = 15;
                    checkBuilder.ttl(defaultTtlIntervalSeconds + "s");
                    LOG.debug("Configured TTL health check with interval: {}s", defaultTtlIntervalSeconds);
                    break;
                default: // HEALTH_CHECK_TYPE_UNKNOWN or any other unhandled
                    String message = "Unsupported health check type: " + request.getHealthCheckType();
                    LOG.warn("Registration failed: {}", message);
                    responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                    responseObserver.onCompleted();
                    return;
            }
            checkBuilder.deregisterCriticalServiceAfter(deregisterCriticalServiceAfterSeconds + "s");
            registrationBuilder.check(checkBuilder.build());

            // Add Tags
            List<String> tags = new ArrayList<>();
            tags.add(YAPPY_MODULE_TAG + "=true");
            tags.add(YAPPY_IMPLEMENTATION_ID_TAG_PREFIX + request.getImplementationId());
            tags.add(YAPPY_CONFIG_DIGEST_TAG_PREFIX + calculatedDigest); // Use the calculated digest

            if (request.hasModuleSoftwareVersion() && !request.getModuleSoftwareVersion().isBlank()) {
                tags.add(YAPPY_MODULE_VERSION_TAG_PREFIX + request.getModuleSoftwareVersion());
            }
            request.getAdditionalTagsMap().forEach((key, value) -> tags.add(key + "=" + value));
            registrationBuilder.tags(tags);
            LOG.debug("Added tags to registration: {}", tags);

            Registration registration = registrationBuilder.build();
            LOG.debug("Constructed Consul Registration object: {}", registration);


            // 5. Register with Consul
            String finalGeneratedServiceId = generatedServiceId; // Effectively final for lambda
            String finalCalculatedDigest = calculatedDigest; // Capture for lambda
            String finalCanonicalJsonForResponse = canonicalJsonForResponse; // Capture for lambda

            consulBusinessOpsService.registerService(registration)
                    .subscribe(
                            null, // onNext: Mono<Void> (or Mono.empty()) doesn't emit a value here
                            error -> { // onError: This is for handling errors from the Mono
                                String message = "Failed to register module instance with Consul: " + error.getMessage();
                                LOG.error(message, error);

                                // Ensure all relevant fields are set in the responseBuilder before sending
                                responseBuilder.setRegisteredServiceId(finalGeneratedServiceId);
                                responseBuilder.setCalculatedConfigDigest(finalCalculatedDigest); // Use captured value
                                if (finalCanonicalJsonForResponse != null) {
                                    responseBuilder.setCanonicalConfigJsonBase64(
                                            Base64.getEncoder().encodeToString(finalCanonicalJsonForResponse.getBytes(StandardCharsets.UTF_8))
                                    );
                                }
                                responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                                responseObserver.onCompleted();
                            },
                            () -> { // onComplete (Runnable): This is for successful completion of the Mono
                                LOG.info("Module instance registered successfully with Consul. Service ID: {}", finalGeneratedServiceId);

                                // Ensure all relevant fields are set in the responseBuilder
                                responseBuilder.setRegisteredServiceId(finalGeneratedServiceId);
                                responseBuilder.setCalculatedConfigDigest(finalCalculatedDigest); // Use captured value
                                if (finalCanonicalJsonForResponse != null) {
                                    responseBuilder.setCanonicalConfigJsonBase64(
                                            Base64.getEncoder().encodeToString(finalCanonicalJsonForResponse.getBytes(StandardCharsets.UTF_8))
                                    );
                                }
                                responseObserver.onNext(responseBuilder.setSuccess(true).setMessage("Module registered successfully.").build());
                                responseObserver.onCompleted();
                            }
                    );

        } catch (Exception e) { // Catch-all for unexpected errors during setup
            // ... (outer catch block remains mostly the same, ensure responseBuilder fields are set if possible)
            String message = "An unexpected error occurred during module registration: " + e.getMessage();
            LOG.error(message, e);
            if (generatedServiceId != null) responseBuilder.setRegisteredServiceId(generatedServiceId);
            if (calculatedDigest != null) responseBuilder.setCalculatedConfigDigest(calculatedDigest);
            // Avoid setting canonicalJsonForResponse if it might be from a failed state or null
            responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
            responseObserver.onCompleted();
        }
    }

    private String generateMd5Digest(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digestBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digestBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Placeholder for UpdateModuleStatus (Future - especially for TTL checks)
    // @Override
    // public void updateModuleStatus(UpdateModuleStatusRequest request, StreamObserver<UpdateModuleStatusResponse> responseObserver) {
    //     LOG.warn("UpdateModuleStatus RPC is not yet implemented.");
    //     UpdateModuleStatusResponse response = UpdateModuleStatusResponse.newBuilder()
    //             .setAcknowledged(false)
    //             .build();
    //     responseObserver.onNext(response);
    //     responseObserver.onCompleted();
    // }
}