# Questions About YAPPY Implementation

## Cluster Name Handling
1. **Default Cluster Name**: The code doesn't appear to set "yappy-default" as a default cluster name when none is provided. Is this intentional, or should there be a default cluster name?

* The `app.config.cluster-name` property in application.yml 
* During the bootstrap process when selecting or creating a cluster

So the bootstrap sets this property.  Right?  The bootstrap process can change - we just need to clarify the needs for this and be 
consistent.

The bootstrap process would take care of this.  So the only requirement to start an engine instance is that consul should exist.  The 
bootstrap process mode is critical.  It looks like I may have been wrong in my assumption?  I think the bootstrap setup screen and guide 
the user through this process.

Now, the service can still be registered and not be part of a cluster yet.  That would be another admin feature - to add a service to 
the cluster that's available.  This should be as simple as adding it to the list of "allowed services".  This feature is here because in 
an enterprise setup, some services may need to be more secure or not.  This leaves it up to admin control what the pipeline editor can 
include in their pipeline.  

Let me know if this is more clear.  Or if it's too big of a change in the code, let me know of a better way to handle this.

2. **Cluster Name Configuration**: How should users configure the cluster name? Should it be set in application.yml, provided during bootstrap, or both?

either one.  If it's already in the startup, the bootstrap process should create it.  If it is not, the bootstarp process will stay in 
setup mode and wait for the user to configure it.  If tehre alreaady exists clusters, the front end will allow the user to decide how 
this engine will start.


## Bootstrap Process
1. **Bootstrap File Location**: The bootstrap file path is configurable via `${yappy.engine.bootstrap-file.path:~/.yappy/engine-bootstrap.properties}`. Is this the recommended location, or should it be changed?

that works fine.  Since it's configurable we can configure it later.  The idea here is that we can write infrstructure code to "seed" a 
new engine/module by adding this file without the need of the front end.  So this process can either be automated or go through the 
front end wee are creating.

2. **Restart Requirements**: After setting Consul configuration, the code recommends a restart. Is this always necessary, or are there cases where a restart can be avoided?

I don't know - I think we suggested a restart to prevent complicated setups and needing to track too many states?  If that can be done 
safely and dynamically, lets' avoid a restart.  

## Service Registration
1. **Registration Service Implementation**: The yappy_service_registration.md document describes a registration service, but it's not clear if this has been implemented yet. What is the current status?

It's not been fully vetted and implemented.  We're in the middle of making it.

You said:
**Module Registration Service**: The documentation in yappy_service_registration.md describes a registration service, but it's not clear if this has been implemented yet. The current_instructions.md should clarify the current status of this service and how modules should register themselves with the YAPPY ecosystem in the current implementation.

My reply:
Since this is in process, it CAN change until we are done.  The code so far should represent how we started to implement this.  Changes 
were made along the way that would change what's in yappy_service_registration.md (and may further complicate things because this 
document is giving more clarification).  Currently, what I write in this document is what I cosider the most up-to-date.  What is in 
yappy_service_registration.md was what we originally designed but may have deviated.  Those deviations are likely easy to spot in the 
code, which is why I'm including the service registartion code below.

* **Registration Workflow**: The documentation should provide more details on the actual registration workflow, including:
    - How modules discover the registration service - they don't, they only run as localhost.  The engine handles all that logic.  The 
      engine will register the module FOR itself.  This code is already done and tested.
    - How the registration service authenticates modules - I think we should make a service call stub for this... we may what to expand 
      on this.  For now we just assume it's allowed but make a stub that always returns true.
    - How the engine discovers registered modules - this is what consul is for.  So the engine itself listens to a host and port on a specifc 
      that's in the engine's local config for what module it will wait to connect to.  I think when we get closer to "launch" we can 
      make this be mutual certs can handle this.. but also like the idea for an authenticate method for now.


Here's the registration service:

```java
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
```

2. **Module Registration Process**: How should modules register themselves with the YAPPY ecosystem in the current implementation?

We're working through that now.  Here's what I'm thinking

1. All engine instances are tied to a module.
2. When an engine first starts ever - not due to being scaled
   1. The service waits for the module to start or if it's in consul (this is detailed now).  It will fall to localhost first and then 
      fallback to a consul service.  If both fail, the service will continue to "wait" before announcing it is available for either of 
      these states to be true.
   2. When the localhost becomes available, it will register itself in consul on the module's behalf. That is what com.krickert.search.
      pipeline.step.registration.YappyModuleRegistrationServiceImpl.registerModule is for
   3. At this point the engine is "green" and can begin being added to pipelines for use.
3. When the engine starts during a scaling operation for a particular module
   1. It should be available in consul at this point.  Therefore, the above step 1 should work.  However, if a service is being proxied 
      and not yet running as localhost, the service status is "yellow".  This should be covered in the code
   2. it won't differ much from the first time ever, but just in case there's other use cases I didn't think about, it's important to 
      notice this state even if it behaves the same way.

## Consul Paths
1. **Standard Paths**: The code uses paths like `pipeline-configs/clusters` for storing cluster configurations. Are these the standard paths that should be documented?

This might be a bug?  the idea is that clusters should be the "chroot" of the implementation. Clusters don't communicate with each other.

Please outline what work needs to be done to correct this....

2. **Path Customization**: Can users customize these paths, and if so, how?
No.  I think that early on I may have been OK with this, but the config in consul should be controlled by the application and we can 
   make a single static class iin the consul model project that tracks all of this.

## Integration Tests
1. **Test Coverage**: What additional integration tests would be beneficial for ensuring the bootstrap and registration processes work correctly?

So at first we will test a single process and full bootstrap.  So we need to simulate in a real integration test (we have consul, 
opensearch, kafka, and moto/apicurio services as testresources for this integration test).  The complication comes that we are talking 
about 2 separate services - the grpc service that runs as localhost (which can be any language) and the engine.  That's why the 
integration tests started to have 2 different application contexts and attempt to get 2 grpc services to be communicating - so we can 
lead to simulating this.

The next step would be to ensure that the bootstrap process is solid.  That it's easy to set this system up if a user sets up consul and 
kafka, it can technically work.  and that the user can either use the setup process or provide a bootstrap property file to do all the 
configuration in an automated way.



2. **Test Environment**: What should the test environment include to properly test these features?

I think this is already setup.  I'm utilizing the micronaut test resources 

[apache-kafka-test-resource](yappy-test-resources/apache-kafka-test-resource)
[apicurio-test-resource](yappy-test-resources/apicurio-test-resource)
[consul-test-resource](yappy-test-resources/consul-test-resource)
[moto-test-resource](yappy-test-resources/moto-test-resource)
[opensearch3-test-resource](yappy-test-resources/opensearch3-test-resource)

This should handle all the setup.  This is already extensively used in [yappy-engine](yappy-engine) and [yappy-consul-config](yappy-consul-config)

