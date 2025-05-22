# YAPPY Module Registration Service & Workflow

## 1. Goal

To enable pipeline step modules (developed in any gRPC-compatible language) to register themselves with the YAPPY ecosystem in a standardized, secure, and simple way. This allows the YAPPY Engine to discover, invoke, and monitor these modules.

**Key Design Principles:**

*   **Minimize Developer Effort:** Module developers should focus on business logic, not on YAPPY-specific registration or Consul intricacies.
*   **Language Agnostic:** The registration mechanism must be accessible from any language capable of making gRPC or REST API calls.
*   **Centralized Registration Logic:** The YAPPY platform (via a dedicated service) handles the complexities of Consul registration.
*   **Configuration Consistency:** Enable tracking of whether a running module instance is using the configuration expected by the engine.

## 2. Core Concepts

*   **`implementationId`**:
    *   A unique string identifier for a *type* of module (e.g., `echo-processor-v1`, `opennlp-chunker-v2.1`).
    *   Defined in `PipelineModuleConfiguration.implementationId`.
    *   Used by the engine to link a pipeline step to a specific module type.
    *   Modules must be configured with their `implementationId` at startup.
*   **Module Instance Custom Configuration**:
    *   The specific configuration a deployed module instance is running with.
    *   This configuration should ideally match the `customConfig` (Map<String, Object>) defined in the `PipelineModuleConfiguration` for its `implementationId`.
    *   Provided to the module instance at deployment (e.g., via environment variables as a JSON string or a mounted config file).
*   **Configuration Digest**:
    *   A hash (e.g., MD5, SHA256) of the module instance's custom configuration (serialized to a canonical JSON string).
    *   Calculated by the module instance at startup.
    *   Used by the engine to verify if a running module instance has the expected configuration.

## 3. Module Registration Workflow

1.  **Module Startup**:
    *   A YAPPY module instance (e.g., a Python gRPC service) starts.
    *   It loads its `implementationId` (e.g., from an environment variable `YAPPY_IMPLEMENTATION_ID`).
    *   It loads its instance-level custom configuration (e.g., from `YAPPY_MODULE_INSTANCE_CONFIG_JSON` env var or a file).
    *   It calculates the MD5/SHA256 digest of its instance-level custom configuration (after serializing it to a canonical JSON string).
2.  **Call Yappy Registration Service**:
    *   The module makes a gRPC (or REST) call to the "Yappy Registration Service" API.
    *   It provides its `implementationId`, its actual network address (host/port), health check endpoint details, and the calculated configuration digest.
3.  **Yappy Registration Service Processing**:
    *   Receives the registration request.
    *   (Future: Authenticates/authorizes the request).
    *   Constructs a complete Consul service registration payload.
    *   Uses `ConsulBusinessOperationsService` to register the module with Consul, including specific Yappy tags.
4.  **Engine Discovery & Monitoring**:
    *   The YAPPY Engine's `ServiceStatusAggregator` discovers the module in Consul.
    *   It uses the tags to identify it as a Yappy module, link it to a `PipelineModuleConfiguration`, and compare configuration digests.
    *   The Engine's `GrpcChannelManager` uses the `implementationId` (from `PipelineStepConfig.processorInfo.grpcServiceName`) to discover and connect to the module for processing pipeline steps.

## 4. Module Responsibilities (at Startup)

To register with the YAPPY ecosystem, a module instance must:

1.  **Be Configured With `implementationId`**:
    *   This ID links the running instance to its type definition in `PipelineModuleConfiguration`.
    *   Typically provided via an environment variable (e.g., `YAPPY_IMPLEMENTATION_ID`).
2.  **Receive Its Instance-Level Custom Configuration**:
    *   This is the configuration that dictates its behavior, corresponding to the `customConfig` map in `PipelineModuleConfiguration`.
    *   Typically provided as a JSON string via an environment variable (e.g., `YAPPY_MODULE_INSTANCE_CONFIG_JSON`) or a mounted configuration file.
