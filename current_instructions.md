# YAPPY Engine: Comprehensive Development and Integration Plan

**Overall Status Update:** The Yappy Engine's startup has been significantly stabilized. Critical issues like `StackOverflowError` during dependency injection, multiple singleton initializations on the main thread, and `RejectedExecutionException` from the Consul KVCache during startup have been resolved. This was primarily achieved by correcting the application's build configuration to be a Micronaut `application` (not `library`) and by implementing a "deferred event publication" strategy in `DynamicConfigurationManagerImpl` for its initial configuration load. `DefaultConfigurationSeeder` helps ensure a baseline configuration can exist. `KafkaListenerManager` now correctly resolves its dependencies and receives configuration change events. Unit tests for `DynamicConfigurationManagerImpl` and integration tests for `KafkaListenerManager` (confirming Apicurio property resolution in a test context) are passing. The immediate next steps involve ensuring correct Apicurio property resolution in the running application and then end-to-end testing of dynamic Kafka listener functionality with actual message processing.

## I. Foundational Principle: User-Centric Design & Operational Excellence

This plan aims to create a YAPPY Engine that is not only powerful and flexible but also easy to set up, configure, monitor, and manage. We prioritize a smooth initial bootstrapping experience ("Easy Peasy" setup) which then enables more advanced operational capabilities like detailed status reporting, robust service lifecycle management, and comprehensive health checking.

**Architectural Note:** The Yappy Engine operates with a clear distinction between the Engine itself and the Modules it manages. Modules are language-agnostic and do not have awareness of Consul or the Engine's registration mechanisms. The Engine is responsible for all Consul registrations on behalf of the modules it is configured to manage.

## II. Part A: Initial Engine Bootstrapping & Cluster Association (The "Easy Peasy" Plan)

This part focuses on getting a YAPPY Engine instance minimally operational and connected to its coordination layer (Consul) and an active YAPPY cluster configuration.

**Status Update:** This phase is largely successful and stable. The engine reliably connects to Consul (using standard Micronaut configuration: `application.yml`, environment variables) and `DynamicConfigurationManagerImpl` loads its active cluster configuration. The "Setup Mode" triggered by the absence of `~/.yappy/engine-bootstrap.properties` (handled by `EngineBootstrapManager`) primarily pertains to setting explicit Consul connection parameters if they are not available through standard means, and for interactively managing cluster definitions/selection via the `BootstrapConfigService` gRPC interface.

### Phase A.1: Consul Connection Bootstrap – "Hello, Consul!"

1.  **Engine Startup: The "Are We Talking to Consul Yet?" Check**
    * **Status:** Implemented. The engine relies on standard Micronaut configuration for Consul details. `ConsulClientFactory`, `ConsulBusinessOperationsService`, and `ConsulConfigFetcher` initialize based on these.
    * **If Configured:** Proceeds to Phase A.2.
    * **If NOT Configured (fundamental host/port missing):** Micronaut's own Consul client initialization will fail, preventing full startup. The `EngineBootstrapManager` also logs if its specific bootstrap file is missing, offering a path to set these via the gRPC API.

2.  **"Setup Mode": Getting Consul Details (for `engine-bootstrap.properties`)**
    * **Status:** `BootstrapConfigServiceImpl` gRPC service exists for this.
    * **API-Driven (Primary):** `BootstrapConfigService.SetConsulConfiguration(ConsulConfigDetails) returns (ConsulConnectionStatus)`.
    * **(Optional) UI for Dev/Testing:** (Future)

3.  **Making the Connection & Remembering It (via `engine-bootstrap.properties`)**
    * **Status:** `BootstrapConfigServiceImpl` implements saving to `engine-bootstrap.properties`.
    * A restart is generally recommended if these bootstrap properties are changed to ensure all Micronaut components pick up new fundamental Consul connection settings.

### Phase A.2: Yappy Cluster Initialization & Configuration Loading – "Which Party Are We Joining?"


