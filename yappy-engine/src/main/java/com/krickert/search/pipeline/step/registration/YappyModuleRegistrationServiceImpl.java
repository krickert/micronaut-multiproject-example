package com.krickert.search.pipeline.step.registration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.TransportType; // Might need this if mapping HealthCheckType to TransportType
import com.krickert.search.config.service.model.ServiceOperationalStatus; // Might need this for status updates (future)
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
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit; // For health check intervals/timeouts

@Singleton
@GrpcService
public class YappyModuleRegistrationServiceImpl extends YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(YappyModuleRegistrationServiceImpl.class);
    private static final String YAPPY_MODULE_TAG_PREFIX = "yappy-module:";
    private static final String YAPPY_IMPLEMENTATION_ID_TAG_PREFIX = "yappy-module-implementation-id=";
    private static final String YAPPY_CONFIG_DIGEST_TAG_PREFIX = "yappy-config-digest=";
    private static final String YAPPY_MODULE_VERSION_TAG_PREFIX = "yappy-module-version=";

    private final ConsulBusinessOperationsService consulBusinessOpsService;
    // Inject UUIDGenerator if preferred, otherwise use UUID.randomUUID()
    // private final UUIDGenerator uuidGenerator;

    @Inject
    public YappyModuleRegistrationServiceImpl(ConsulBusinessOperationsService consulBusinessOpsService /*, UUIDGenerator uuidGenerator */) {
        this.consulBusinessOpsService = consulBusinessOpsService;
        // this.uuidGenerator = uuidGenerator;
    }

    @Override
    public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
        LOG.info("Received RegisterModule request for implementationId: {}, serviceName: {}",
                request.getImplementationId(), request.getInstanceServiceName());

        RegisterModuleResponse.Builder responseBuilder = RegisterModuleResponse.newBuilder();
        String generatedServiceId = null; // Initialize to null

        try {
            // 1. Basic Request Validation
            if (request.getImplementationId().isBlank() || request.getInstanceServiceName().isBlank() ||
                request.getHost().isBlank() || request.getPort() <= 0 ||
                request.getHealthCheckType() == HealthCheckType.HEALTH_CHECK_TYPE_UNKNOWN ||
                request.getConfigDigest().isBlank()) { // Config digest is mandatory
                String message = "Missing or invalid required fields in registration request.";
                LOG.warn("Registration failed: {}", message);
                responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                responseObserver.onCompleted();
                return;
            }

            // 2. Generate Consul Service ID
            String instanceIdHint = request.getInstanceIdHint();
            if (!instanceIdHint.isBlank()) {
                 // Use hint, maybe append a short suffix for extra uniqueness assurance
                 generatedServiceId = instanceIdHint + "-" + UUID.randomUUID().toString().substring(0, 8);
                 LOG.debug("Generated Consul Service ID from hint: {}", generatedServiceId);
            } else {
                 // Generate ID from implementationId and UUID
                 generatedServiceId = request.getImplementationId() + "-" + UUID.randomUUID().toString();
                 LOG.debug("Generated Consul Service ID from implementationId and UUID: {}", generatedServiceId);
            }
            responseBuilder.setRegisteredServiceId(generatedServiceId); // Set ID in response early

            // 3. Construct Consul Registration Object
            ImmutableRegistration.Builder registrationBuilder = ImmutableRegistration.builder()
                .id(generatedServiceId)
                .name(request.getInstanceServiceName())
                .address(request.getHost())
                .port(request.getPort());

            // Configure Health Check
            ImmutableRegCheck.Builder checkBuilder = ImmutableRegCheck.builder();
            long checkIntervalSeconds = 10; // Default interval
            long checkTimeoutSeconds = 5;   // Default timeout
            long deregisterCriticalServiceAfterSeconds = 60; // Default deregister after

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
                    // kiwiproject's grpc check uses host:port. The endpoint might be the gRPC service name.
                    // If the endpoint is the gRPC service name (e.g. "grpc.health.v1.Health"),
                    // you might need to add it as a tag or metadata if the Consul client doesn't support it directly in the check.
                    // For now, use the basic grpc check which targets the server at host:port.
                    String grpcCheckAddress = request.getHost() + ":" + request.getPort();
                    checkBuilder.grpc(grpcCheckAddress)
                                .interval(checkIntervalSeconds + "s")
                                .timeout(checkTimeoutSeconds + "s");
                    // Optionally, add the gRPC service name endpoint as a tag for info
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
                    // For TTL, the module sends heartbeats. The check definition in Consul is just the TTL interval.
                    // The request doesn't provide the interval, so we'll use a default or require it in the request.
                    // Let's assume a default TTL interval for the check definition.
                    long defaultTtlIntervalSeconds = 15; // Module must heartbeat within this time
                    checkBuilder.ttl(defaultTtlIntervalSeconds + "s");
                    LOG.debug("Configured TTL health check with interval: {}s", defaultTtlIntervalSeconds);
                    // The module needs to know its registered_service_id to send heartbeats.
                    break;
                default:
                    String message = "Unsupported health check type: " + request.getHealthCheckType();
                    LOG.warn("Registration failed: {}", message);
                    responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                    responseObserver.onCompleted();
                    return;
            }
            // Add common check properties
            checkBuilder.deregisterCriticalServiceAfter(deregisterCriticalServiceAfterSeconds + "s");
            registrationBuilder.check(checkBuilder.build());

            // Add Tags
            List<String> tags = new ArrayList<>();
            tags.add(YAPPY_MODULE_TAG_PREFIX + "true"); // Mandatory Yappy module tag
            tags.add(YAPPY_IMPLEMENTATION_ID_TAG_PREFIX + request.getImplementationId()); // Mandatory implementation ID tag
            tags.add(YAPPY_CONFIG_DIGEST_TAG_PREFIX + request.getConfigDigest()); // Mandatory config digest tag

            if (request.hasModuleSoftwareVersion() && !request.getModuleSoftwareVersion().isBlank()) {
                tags.add(YAPPY_MODULE_VERSION_TAG_PREFIX + request.getModuleSoftwareVersion());
            }
            // Add additional tags from the request
            request.getAdditionalTagsMap().forEach((key, value) -> tags.add(key + "=" + value)); // Simple key=value format for now
            registrationBuilder.tags(tags);
            LOG.debug("Added tags to registration: {}", tags);

            Registration registration = registrationBuilder.build();
            LOG.debug("Constructed Consul Registration object: {}", registration);

            // 4. Register with Consul
            String finalGeneratedServiceId = generatedServiceId;
            consulBusinessOpsService.registerService(registration)
                .subscribe(
                    (Void v) -> { /* onComplete */
                        LOG.info("Module instance registered successfully with Consul. Service ID: {}", finalGeneratedServiceId);
                        responseObserver.onNext(responseBuilder.setSuccess(true).setMessage("Module registered successfully.").build());
                        responseObserver.onCompleted();
                    },
                    error -> { // onError
                        String message = "Failed to register module instance with Consul: " + error.getMessage();
                        LOG.error(message, error);
                        responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
                        responseObserver.onCompleted();
                    }
                );

        } catch (Exception e) {
            String message = "An unexpected error occurred during module registration: " + e.getMessage();
            LOG.error(message, e);
            // Ensure registered_service_id is included even on unexpected errors if generated
            responseObserver.onNext(responseBuilder.setSuccess(false).setMessage(message).build());
            responseObserver.onCompleted();
        }
    }

    // Placeholder for DeregisterModule (Future)
    // @Override
    // public void deregisterModule(DeregisterModuleRequest request, StreamObserver<DeregisterModuleResponse> responseObserver) {
    //     LOG.warn("DeregisterModule RPC is not yet implemented.");
    //     DeregisterModuleResponse response = DeregisterModuleResponse.newBuilder()
    //             .setSuccess(false)
    //             .setMessage("Not yet implemented")
    //             .build();
    //     responseObserver.onNext(response);
    //     responseObserver.onCompleted();
    // }

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