3.  **Calculate Configuration Digest**:
    *   Read its instance-level custom configuration.
    *   Serialize this configuration to a canonical JSON string (e.g., keys sorted alphabetically).
    *   Compute a digest (e.g., MD5 or SHA256) of this JSON string.
4.  **Call the Yappy Registration Service API**:
    *   Provide the following information:
        *   `implementationId` (string)
        *   `serviceName` (string, e.g., its own application name, can be derived or configured)
        *   `host` (string, its network-accessible host/IP)
        *   `port` (int, the port its gRPC server is listening on)
        *   `healthCheckEndpoint` (string, e.g., `/health/ready` or gRPC health service method)
        *   `healthCheckType` (enum, e.g., `HTTP`, `GRPC`, `TCP`)
        *   `configDigest` (string, the calculated digest)
        *   (Optional: `moduleSoftwareVersion`, `instanceIdHint`)

## 5. Yappy Registration Service

This is a new service, potentially part of the Yappy Engine's broader control plane capabilities or a standalone Micronaut application.

### 5.1. API Definition (gRPC Example)

The Yappy Module Registration Service will expose a gRPC API. The following is the definition for this service, typically defined in a `.proto` file (e.g., `yappy_module_registration_service.proto`).

```protobuf
syntax = "proto3";

package com.krickert.yappy.registration.api;

// Consider standardizing your Java package options across all Yappy protos
option java_package = "com.krickert.yappy.registration.api";
option java_multiple_files = true;
option java_outer_classname = "YappyModuleRegistrationProto"; // Or similar

// Service definition for modules to register with the Yappy platform
service YappyModuleRegistrationService {
  // Allows a module instance to register itself with the Yappy platform.
  // The platform will then handle the actual registration with Consul.
  rpc RegisterModule (RegisterModuleRequest) returns (RegisterModuleResponse);

  // Future: Allows a module instance to gracefully deregister itself.
  // rpc DeregisterModule (DeregisterModuleRequest) returns (DeregisterModuleResponse);

  // Future: Allows a module instance to update its status or send heartbeats if using TTL checks.
  // rpc UpdateModuleStatus (UpdateModuleStatusRequest) returns (UpdateModuleStatusResponse);
}

// Enum for specifying the type of health check the module exposes.
enum HealthCheckType {
  HEALTH_CHECK_TYPE_UNKNOWN = 0; // Default, should not be used
  HTTP = 1;                      // Standard HTTP GET endpoint
  GRPC = 2;                      // Standard gRPC health check (grpc.health.v1.Health)
  TCP = 3;                       // Simple TCP connect check
  TTL = 4;                       // Time-To-Live; module must send heartbeats via UpdateModuleStatus
}

// Request message for registering a module instance.
message RegisterModuleRequest {
  // The unique identifier for the *type* of this module.
  // This ID links the running instance to its definition in PipelineModuleConfiguration.
  // Example: "echo-processor-v1", "opennlp-chunker-v2.1"
  string implementation_id = 1;

  // The desired service name for this specific instance in Consul.
  // Can be unique per instance (e.g., "echo-instance-abc12") or a common name
  // if multiple instances of the same implementationId are load-balanced.
  // If multiple instances share a name, instance_id_hint becomes more important.
  string instance_service_name = 2;

  // The network-accessible host or IP address of this module instance.
  string host = 3;

  // The port number on which this module instance's gRPC server is listening.
  int32 port = 4;

  // The type of health check this module instance supports.
  HealthCheckType health_check_type = 5;

  // The endpoint for the health check.
  // - For HTTP: Path (e.g., "/health/ready")
  // - For GRPC: Fully qualified service/method (e.g., "grpc.health.v1.Health/Check")
  // - For TCP: Can be empty (host/port from above are used)
  // - For TTL: Not applicable here, interval defined by registration service
  string health_check_endpoint = 6;

  // The MD5 or SHA256 digest of this module instance's custom configuration JSON.
  // This is used by the Yappy Engine to verify configuration consistency.
  string config_digest = 7;

  // Optional: The software version of this module instance (e.g., "1.0.2").
  optional string module_software_version = 8;

  // Optional: A hint for generating a unique instance ID in Consul.
  // If not provided, the Registration Service will generate one.
  // Useful if the module instance already has a stable unique ID (e.g., K8s pod name).
  optional string instance_id_hint = 9;

  // Optional: Any additional tags this module instance suggests for its Consul registration.
  // The Registration Service may add its own standard tags as well.
  map<string, string> additional_tags = 10;
}

// Response message for the RegisterModule RPC.
message RegisterModuleResponse {
  // Indicates if the registration request was successfully processed by the
  // Yappy Registration Service and forwarded to Consul.
  bool success = 1;

  // A human-readable message indicating the outcome or any errors.
  string message = 2;

  // The unique service ID assigned to this module instance by Consul
  // (as registered by the Yappy Registration Service).
  // This can be useful for the module if it needs to perform self-deregistration later.
  string registered_service_id = 3;
}

// Future placeholder messages for DeregisterModule and UpdateModuleStatus
// message DeregisterModuleRequest {
//   string registered_service_id = 1; // ID obtained from RegisterModuleResponse
// }
// message DeregisterModuleResponse {
//   bool success = 1;
//   string message = 2;
// }
//
// message UpdateModuleStatusRequest {
//   string registered_service_id = 1;
//   // Could include current load, specific health status details, etc.
//   // For TTL checks, this would be the heartbeat.
// }
// message UpdateModuleStatusResponse {
//   bool acknowledged = 1;
// }
```