*(This phase executes once the engine has successfully connected to Consul)*

**Note on Cluster Names & Configuration:**
* The primary active cluster for an engine instance is determined by the `app.config.cluster-name` property (from `application.yml` or other Micronaut configuration sources).
* `DefaultConfigurationSeeder` runs at startup to ensure that this default/active cluster has at least a minimal valid `PipelineClusterConfig` present in Consul if one doesn't already exist.
* The `BootstrapConfigService` gRPC interface allows for listing other clusters present in Consul, selecting a different cluster (by updating `engine-bootstrap.properties`, which would then influence `app.config.cluster-name` on next effective load), and creating new clusters (which seeds a minimal config via `ConsulBusinessOperationsService`).

1.  **Cluster Choice Time & Management: API**
    * **Status:** `BootstrapConfigService` (gRPC) provides:
        * `ListAvailableClusters`
        * `SelectExistingCluster`
        * `CreateNewCluster`
    * These primarily interact with Consul (via `ConsulBusinessOperationsService`) and the `engine-bootstrap.properties` file.

2.  **Planting the Seed for a New Cluster (via `CreateNewCluster` or `DefaultConfigurationSeeder`)**
    * **Status:** Implemented. Both `DefaultConfigurationSeeder` (for the default active cluster) and `BootstrapConfigServiceImpl.createNewCluster()` (for interactively creating new ones) use `ConsulBusinessOperationsService` to write a default, minimal, valid `PipelineClusterConfig` to Consul (e.g., under `yappy/pipeline-configs/clusters/<cluster-name>`).
    * **Seed Configuration Details:** Minimal (empty graphs/maps, default names, empty allowed lists).


### Phase A.3: Showtime! – Transition to Normal Operation

**Status Update:** This phase is now stable and working as expected.
1.  **Full Steam Ahead**
    * The engine performs its full startup.
    * `DynamicConfigurationManagerImpl` (DCM) loads the `PipelineClusterConfig` for its `effectiveClusterName` from Consul. Its initial event publication is deferred until after its `@PostConstruct` completes, preventing DI loops.
    * The Engine instance itself registers with Consul (as per your clarification). *(This needs to be implemented/verified if it's not already automatic via Micronaut's `consul.client.registration.enabled=true` for the engine app itself).*
    * `KafkaListenerManager` receives the configuration event (asynchronously) and will attempt to start listeners if defined in the loaded `PipelineClusterConfig`.
    * Other operational components are initialized.
    * The "Setup Mode" (from `EngineBootstrapManager`) is secondary to the primary configuration loading path if `application.yml` provides sufficient details.

## III. Part B: Enhanced Engine Operations within a Cluster

*(This part assumes the engine has successfully completed Part A and is operating with a loaded Yappy cluster configuration)*

### Phase B.1: Define Core Status Models & Schema

1.  **`ServiceOperationalStatus.java` (Enum):**
    *   Implement the enum with states: `UNKNOWN`, `DEFINED`, `INITIALIZING`, `AWAITING_HEALTHY_REGISTRATION`, `ACTIVE_HEALTHY`, `ACTIVE_PROXYING`, `DEGRADED_OPERATIONAL`, `CONFIGURATION_ERROR` (covering `BAD_SCHEMA`, `BAD_CONFIG`), `UNAVAILABLE`, `UPGRADING`, `STOPPED`.
2.  **`ServiceAggregatedStatus.java` (Java Record):**
    *   Implement the record with fields: `serviceName`, `operationalStatus`, `statusDetail`, `lastCheckedByEngineMillis`, `totalInstancesConsul`, `healthyInstancesConsul`, `isLocalInstanceActive`, `activeLocalInstanceId`, `isProxying`, `proxyTargetInstanceId`, `isUsingStaleClusterConfig`, `activeClusterConfigVersion`, `reportedModuleConfigDigest`, `errorMessages`, `additionalAttributes`.
    *   This record will be serialized to JSON for storage in Consul KV.