**Key elements in this `.proto` definition:**

*   **`YappyModuleRegistrationService`**: The service definition with an initial `RegisterModule` RPC. Placeholders for future `DeregisterModule` and `UpdateModuleStatus` RPCs are included.
*   **`HealthCheckType` enum**: Clearly defines the supported health check mechanisms (`HTTP`, `GRPC`, `TCP`, `TTL`).
*   **`RegisterModuleRequest`**:
    *   `implementation_id`: Crucial for linking to `PipelineModuleConfiguration`.
    *   `instance_service_name`: The name under which this specific instance will appear in Consul.
    *   `host`, `port`: Network details.
    *   `health_check_type`, `health_check_endpoint`: For Consul health checking.
    *   `config_digest`: The hash of the module's instance-level custom configuration.
    *   `module_software_version` (optional): Good for observability.
    *   `instance_id_hint` (optional): Allows the module to suggest a unique ID.
    *   `additional_tags` (optional): Allows modules to provide extra metadata for Consul.
*   **`RegisterModuleResponse`**:
    *   `success`: Boolean indicating if the registration service accepted the request.
    *   `message`: Human-readable status.
    *   `registered_service_id`: The unique service ID assigned in Consul.



### 5.2. Internal Logic

1.  **Receive `RegisterModuleRequest`**.
2.  **Authentication/Authorization (Future)**: Verify the caller's identity and authority to register.
3.  **Validate `implementationId` (Future)**: Optionally, check if the `implementationId` is known/pre-declared in the YAPPY system (e.g., exists in `PipelineModuleMap`).
4.  **Construct Consul `Registration` Object**:
    *   **Service ID**: Generate a unique ID, possibly using `instance_id_hint` or `implementation_id` + UUID (e.g., `echo-processor-v1-instance-uuid`).
    *   **Service Name**: Use `instance_service_name` from the request. This is what appears in Consul's service catalog.
    *   **Address**: Use `host` from the request.
    *   **Port**: Use `port` from the request.
    *   **Health Check**:
        *   If `GRPC`: Configure a gRPC health check using `health_check_endpoint` (e.g., `fully.qualified.HealthService/Check`) and the module's port. Set `grpcUseTls` appropriately.
        *   If `HTTP`: Configure an HTTP health check to `http://<host>:<port>/<health_check_endpoint>`.
        *   If `TCP`: Configure a TCP check to `<host>:<port>`.
        *   Set interval (e.g., "10s") and deregister critical service after (e.g., "1m").
    *   **Tags**:
        *   `yappy-module:true`
        *   `yappy-module-implementation-id:<request.implementation_id>`
        *   `yappy-config-digest:<request.config_digest>`
        *   If `request.module_software_version` is present: `yappy-module-version:<request.module_software_version>`
        *   Merge any `request.additional_tags`.
5.  **Register with Consul**:
    *   Call `consulBusinessOperationsService.registerService(registration)`.
6.  **Return `RegisterModuleResponse`**: Indicate success/failure and the `registered_service_id`.


### 5.3. Deployment

The Yappy Registration Service can be:
*   A new gRPC service endpoint within the existing Yappy Engine application.
*   A separate Micronaut application dedicated to control plane/admin functions.

## 6. Consul Service Definition for Yappy Modules (Example)

A registered "echo-processor-v1" module instance in Consul might look like:

*   **ID**: `echo-processor-v1-instance-abcdef123456`
*   **Name**: `echo-service-instance-01` (from `request.instance_service_name`)
*   **Address**: `10.0.1.23`
*   **Port**: `50051`
*   **Tags**:
    *   `yappy-module:true`
    *   `yappy-module-implementation-id:echo-processor-v1`
    *   `yappy-config-digest:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6`
    *   `yappy-module-version:1.0.2`
*   **Checks**: (e.g., gRPC health check for `echo-processor-v1-instance-abcdef123456:50051` on `grpc.health.v1.Health/Check`)

## 7. Engine Interaction (Post-Registration)

*   **`ServiceStatusAggregator`**:
    1.  Queries Consul for services tagged with `yappy-module:true`.
    2.  For each discovered service instance:
        *   Extracts the `yappy-module-implementation-id` tag.
        *   Uses this ID to look up the corresponding `PipelineModuleConfiguration` from its cached `PipelineClusterConfig`.
        *   If found, calculates the *expected* configuration digest from `PipelineModuleConfiguration.customConfig` (the `Map<String, Object>` template).
        *   Extracts the `yappy-config-digest` tag from the Consul service instance.
        *   Compares the expected digest with the reported digest.
        *   Sets `ServiceAggregatedStatus.operationalStatus` to `CONFIGURATION_ERROR` if digests mismatch or other configuration issues are detected (e.g., module reports a digest but `PipelineModuleConfiguration` has no `customConfig`).
        *   Populates `ServiceAggregatedStatus.reportedModuleConfigDigest`.
*   **`GrpcChannelManager`**:
    1.  When a pipeline step needs to be executed, `PipelineStepConfig.processorInfo.grpcServiceName` (which *is* an `implementationId`) is used.
    2.  The `GrpcChannelManager` discovers services in Consul. It should ideally use the `grpcServiceName` (as the Consul service name convention if modules register that way) AND/OR filter by the `yappy-module-implementation-id` tag to ensure it's connecting to the correct type of module.
        *   *Refinement*: If modules register with a unique `instance_service_name` (e.g., `echo-instance-01`), then discovery by `implementationId` tag becomes essential. The `grpcServiceName` in `ProcessorInfo` would map to this `implementationId`.

## 8. Practical Implementation Steps (for YAPPY Platform Developers)

1.  **Define Registration API (gRPC Proto)**:
    *   Create `yappy_module_registration_service.proto` with `RegisterModuleRequest` and `RegisterModuleResponse` as outlined above.
2.  **Implement Yappy Registration Service**:
    *   Create a new Micronaut gRPC service (e.g., `YappyModuleRegistrationServiceImpl`) that implements the generated base from the proto.
    *   Inject `ConsulBusinessOperationsService`.
    *   Implement the `registerModule` RPC method:
        *   Translate `RegisterModuleRequest` into a `org.kiwiproject.consul.model.agent.Registration` object.
        *   Pay close attention to constructing the `Registration.Check` correctly based on `HealthCheckType`.
        *   Call `consulBusinessOperationsService.registerService()`.