3.  **Schema for `ServiceAggregatedStatus`:**
    *   Generate and document the JSON schema for `ServiceAggregatedStatus` for API consumers.

### Phase B.2: Implement Engine Logic for Status Management

1.  **Develop Engine Component for Status Aggregation:**
    *   This component will:
        *   Monitor Consul service health and registrations (via `ConsulBusinessOperationsService`).
        *   Observe the state of any locally managed modules.
        *   Track the active `PipelineClusterConfig` version (from `DynamicConfigurationManager`).
        *   Detect `BAD_SCHEMA` (e.g., schema registry issues, unparsable schemas from Consul) and `BAD_CONFIG` (module config failing validation against its schema, potentially reported by the module's own health check).
        *   Calculate `ServiceOperationalStatus` and populate `ServiceAggregatedStatus` for each logical service in `PipelineClusterConfig.pipelineModuleMap`.
2.  **Consul KV Updates for Status:**
    *   The engine will periodically (or event-driven on relevant changes) update the `ServiceAggregatedStatus` JSON object in Consul KV at a path like `yappy/status/services/{logicalServiceName}` using `ConsulBusinessOperationsService.putValue()`.

### Phase B.3: Enhance Engine Logic for Service Registration & Lifecycle

1.  **Implement "Bootstrap Cluster" Refinement (Engine Self-Healing for Empty Config):**
    * **Status:** This is effectively handled by `DefaultConfigurationSeeder`.

2.  **Implement Engine-Managed Module Registration:**
    * **Clarified Requirement:** Modules DO NOT self-register. The Yappy Engine is SOLELY responsible for registering its paired/configured module with Consul. An Engine instance is "tied" to a specific module type it's configured to manage.
    * **Engine's Role:**
        * The Engine knows which module `implementationId` it's responsible for (from its own configuration or the `PipelineClusterConfig.pipelineModuleMap` if the engine manages multiple *types* which seems less likely given "tied to only one module").
        * The Engine polls a configured `host:port` for its paired module's health/availability.
        * Once the module is "seen" and validated (config digest, schema checks etc. by the Engine), the Engine constructs a Consul `Registration` object and calls `ConsulBusinessOperationsService.registerService()`.
        * The Engine will also handle deregistration via `ConsulBusinessOperationsService.deregisterService()` when it determines its paired module should be stopped or is gone.
    * **Pre-registration Health Check:** Engine pings module's health endpoint. (To be implemented)
    * **Authentication Stub:** (To be implemented)

3.  **Implement Proxying Logic (Revised Understanding):**
    * If an Engine's *paired local module* is `UNAVAILABLE` or in `CONFIGURATION_ERROR`:
        * The Engine queries Consul for other healthy remote instances of the *same logical module service name* (i.e., same `implementationId`).
        * If found, the Engine routes traffic to a healthy remote instance.
        * Update the service's `ServiceAggregatedStatus` in KV to `ACTIVE_PROXYING`.
    * An Engine instance will *not* by default proxy to a different module type if its paired module is down.

### Phase B.4: Implement Health Check Integration

1.  **Engine Health Indicator:**
    *   Create a Micronaut `HealthIndicator` for the YAPPY Engine.
    *   Its health check will verify:
        *   Consul client connectivity and availability.
        *   `DynamicConfigurationManager` status (e.g., current `PipelineClusterConfig` is loaded, valid, and not unexpectedly stale).
        *   Aggregated health of critical services required by *active* pipelines (by reading from `yappy/status/services/*` KV).
2.  **Module Health Endpoint Mandate & Consumption:**
    *   Formalize the requirement that all YAPPY modules (gRPC services) expose a standard health check endpoint (HTTP or gRPC Health Checking Protocol).
    *   This module health check *must* include the status of its own configuration (i.e., is its `customConfigJson` valid against its declared schema?). If the module's config is bad, it must report itself as unhealthy.
    *   The engine's status aggregation component (Phase B.2) will use this information when determining `ServiceOperationalStatus`.

### Phase B.5: Expose Status and Configuration via API (Initial Focus on JSON/HTTP for UI)

**Status Update:** The API endpoints listed below are candidates for Jules AI to implement next, focusing on leveraging existing backend services.
1.  **Create REST Controllers in the Engine:**
    * `GET /api/status/engine`
    * `GET /api/status/services` & `GET /api/status/services/{serviceName}` (depends on B.2)
    * `GET /api/config/cluster` (calls DCM)
    * `PUT /api/config/cluster` (calls DCM/ConsulBusinessOps to save, then relies on watch)
    * Kafka Consumer Management APIs (Pause/Resume/Reset Offsets, List Statuses - calling `KafkaListenerManager`) (See Section VII Task 1)
    * Module Management APIs (List configured, Get Status, Trigger Register/Deregister - calling DCM and `ConsulBusinessOperationsService` via a new `ModuleLifecycleManagerService`) (See Section VII Task 2)

### Phase B.6: Testing for Enhanced Operations

1.  **Unit Tests:**
    * **Status:** `DynamicConfigurationManagerImplTest` is passing. `DefaultPipeStreamEngineLogicImplTest` needs completion. Tests for `KafkaListenerManager`'s synchronization logic are needed.
2.  **Integration Tests:**
    * **`KafkaListenerManagerIntegrationTest`:** **PASSING!** Confirms Apicurio properties are correctly processed in the test environment.
    * **Next Integration Test:** Focus on end-to-end Kafka listener functionality in the running application (verifying Apicurio settings from `application-dev.yml` and message consumption). (See Section VII Task 1)
    * Then, integration tests for Engine-Managed Module Registration. (See Section VII Task 2)

## IV. Parallel Tasks for Coworker (Supporting Parts A & B)

While the core engine logic for the above phases is being developed, a coworker can proceed with:

1.  **Define Setup & Status APIs (Proto/OpenAPI First):**
    *   Draft gRPC service definitions (`.proto`) or OpenAPI specifications for:
        *   `BootstrapConfigService` (from Phase A.1, A.2).
        *   The REST APIs for status and configuration (from Phase B.5).
    *   Define message types for `ConsulConfigDetails`, `ConsulConnectionStatus`, `ClusterList`, `ClusterSelection`, `NewClusterDetails`, `ClusterCreationStatus`, `OperationStatus`, and the structure for `ServiceAggregatedStatus` (aligning with the Java Record from B.1).
2.  **Sketch out Minimal UI for Setup:**
    *   HTML mockups/wireframes for the Consul configuration form and the cluster selection/creation page (supporting Phase A.1, A.2).
3.  **Define Default Seed `PipelineClusterConfig` Structure:**
    *   Document the exact JSON/YAML structure for a minimal, valid `PipelineClusterConfig` used for seeding new clusters (supports Phase A.2.2 and B.3.1). Specify what "empty but valid" means for all its sub-components.
4.  **Research & Plan Bootstrap Config Persistence Mechanism:**
    *   Finalize the local file format (properties, YAML, JSON), default path (e.g., `~/.yappy/engine-bootstrap.conf`), permission considerations, and how Micronaut will load this *very* early in its bootstrap sequence (supports Phase A.1.3).
5.  **(Optional) Design Basic CLI Tool Interface for Setup:**
    *   Outline commands for `yappy-engine setup consul ...` and `yappy-engine setup cluster ...` (supports Phase A).
6.  **Implement Core Status Models (Phase B.1):**
    *   The coworker can implement `ServiceOperationalStatus.java` and `ServiceAggregatedStatus.java` once the fields are agreed upon.

## V. Future Integration Testing & Advanced Features (Beyond Initial Setup & Core Operations)

This section covers the broader testing and feature development outlined in the original "Next Steps for YAPPY Project Integration Testing" and "Future Steps" sections of `current_instructions.md`. These will build upon the stable foundation established by Parts A and B.

1.  **Integration Tests with Actual Modules (Echo & Chunker):**
    *   Adapt existing integration tests or create new ones in `yappy-engine` to use the *actual* Echo and Chunker module implementations from the `yappy-modules` directory, rather than mocks. This will involve managing multiple service contexts within a single test, likely using Testcontainers for shared infrastructure (Consul, Kafka, Schema Registry).
    *   **Test Configuration Management:** Explore using dedicated test YAML files per service context (e.g., `application-test-echo.yml`) for clarity.
2.  **Test Kafka Serialization of `PipeStream` Objects:**
    *   Implement the Kafka serialization/deserialization test for `PipeStream` as outlined.
3.  **Test End-to-End gRPC Flow (Engine Orchestrating Modules):**
    *   Create tests where the YAPPY Engine receives a request (e.g., via `processPipe` or `processConnectorDoc`) and orchestrates calls to actual Echo and Chunker modules discovered via Consul.
4.  **Test Combined Kafka and gRPC Flow:**
    *   Implement the test for a pipeline that involves both Kafka message passing and gRPC calls between steps, using actual modules.
5.  **Schema Registry Integration & Testing:**
    *   Ensure schemas are pre-registered or registered during test setup.
    *   Test Kafka flows that require schema validation (Apicurio/Glue), ensuring all service contexts point to the same Testcontainer-managed schema registry.
6.  **Admin API Usage in Tests:**
    *   As Admin APIs for managing `PipelineClusterConfig`, `PipelineConfig`, module registration, etc., are developed (from "Admin Features Left to Implement"), incorporate their use into integration tests for setup and configuration, rather than always seeding Consul directly.
7.  **Advanced Feature Implementation & Testing (from original document):**
    *   **Admin Features:** Module validation service, module registration API, containerization strategies, live config updates for modules, module health monitoring.
    *   **Observability:** Metrics, distributed tracing, logging, alerting, dashboards.
    *   **Search Features:** Search API, analytics, white-labeling.
    *   **Pipeline Editor & Dashboard.**
    *   **Connectors & Sinks:** Python examples, Wikipedia/Common Crawl connectors, OpenSearch/Docstore sinks.
    *   **Error Handling, Idempotency, Retries, Security.**

This unified plan should provide a clear roadmap, allowing for parallel work where appropriate and ensuring that foundational pieces are in place before building more complex features on top.

---
## VI. Current Focus & Next Development Tasks for Yappy Engine

This section consolidates the immediate next steps.

1.  **Verify Apicurio Property in Running `KafkaListenerManager`:**
    * **Status:** Pending final verification in main application run.
    * **Action:** Add diagnostic logging to `KafkaListenerManager` constructor (inject `Environment`, log active profiles, log `@Value` field vs. manual `applicationContext.getProperty("kafka.schema.registry.type", String.class)`). Run the main application. If `@Value` is still problematic, set the field using `applicationContext.getProperty()`.
    * **Goal:** Ensure `KafkaListenerManager` logs `schema registry type: 'apicurio'` in the running application.

2.  **Integration Test: End-to-End Dynamic Kafka Listener Functionality:**
    * **Status:** Next new test to be developed.
    * **Action:** Create an integration test (`DynamicKafkaListenerE2ETest.java`).
        * Setup: `@MicronautTest`, use Testcontainers for Consul/Kafka/Apicurio. Seed `PipelineClusterConfig` with a Kafka input step into test Consul.
        * Test: Start Yappy Engine. Verify `KafkaListenerManager` creates a listener with correct Apicurio properties. Produce a Protobuf message. Verify consumption and processing by `DefaultPipeStreamEngineLogicImpl`.
    * **Goal:** Confirm dynamic Kafka listeners work end-to-end with Apicurio deserialization in the main application context.

3.  **Implement Engine-Managed Module Registration (API & Logic - Task for Jules AI):**
    * This is the next major feature for the "registration goal" based on clarified requirements.
    * See **Section VII: Detailed Task for Jules AI** below.

4.  **Unit Test `DefaultPipeStreamEngineLogicImpl`:**
    * **Status:** Pending/In Progress.
    * **Action:** Complete the unit tests for `DefaultPipeStreamEngineLogicImpl`, covering its core processing logic, retries, error handling, and routing calculations.

## Future Steps

### 3. Test Kafka Serialization of PipeStream Objects

Create a test that demonstrates serializing and deserializing PipeStream objects through Kafka:

```java
@Test
void testKafkaSerialization() {
    // Create a PipeStream object with test data
    PipeStream pipeStream = PipeStream.newBuilder()
        .setStreamId("test-stream-" + System.currentTimeMillis())
        .setCurrentPipelineName("test-pipeline")
        .setTargetStepName("echo-step")
        .setDocument(createTestDocument())
        .build();

    // Produce to Kafka
    ProducerRecord<String, PipeStream> record = 
        new ProducerRecord<>("test-pipeline-input", pipeStream.getStreamId(), pipeStream);
    kafkaProducer.send(record).get();

    // Consume from Kafka
    kafkaConsumer.subscribe(Collections.singletonList("test-pipeline-input"));
    ConsumerRecords<String, PipeStream> records = kafkaConsumer.poll(Duration.ofSeconds(10));

    // Verify
    assertFalse(records.isEmpty(), "Should have received records");
    PipeStream receivedPipeStream = records.iterator().next().value();
    assertEquals(pipeStream.getStreamId(), receivedPipeStream.getStreamId());
    assertEquals(pipeStream.getCurrentPipelineName(), receivedPipeStream.getCurrentPipelineName());
    assertEquals(pipeStream.getTargetStepName(), receivedPipeStream.getTargetStepName());
    assertEquals(pipeStream.getDocument().getId(), receivedPipeStream.getDocument().getId());
}
```

### 4. Test End-to-End gRPC Flow

Create a test that demonstrates the full pipeline flow using gRPC:

```java
@Test
void testEndToEndGrpcFlow() {
    // Create a client for the Echo service
    ManagedChannel echoChannel = ManagedChannelBuilder
        .forAddress("localhost", echoServer.getPort())
        .usePlaintext()
        .build();
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClient = 
        PipeStepProcessorGrpc.newBlockingStub(echoChannel);

    // Create a client for the Chunker service
    ManagedChannel chunkerChannel = ManagedChannelBuilder
        .forAddress("localhost", chunkerServer.getPort())
        .usePlaintext()
        .build();
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient = 
        PipeStepProcessorGrpc.newBlockingStub(chunkerChannel);

    // Create a test document
    PipeDoc testDoc = createTestDocument();

    // Create a request for the Echo service
    ProcessRequest echoRequest = createProcessRequest("echo-step", testDoc);

    // Call the Echo service
    ProcessResponse echoResponse = echoClient.processData(echoRequest);
    assertTrue(echoResponse.getSuccess());

    // Call the Chunker service with the Echo response
    ProcessRequest chunkerRequest = createProcessRequest("chunker-step", echoResponse.getOutputDoc());
    ProcessResponse chunkerResponse = chunkerClient.processData(chunkerRequest);
    assertTrue(chunkerResponse.getSuccess());

    // Verify the Chunker response contains semantic results
    assertTrue(chunkerResponse.getOutputDoc().getSemanticResultsCount() > 0);
}
```

### 5. Test Combined Kafka and gRPC Flow

Create a test that demonstrates both Kafka and gRPC working together:

```java
@Test
void testKafkaAndGrpcFlow() {
    // Create a PipeStream object
    PipeStream pipeStream = createTestPipeStream();

    // Send to Kafka
    kafkaProducer.send(new ProducerRecord<>("test-pipeline-input", pipeStream.getStreamId(), pipeStream)).get();

    // Set up a consumer for the output topic
    kafkaConsumer.subscribe(Collections.singletonList("test-pipeline-output"));

    // Process should happen asynchronously through the pipeline

    // Verify the result in the output topic
    ConsumerRecords<String, PipeStream> records = kafkaConsumer.poll(Duration.ofSeconds(30));
    assertFalse(records.isEmpty(), "Should have received records");

    // Verify the final document has been processed by both Echo and Chunker
    PipeStream result = records.iterator().next().value();
    assertTrue(result.getDocument().getSemanticResultsCount() > 0, "Document should have semantic results from Chunker");
}
```

---
## VII. Detailed Task for Jules AI: Implement APIs for Engine-Managed Module Information & Registration Control

**Background:**
The Yappy Engine is now responsible for registering its paired/managed modules with Consul. Modules themselves are passive and do not self-register. We need API endpoints on the Yappy Engine to:
a. Allow an administrator/operator to see which modules the Engine is configured to manage.
b. View the current Consul registration status of these configured modules.
c. Trigger the Engine to attempt to register (or deregister) a *specific instance* of a configured module with Consul. This requires the caller to provide host/port details for the module instance, as the module itself doesn't announce its location to the Engine directly for registration purposes.

**Objective:**
Implement REST API endpoints in the Yappy Engine to provide visibility into configured modules and allow an operator to trigger Consul registration/deregistration for specific module *instances* that the Engine is configured to be aware of.

**Prerequisites (Assumed Backend Components):**
* `DynamicConfigurationManager` (DCM): Provides the current `PipelineClusterConfig`, which includes the `PipelineModuleMap` (the list of *defined* modules).
* `ConsulBusinessOperationsService`: Has methods like `registerService(Registration details)`, `deregisterService(String serviceId)`, and `getServiceInstances(String serviceName)`.

**API Endpoints to Implement:**

1.  **List Configured Modules:**
    * **Endpoint:** `GET /api/admin/modules/definitions`
    * **Controller Method:**
        ```java
        @Get("/definitions")
        public Map<String, PipelineModuleConfiguration> getConfiguredModules() {
            Optional<PipelineClusterConfig> clusterConfigOpt = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
            if (clusterConfigOpt.isPresent() && clusterConfigOpt.get().pipelineModuleMap() != null) {
                return clusterConfigOpt.get().pipelineModuleMap().availableModules();
            }
            return Collections.emptyMap();
        }
        ```
    * **Dependencies:** Inject `DynamicConfigurationManager`.
    * **Response:** JSON map of `implementationId` to `PipelineModuleConfiguration`.
    * **Purpose:** Shows what module types are defined in the engine's current cluster configuration.

2.  **View Status of Configured Modules in Consul:**
    * **Endpoint:** `GET /api/admin/modules/status`
    * **Controller Method:**
        ```java
        @Get("/status")
        public List<ModuleConsulStatus> getConfiguredModulesStatus() {
            // ... (logic from previous API Task 2.2) ...
            // 1. Get PipelineModuleMap from DCM.
            // 2. For each module definition (implementationId) in the map:
            //    a. Query Consul using ConsulBusinessOperationsService.getHealthyServiceInstances(implementationId).
            //    b. Construct a ModuleConsulStatus object.
            // Return List<ModuleConsulStatus>.
        }
        // Define a DTO: 
        // record ModuleConsulStatus(String implementationId, String implementationName, boolean definedInConfig, String consulRegistrationStatus, int healthyInstanceCount) {}
        ```
    * **Dependencies:** Inject `DynamicConfigurationManager`, `ConsulBusinessOperationsService`.
    * **Response:** JSON array of `ModuleConsulStatus` objects.
    * **Purpose:** Shows which defined modules have active, healthy registrations in Consul.

3.  **Trigger Engine to Register a Specific Module Instance:**
    * **Endpoint:** `POST /api/admin/modules/instances/register`
    * **Request Body (JSON):**
        ```json
        {
          "implementationId": "echo-v1", // Must match an ID in PipelineModuleMap
          "instanceId": "echo-v1-instance-01", // Unique ID for this physical instance
          "host": "10.0.1.23",
          "port": 50052,
          "healthCheckType": "HTTP", // e.g., HTTP, GRPC, TCP
          "healthCheckEndpoint": "/health", // e.g., "/health", "serviceName/method", or "host:port" for TCP
          "healthCheckInterval": "10s",
          "healthCheckTimeout": "5s",
          "tags": ["version=1.0", "env=prod"] // Optional additional tags
        }
        ```
    * **Controller Method:**
        ```java
        @Post("/instances/register")
        public HttpResponse<Map<String, Object>> registerModuleInstance(@Body ModuleRegistrationRequest request) {
            // 1. Validate request.
            // 2. Optional: Validate request.implementationId against PipelineModuleMap from DCM.
            // 3. Construct org.kiwiproject.consul.model.agent.Registration object:
            //    - Registration.Address: request.host
            //    - Registration.Port: request.port
            //    - Registration.Name: request.implementationId (this is the Consul Service Name)
            //    - Registration.Id: request.instanceId (this is the Consul Service ID, must be unique per agent)
            //    - Registration.Tags: Add "yappy-module-implementation-id="+request.implementationId and request.tags
            //    - Registration.Check: Create appropriate Registration.RegCheck based on request.healthCheckType/Endpoint/Interval/Timeout
            //       (e.g., Registration.RegCheck.http(url, interval, timeout), .grpc(grpc, interval), .tcp(tcp, interval, timeout))
            // 4. Call consulBusinessOperationsService.registerService(consulRegistration).
            // 5. Return success/failure.
        }
        // Define DTO: ModuleRegistrationRequest based on JSON above.
        ```
    * **Dependencies:** Inject `DynamicConfigurationManager` (optional for validation), `ConsulBusinessOperationsService`.
    * **Response:** `HttpResponse<Map<String, Object>>` e.g., `{ "success": true, "message": "Module instance submitted for registration.", "serviceIdRegistered": "echo-v1-instance-01" }`
    * **Purpose:** Allows an operator to explicitly tell the Engine: "There's an instance of module 'echo-v1' running at this host/port; please register it in Consul with this health check."

4.  **Trigger Engine to Deregister a Specific Module Instance:**
    * **Endpoint:** `POST /api/admin/modules/instances/{consulServiceId}/deregister`
    * **Path Variable:** `consulServiceId` (This is the unique ID used when registering the instance, e.g., "echo-v1-instance-01").
    * **Controller Method:**
        ```java
        @Post("/instances/{consulServiceId}/deregister")
        public HttpResponse<Map<String, Object>> deregisterModuleInstance(@PathVariable String consulServiceId) {
            // 1. Call consulBusinessOperationsService.deregisterService(consulServiceId).
            // 2. Return success/failure.
        }
        ```
    * **Dependencies:** Inject `ConsulBusinessOperationsService`.
    * **Response:** JSON: `{ "success": boolean, "message": "string" }`
    * **Purpose:** Removes a specific module instance's registration from Consul via the Engine.

**OpenAPI/Swagger Documentation:**
* All implemented REST endpoints must include comprehensive OpenAPI annotations (`@Operation`, `@Parameter`, `@RequestBody`, `@ApiResponse`, etc.) to generate interactive API documentation.

**Testing These APIs:**
* Write integration tests (`@MicronautTest`) for these new controller endpoints.
* Use Testcontainers for Consul.
* Mock `ConsulBusinessOperationsService` to verify it's called with the correct `Registration` details or `serviceId` for deregistration.
* For `GET` endpoints, seed Consul (via a real `ConsulBusinessOperationsService` connected to Testcontainers Consul) with test data and verify the API returns the expected module definitions or statuses.

This set of tasks provides a clear path for implementing the API layer for engine-managed module registration, which is your clarified next big step for the registration goal.