3.  **Module-Side Configuration and Call (Guidance & Examples)**:
    *   **Configuration**: Document that modules need to be started with:
        *   `YAPPY_IMPLEMENTATION_ID` (environment variable).
        *   `YAPPY_MODULE_INSTANCE_CONFIG_JSON` (environment variable containing the JSON string of their instance-level custom config, which should match the template from `PipelineModuleConfiguration.customConfig`).
        *   Their own gRPC server host/port, health check details.
    *   **Digest Calculation**: Provide a simple code snippet (e.g., Python, Java) showing how to:
        *   Parse the `YAPPY_MODULE_INSTANCE_CONFIG_JSON` string.
        *   Sort keys if it's a map/dictionary.
        *   Serialize back to a canonical JSON string.
        *   Calculate MD5 or SHA256 hash.
    *   **API Call**: Provide an example of using a standard gRPC client in a target language (e.g., Python) to call the `YappyModuleRegistrationService.RegisterModule` RPC.
4.  **Update `ServiceStatusAggregator`**:
    *   Modify `buildStatusForService` to:
        *   Extract `yappy-module-implementation-id` from Consul service tags.
        *   Use this ID to get the `PipelineModuleConfiguration` from the cached `PipelineClusterConfig`.
        *   If `PipelineModuleConfiguration.customConfig` is present, calculate its digest (ensure canonical serialization, e.g., using `ObjectMapper` with sorted keys, then hash). This is the *expected digest*.
        *   Extract `yappy-config-digest` from the Consul service tags (this is the *reported digest*).
        *   Compare expected vs. reported. If different, set status to `CONFIGURATION_ERROR` and add details to `errorMessages` and `statusDetail`.
        *   Store the `reportedModuleConfigDigest` in `ServiceAggregatedStatus`.
5.  **Update `GrpcChannelManager` (Refinement)**:
    *   When discovering services for a `grpcServiceName` (which is an `implementationId`):
        *   The `DiscoveryClient.getInstances(serviceName)` call might target a generic service name pattern if modules don't register with their `implementationId` as the primary Consul service name.
        *   It's more robust if `GrpcChannelManager` filters the discovered `ServiceInstance` list to only include those that have a `yappy-module-implementation-id` tag matching the required `implementationId`. This ensures the engine connects to the correct *type* of module, even if multiple module types happen to share a less specific Consul service name.
        *   *Decision Point*: Do modules register in Consul with their `implementationId` as the primary service name, or a more generic instance name with `implementationId` as a tag? Using `implementationId` as the Consul service name simplifies discovery for the engine. If using instance names, tag-based filtering is essential.
6.  **Documentation & Project Generator Updates**:
    *   Create clear documentation for module developers on the registration process, required configurations, and how to call the Registration API.
    *   If you have project generators, update them to include:
        *   Placeholders for `YAPPY_IMPLEMENTATION_ID` and `YAPPY_MODULE_INSTANCE_CONFIG_JSON`.
        *   A basic script/main function example showing how to calculate the digest and call the registration API.
        *   A standard gRPC health check implementation.

## 9. Security Considerations (Future Work)

*   The Yappy Registration Service API should eventually be secured (e.g., mTLS, OAuth2 client credentials for modules).
*   Communication between the Engine and modules should also be secured.

## 10. Future Enhancements

*   Admin approval workflow for module registrations.
*   `DeregisterModule` API in the Registration Service.
*   More sophisticated health check configurations derived from module metadata.
*   CLI for Yappy platform administration, including module type definition and registration management.

This plan provides a clear path to a more robust, language-agnostic module registration system. The key is the "Yappy Registration Service" acting as the well-defined interface between modules and the YAPPY platform's internal use of Consul.


