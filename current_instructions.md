# YAPPY Engine: Comprehensive Development and Integration Plan

## I. Foundational Principle: User-Centric Design & Operational Excellence

This plan aims to create a YAPPY Engine that is not only powerful and flexible but also easy to set up, configure, monitor, and manage. We prioritize a smooth initial bootstrapping experience ("Easy Peasy" setup) which then enables more advanced operational capabilities like detailed status reporting, robust service lifecycle management, and comprehensive health checking.

## II. Part A: Initial Engine Bootstrapping & Cluster Association (The "Easy Peasy" Plan)

This part focuses on getting a YAPPY Engine instance minimally operational and connected to its coordination layer (Consul) and a YAPPY cluster.

### Phase A.1: Consul Connection Bootstrap – "Hello, Consul!"

1.  **Engine Startup: The "Are We Talking to Consul Yet?" Check**
    *   On boot, the engine checks for Consul connection details (host, port, ACL token) from standard Micronaut configuration sources (env vars, `application.yml`, etc.).
    *   **If Configured:** Proceed to Phase A.2.
    *   **If NOT Configured:**
        *   Log a user-friendly message indicating setup mode.
        *   Activate a minimal "Setup Mode," deferring initialization of Consul-dependent components (e.g., `DynamicConfigurationManager`) to prevent startup failures.

2.  **"Setup Mode": Getting Consul Details**
    *   **API-Driven (Primary):** Expose a simple, unauthenticated API for providing Consul configuration.
        *   **gRPC Option:** `BootstrapConfigService.SetConsulConfiguration(ConsulConfigDetails) returns (ConsulConnectionStatus)`
        *   **HTTP Option:** `POST /setup/consul` with JSON payload.
    *   **(Optional) UI for Dev/Testing:** A lightweight web form (served by the engine in setup mode) that calls the above API.
        *   Inputs: Consul Host & Port, ACL Token, other essential client settings.

3.  **Making the Connection & Remembering It**
    *   Upon receiving Consul details via the API:
        *   Attempt to connect/ping the provided Consul instance.
        *   **If Successful:**
            *   **Persist Settings:** Save the validated Consul connection details to a local bootstrap configuration file (e.g., `~/.yappy/engine-bootstrap.properties` or a configurable path). This file will be loaded by Micronaut on subsequent startups *before* other configurations.
            *   Log success and return a success status via the API.
            *   The engine may then attempt to re-initialize Consul-dependent components or signal that a restart is required to use the new bootstrap configuration.
        *   **If Unsuccessful:** Return an error status via the API, allowing the user to retry.

### Phase A.2: Yappy Cluster Initialization – "Which Party Are We Joining?"

*(This phase executes once the engine has successfully connected to Consul via Phase A.1)*

**Note on Cluster Names:** There is no default "yappy-default" cluster name in the system. The cluster name must be explicitly configured through one of these methods:
* The `app.config.cluster-name` property in application.yml
* During the bootstrap process when selecting or creating a cluster
* The `YAPPY_BOOTSTRAP_CLUSTER_SELECTED_NAME` property in the bootstrap file

1.  **Cluster Choice Time: API & (Optional) UI**
    *   Expose API endpoints for cluster management:
        *   **gRPC Option:** `BootstrapConfigService`
            *   `ListAvailableClusters(Empty) returns (ClusterList)`: Queries Consul (e.g., lists keys under `pipeline-configs/clusters/`) for existing Yappy clusters. Note: This path will be changed to `/yappy-clusters` in future updates.
            *   `SelectExistingCluster(ClusterSelection) returns (OperationStatus)`: Engine records the selected cluster (e.g., in its local bootstrap config or an instance-specific Consul key) and associates itself with it.
            *   `CreateNewCluster(NewClusterDetails) returns (ClusterCreationStatus)`: For initializing a new Yappy cluster.
        *   **HTTP Option:** Endpoints like `GET /setup/clusters`, `POST /setup/cluster/select`, `POST /setup/cluster/create`.
    *   **(Optional) UI for Dev/Testing:** A simple web page presenting these options (dropdown for existing, textbox for new).

2.  **Planting the Seed for a New Cluster**
    *   If `CreateNewCluster` is invoked:
        *   The engine uses `ConsulBusinessOperationsService` to write a default, minimal, but valid `PipelineClusterConfig` to the new cluster's path in Consul (e.g., `pipeline-configs/clusters/<new-cluster-name>`). Note: This path will be changed to `/yappy-clusters/<new-cluster-name>` in future updates.
        *   **Seed Configuration Details:**
            *   `pipelineGraphConfig`: Empty or a very simple "hello world" example.
            *   `pipelineModuleMap`: Empty.
            *   `allowedKafkaTopics`: Empty or sensible defaults.
            *   `allowedGrpcServices`: Empty or sensible defaults (perhaps allowing core engine services).
            *   Whitelist configuration: Empty or allowing only essential initial registrations.
        *   The engine then considers itself part of this newly seeded cluster.

### Phase A.3: Showtime! – Transition to Normal Operation

1.  **Full Steam Ahead**
    *   With Consul configured (from Phase A.1) and a Yappy cluster identified (from Phase A.2):
        *   The engine performs its full, normal startup sequence.
        *   `DynamicConfigurationManager` loads the `PipelineClusterConfig` from Consul.
        *   The engine registers itself with Consul (if applicable to its role).
        *   Kafka listeners are started (if Kafka is configured and available).
        *   Other operational components are initialized.
    *   The "Setup Mode" is deactivated. The engine is now fully operational.

## III. Part B: Enhanced Engine Operations within a Cluster

*(This part assumes the engine has successfully completed Part A and is operating within a configured Yappy cluster)*

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
    *   On startup, if the engine is configured for a specific cluster (post-Phase A.2) but `ConsulBusinessOperationsService.getPipelineClusterConfig()` returns an empty or non-existent configuration for that cluster key in Consul:
        *   Create a default/minimal `PipelineClusterConfig` in memory (similar to the seed in Phase A.2.2).
        *   Store this minimal config in Consul KV at the expected cluster path using `ConsulBusinessOperationsService.storeClusterConfiguration()`. This ensures the engine can operate even if its designated cluster config was accidentally wiped from Consul.
2.  **Implement Engine-Managed Module Registration:**
    *   **Important Note:** Modules do NOT register themselves - the engine handles registration on behalf of modules. This simplifies the architecture and reduces the burden on module developers.
    *   The engine is responsible for registering modules:
        *   **Pre-registration Health Check:** Directly ping the module's health endpoint before attempting Consul registration.
        *   **Authentication:** Implement a stub that always returns true as a placeholder for future security enhancements.
        *   **Construct `Registration` Object:** Populate ID, name, address, port, tags (including `yappy-service-name`, `yappy-version`, `yappy-config-digest`), and detailed health check information (`Registration.RegCheck`).
        *   **Register:** Call `ConsulBusinessOperationsService.registerService()`.
        *   **Verify:** After a short delay, use `ConsulBusinessOperationsService.getHealthyServiceInstances()` to confirm successful registration and health status in Consul.
        *   **Deregister on Shutdown:** Call `ConsulBusinessOperationsService.deregisterService()` when the engine stops a managed module.
3.  **Implement Proxying Logic (Basic - Lower Priority/Later):**
    *   If a required local module is `UNAVAILABLE` or in `CONFIGURATION_ERROR`:
        *   Query Consul for healthy remote instances of the same logical service name using `ConsulBusinessOperationsService.getHealthyServiceInstances()`.
        *   If found, the engine (or a component it manages) routes traffic to a healthy remote instance.
        *   Update the service's `ServiceAggregatedStatus` in KV to `ACTIVE_PROXYING` with `proxyTargetInstanceId`.

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

1.  **Create REST Controllers in the Engine:**
    *   `GET /api/status/services`: Returns `List<ServiceAggregatedStatus>`.
    *   `GET /api/status/services/{serviceName}`: Returns `ServiceAggregatedStatus` for a specific logical service.
    *   `GET /api/status/cluster`: Returns the engine's overall health (from its `HealthIndicator`) and its current configuration status (e.g., active cluster name, config version).
    *   `GET /api/config/cluster`: Returns the current `PipelineClusterConfig` being used by the engine.
    *   **(Optional, Later) Proxied Consul Endpoints for Admin UI convenience:**
        *   `GET /api/consul/services`
        *   `GET /api/consul/services/{serviceName}/instances`
        *   `GET /api/consul/services/{serviceName}/health`
    *   These controllers will primarily read data prepared by the engine (e.g., from Consul KV for aggregated status) or call `ConsulBusinessOperationsService` for direct Consul information.

### Phase B.6: Testing for Enhanced Operations

1.  **Unit Tests for New Engine Logic:**
    *   Mock `ConsulBusinessOperationsService`, `DynamicConfigurationManager`, etc., to test the status calculation, engine-managed registration decisions, and other new logic under various scenarios.
2.  **Integration Tests (MicronautTest with Testcontainers):**
    *   Test the "Bootstrap Cluster" refinement (engine seeding an empty but existing cluster config).
    *   Test the engine-managed module registration flow (if an API is added to trigger it for a test module, or for a simple co-located test module).
    *   Test the engine's ability to read module health from Consul (simulating healthy/unhealthy modules) and correctly update the `ServiceAggregatedStatus` in KV.
    *   Test the new status and config REST APIs (Phase B.5).

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

## Implementation Approach

1. **Start Simple**: Begin with a basic test that verifies each service works individually
2. **Add Kafka**: Test Kafka serialization of PipeStream objects
3. **Combine Services**: Test the services working together via gRPC
4. **Full Pipeline**: Test the full pipeline with both Kafka and gRPC

This approach will allow you to incrementally build up the functionality while ensuring each component works correctly before moving on to the next step.

## Additional Considerations

1. **Schema Registration**: Ensure that protobuf schemas are properly registered in both Apicurio and AWS Glue
2. **Service Discovery**: Configure Consul for service discovery between the Echo and Chunker services
3. **Error Handling**: Test error scenarios to ensure the pipeline handles them gracefully
4. **Metrics**: Add metrics collection to monitor the performance of the pipeline

By following these steps, you'll be able to create a comprehensive integration test suite that verifies the entire pipeline works correctly with both Kafka and gRPC communication.


# Detailed Implementation Plan for PipeStreamEngineImpl and Related Components

Based on the project exploration, this is an outline a comprehensive plan for implementing the YAPPY and related components. This plan focuses on creating proper Java interfaces with their implementations and utilizing the consul-config structure for routing.

## Overview of Components to Implement

1. **PipeStreamEngineImpl** - Main implementation of the PipeStreamEngine gRPC service (done)
2. **PipeStreamGrpcForwarder** - Already exists, handles forwarding to gRPC services (done)
3. **PipelineStepGrpcProcessor** - New component for processing pipeline steps via gRPC (done)
4. **PipeStepExecutor** - Interface and factory for executing pipeline steps (done)
5. **PipeStreamStateBuilder** - Builder for managing pipeline stream state (in progress)

## Overview of project setup

### Read this document in full
I'm in ask mode because this is a more complicated task, and might involve looking through multiple projects to ensure we can test them all at one time.

To fully understand what is being built and how, read through this full document `current_instructions.md`, this details where we are with coding - we are specifically on starting to 
integrate kafka and apicurio or glue (we will test both Apicurio and Glue for protobuf validation via kafka)

### Project Architecture Overview
The YAPPY project follows a distributed microservice architecture where:

1. Each service instance runs exactly one module (e.g., Echo or Chunker)
2. Each service contains both the module implementation and an embedded PipeStreamEngine
3. Services communicate with each other through gRPC and/or Kafka
4. Configuration is managed centrally through Consul
5. Schema validation is handled by either Apicurio or AWS Glue Schema Registry

### Key Components and Their Relationships
- **PipeStreamEngine**: Embedded in each service, handles routing and processing
- **PipeStepProcessor**: Interface implemented by modules (Echo, Chunker, etc.)
- **Consul**: Stores configuration and enables service discovery
- **Kafka**: Provides asynchronous communication between services
- **Schema Registry**: Validates and stores protobuf schemas (Apicurio or AWS Glue)

### Integration Testing Strategy
Our current focus is on creating end-to-end integration tests that:
1. Run multiple services (Echo and Chunker) in the same test
2. Connect them to the same Consul, Kafka, and schema registry instances
3. Test both synchronous (gRPC) and asynchronous (Kafka) communication
4. Verify that PipeStream objects can be properly serialized and deserialized

### Locations of models for this project
The protobufs will be developed in the code and pushed to either Glue or Apicurio.

The java models serialize in JSON and are used for API calls and in the case of schemas, stored in consul as well.

#### Protobufs
After this, read through the grpc protobuffers `yappy-models/protobuf-models/src/main/proto/*.proto`, this gives a clear understanding of the contracts we are building.

#### Pipeline-config-models
Then, look at the pipeline-config-models `com.krickert.search.config.pipeline.model` package in `yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/model/*.java`, this provides an insight as to the control plane of the engine and gives the full structure on the models to create/update pipeline configurations.

#### JSON Schema models
The last files, `yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/schema/model/*.java` for package `com.krickert.search.config.schema.model` - this provides an insight into the JSON Schema model design.

#### Module Implementations
The actual module implementations can be found in:
- Echo module: `yappy-modules/echo/src/main/java/com/krickert/yappy/modules/echo/EchoService.java`
- Chunker module: `yappy-modules/chunker/src/main/java/com/krickert/yappy/modules/chunker/ChunkerServiceGrpc.java`

#### Integration Tests
Current integration tests are located in:
- `yappy-engine/src/test/java/com/krickert/search/pipeline/integration/`
- These tests demonstrate how to set up and test various components of the system


## Service Discovery
### Module Discovery and Invocation

The YAPPY Engine is designed to dynamically discover and interact with its associated Module Processor. 
This process is crucial for the "engine-per-step" concept, where each engine instance is responsible for orchestrating a 
specific piece of business logic encapsulated in a module.

#### Discovery FLow
1. **Configuration-Driven**
   * When an Engine instance receives a PipeStream destined for a particular pipeline step (e.g., `stepName` = "`P1_S1_ValidateData`" within `pipelineName` = "`DataSciencePipeline-P1`"), it first consults its dynamic configuration.
2. **Fetch Step Configuration**: Using the `DynamicConfigurationManager`, the `Engine` retrieves the `PipelineStepConfig` for the current `pipelineName` and `target_step_name` from `Consul`.
3. **Identify Processor**
   * This `PipelineStepConfig` contains `ProcessorInfo`, which specifies how to find the actual module processor. 
   * Critically, this includes the `grpcServiceName` (e.g., "`validator-module-v1`"). 
   * This `grpcServiceName` is the key used for service discovery.
4. **Service Discovery (Consul)**
   * The `Engine`'s internal `PipelineStepGrpcProcessorImpl` component uses the **Micronaut DiscoveryClient** (configured for `Consul`) to look up active instances of the required `grpcServiceName`.
   * This allows modules to be deployed, scaled, and updated independently, with the `Engine` always finding a healthy, available instance.
5. **Localhost-First** (Optimization/Fallback)
   * As an optimization, especially if the `Engine` and its dedicated **Module Processor** are packaged within the same container or host, the `Engine` can be configured to attempt connection to a `localhost` address first for its specific, dedicated module.
   * **If this local connection fails** or is not configured, _it falls back_ to the standard service discovery mechanism via `Consul`. 
   * This ensures that **even in a tightly coupled deployment, the system can function**, but it still retains the flexibility of discovering modules running elsewhere.
6. **gRPC Invocation**: Once a service instance is discovered (its host and port are resolved), the `Engine` establishes a **gRPC connection** and invokes the `processData` method on the `PipeStepProcessor` interface implemented by the target module.

#### **Self-Registration (Implicit)** 
   * While the `Engine` discovers _modules_, the _modules_ themselves (when packaged as _standalone gRPC services_) are responsible for registering with `Consul` upon startup. 
   * This is a standard practice in microservice architectures:
     * When a service instance (e.g., "`Validator` Module" or "`Chunker` Module") starts, its embedded **Micronaut framework**, if configured for `Consul` registration, announces its presence, service name (e.g., "`validator-module-v1`"), `host`, and `port` to `Consul`.
     * `Consul` then makes this information available to other services, like the **YAPPY Engine**, that need to discover and communicate with it.
   * This entire discovery and invocation process is abstracted away from the **Module Processor** itself, allowing the module to _focus solely on its business logic_. 
   * The `Engine` handles the complexities of configuration, discovery, and communication.


## Component Details and Implementation Plan

### 1. PipeStepExecutor Interface and Implementation

This component provides an abstraction for executing pipeline steps, allowing for different execution strategies.

**Location**: 
- `com.krickert.search.pipeline.step.PipeStepExecutor` (Interface)
- `com.krickert.search.pipeline.step.impl.GrpcPipeStepExecutor` (Implementation)

**Interface Definition**:
```java
/**
 * Interface for executing pipeline steps.
 * This provides an abstraction over different execution strategies (gRPC, internal, etc.)
 */
public interface PipeStepExecutor {
    /**
     * Execute a pipeline step with the given PipeStream.
     * 
     * @param pipeStream The input PipeStream to process
     * @return The processed PipeStream
     * @throws PipeStepExecutionException If an error occurs during execution
     */
    PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException;

    /**
     * Get the name of the step this executor handles.
     * 
     * @return The step name
     */
    String getStepName();

    /**
     * Get the type of step this executor handles.
     * 
     * @return The step type
     */
    StepType getStepType();
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStepExecutor that executes steps via gRPC.
 */
@Singleton
public class GrpcPipeStepExecutor implements PipeStepExecutor {
    private final PipelineStepGrpcProcessor grpcProcessor;
    private final String stepName;
    private final StepType stepType;

    @Inject
    public GrpcPipeStepExecutor(PipelineStepGrpcProcessor grpcProcessor, 
                               String stepName, 
                               StepType stepType) {
        this.grpcProcessor = grpcProcessor;
        this.stepName = stepName;
        this.stepType = stepType;
    }

    @Override
    public PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException {
        try {
            ProcessResponse response = grpcProcessor.processStep(pipeStream, stepName);
            // Transform response back to PipeStream
            return transformResponseToPipeStream(pipeStream, response);
        } catch (Exception e) {
            throw new PipeStepExecutionException("Error executing gRPC step: " + stepName, e);
        }
    }

    @Override
    public String getStepName() {
        return stepName;
    }

    @Override
    public StepType getStepType() {
        return stepType;
    }

    private PipeStream transformResponseToPipeStream(PipeStream original, ProcessResponse response) {
        // Implementation to transform ProcessResponse back to PipeStream
        // This would update the document, add logs, etc.
        return original.toBuilder()
            .setDocument(response.getOutputDoc())
            // Add other transformations as needed
            .build();
    }
}
```

### 2. PipeStepExecutorFactory Interface and Implementation

This factory creates the appropriate executor for a given step.

**Location**: 
- `com.krickert.search.pipeline.step.PipeStepExecutorFactory` (Interface)
- `com.krickert.search.pipeline.step.impl.PipeStepExecutorFactoryImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Factory for creating PipeStepExecutor instances.
 */
public interface PipeStepExecutorFactory {
    /**
     * Get an executor for the specified pipeline and step.
     * 
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The appropriate executor for the step
     * @throws PipeStepExecutorNotFoundException If no executor can be found for the step
     */
    PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException;
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStepExecutorFactory that creates executors based on pipeline configuration.
 */
@Singleton
public class PipeStepExecutorFactoryImpl implements PipeStepExecutorFactory {
    private final DynamicConfigurationManager configManager;
    private final PipelineStepGrpcProcessor grpcProcessor;

    @Inject
    public PipeStepExecutorFactoryImpl(DynamicConfigurationManager configManager,
                                      PipelineStepGrpcProcessor grpcProcessor) {
        this.configManager = configManager;
        this.grpcProcessor = grpcProcessor;
    }

    @Override
    public PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException {
        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
        if (pipelineConfig.isEmpty()) {
            throw new PipeStepExecutorNotFoundException("Pipeline not found: " + pipelineName);
        }

        // Get the step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
        if (stepConfig == null) {
            throw new PipeStepExecutorNotFoundException("Step not found: " + stepName + " in pipeline: " + pipelineName);
        }

        // Create the appropriate executor based on step type
        if (stepConfig.processorInfo().grpcServiceName() != null) {
            return new GrpcPipeStepExecutor(grpcProcessor, stepName, stepConfig.stepType());
        } else if (stepConfig.processorInfo().internalProcessorBeanName() != null) {
            // For internal processors, we would use a different executor implementation
            // This would be implemented in a future task
            throw new PipeStepExecutorNotFoundException("Internal processors not yet implemented");
        } else {
            throw new PipeStepExecutorNotFoundException("No processor info found for step: " + stepName);
        }
    }
}
```

### 3. PipelineStepGrpcProcessor Interface and Implementation

This component handles the processing of pipeline steps via gRPC.

**Location**: 
- `com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor` (Interface)
- `com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessorImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Interface for processing pipeline steps via gRPC.
 */
public interface PipelineStepGrpcProcessor {
    /**
     * Process a step via gRPC.
     * 
     * @param pipeStream The input PipeStream to process
     * @param stepName The name of the step to process
     * @return The response from the gRPC service
     * @throws PipeStepProcessingException If an error occurs during processing
     */
    ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException;
}
```

**Implementation**:
```java
/**
 * Implementation of PipelineStepGrpcProcessor that connects to gRPC services.
 */
@Singleton
public class PipelineStepGrpcProcessorImpl implements PipelineStepGrpcProcessor {
    private final DiscoveryClient discoveryClient;
    private final DynamicConfigurationManager configManager;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    @Inject
    public PipelineStepGrpcProcessorImpl(DiscoveryClient discoveryClient,
                                        DynamicConfigurationManager configManager) {
        this.discoveryClient = discoveryClient;
        this.configManager = configManager;
    }

    @Override
    public ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException {
        try {
            // Get the pipeline configuration
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipeStream.getCurrentPipelineName());
            if (pipelineConfig.isEmpty()) {
                throw new PipeStepProcessingException("Pipeline not found: " + pipeStream.getCurrentPipelineName());
            }

            // Get the step configuration
            PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
            if (stepConfig == null) {
                throw new PipeStepProcessingException("Step not found: " + stepName);
            }

            // Get the gRPC service name
            String grpcServiceName = stepConfig.processorInfo().grpcServiceName();
            if (grpcServiceName == null) {
                throw new PipeStepProcessingException("No gRPC service name found for step: " + stepName);
            }

            // Get or create the channel
            ManagedChannel channel = getOrCreateChannel(grpcServiceName);

            // Create the stub
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);

            // Create the request
            ProcessRequest request = createProcessRequest(pipeStream, stepConfig);

            // Call the service
            return stub.processData(request);
        } catch (Exception e) {
            throw new PipeStepProcessingException("Error processing step: " + stepName, e);
        }
    }

    private ManagedChannel getOrCreateChannel(String serviceName) {
        return channelCache.computeIfAbsent(serviceName, name -> {
            // Use discovery client to find the service
            List<ServiceInstance> instances = Mono.from(discoveryClient.getInstances(name))
                .block(Duration.ofSeconds(10));

            if (instances == null || instances.isEmpty()) {
                throw new PipeStepProcessingException("Service not found: " + name);
            }

            ServiceInstance instance = instances.get(0);
            return ManagedChannelBuilder.forAddress(instance.getHost(), instance.getPort())
                .usePlaintext()
                .build();
        });
    }

    private ProcessRequest createProcessRequest(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        // Create service metadata
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
            .setPipelineName(pipeStream.getCurrentPipelineName())
            .setPipeStepName(stepConfig.stepName())
            .setStreamId(pipeStream.getStreamId())
            .setCurrentHopNumber(pipeStream.getCurrentHopNumber())
            .addAllHistory(pipeStream.getHistoryList())
            .putAllContextParams(pipeStream.getContextParamsMap())
            .build();

        // Create process configuration
        ProcessConfiguration config = ProcessConfiguration.newBuilder()
            .putAllConfigParams(stepConfig.customConfig().configParams())
            .build();

        // Create the request
        return ProcessRequest.newBuilder()
            .setDocument(pipeStream.getDocument())
            .setConfig(config)
            .setMetadata(metadata)
            .build();
    }
}
```

### 4. RouteData Record

This record is used to represent routing information for both gRPC and Kafka transports.

**Location**: `com.krickert.search.pipeline.engine.common.RouteData`

**Definition**:
```java
/**
 * Route data for forwarding PipeStream to the next step.
 * This record is used by both gRPC and Kafka forwarders.
 */
@Builder
public record RouteData(
        String targetPipeline,
        String nextTargetStep,
        String destination,
        String streamId,
        TransportType transportType,
        @Value("${grpc.client.plaintext:true}") boolean usePlainText) {
}
```

**Field Descriptions**:
- `targetPipeline`: The name of the pipeline that the next step belongs to
- `nextTargetStep`: The logical name of the next step in the pipeline that should process the message. This is set in the PipeStream's targetStepName field before forwarding, so the receiving service knows which step should process the message.
- `destination`: The physical endpoint where the message will be sent. For gRPC, it's the service name that will be looked up in the service discovery system (Consul). For Kafka, it's the topic name.
- `streamId`: The unique identifier for the stream being processed
- `transportType`: The type of transport to use (GRPC or KAFKA)
- `usePlainText`: Whether to use plaintext communication for gRPC (vs TLS/SSL)

The key difference between `destination` and `nextTargetStep` is that:
- `destination` is about where to send the message (physical routing)
- `nextTargetStep` is about which step should process the message (logical routing)

For example, when forwarding to a gRPC service, the `destination` might be "text-extractor-service" (the actual service name in Consul), while the `nextTargetStep` might be "extract-text" (the logical step name in the pipeline configuration).

### 5. PipeStreamStateBuilder Interface and Implementation

This component manages the three states (request, present, response) during processing and handles routing.

**Location**: 
- `com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder` (Interface)
- `com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Builder for managing pipeline stream state during processing.
 */
public interface PipeStreamStateBuilder {
    /**
     * Get the original request state.
     * 
     * @return The immutable request state
     */
    PipeStream getRequestState();

    /**
     * Get the current present state.
     * 
     * @return The mutable present state
     */
    PipeStream.Builder getPresentState();

    /**
     * Update the hop number in the present state.
     * 
     * @param hopNumber The new hop number
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withHopNumber(int hopNumber);

    /**
     * Set the target step in the present state.
     * 
     * @param stepName The target step name
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withTargetStep(String stepName);

    /**
     * Add a log entry to the present state.
     * 
     * @param log The log entry to add
     * @return This builder for chaining
     */
    PipeStreamStateBuilder addLogEntry(String log);

    /**
     * Calculate the next routes for the current state.
     * 
     * @return A list of route data for the next steps
     */
    List<RouteData> calculateNextRoutes();

    /**
     * Build the final response state.
     * 
     * @return The immutable response state
     */
    PipeStream build();
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStreamStateBuilder that manages pipeline stream state and routing.
 */
@Singleton

public class PipeStreamStateBuilderImpl implements PipeStreamStateBuilder {
    private final PipeStream requestState;
    private PipeStream.Builder presentState;
    private PipeStream responseState;
    private final DynamicConfigurationManager configManager;
    private final long startTime;
    private long endTime;

    @Inject
    public PipeStreamStateBuilderImpl(PipeStream request, 
                                     DynamicConfigurationManager configManager) {
        this.requestState = request;
        this.presentState = request.toBuilder();
        this.configManager = configManager;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public PipeStream getRequestState() {
        return requestState;
    }

    @Override
    public PipeStream.Builder getPresentState() {
        return presentState;
    }

    @Override
    public PipeStreamStateBuilder withHopNumber(int hopNumber) {
        presentState.setCurrentHopNumber(hopNumber);
        return this;
    }

    @Override
    public PipeStreamStateBuilder withTargetStep(String stepName) {
        presentState.setTargetStepName(stepName);
        return this;
    }

    @Override
    public PipeStreamStateBuilder addLogEntry(String log) {
        // Add log entry to history
        StepExecutionRecord record = StepExecutionRecord.newBuilder()
            .setHopNumber(presentState.getCurrentHopNumber())
            .setStepName(presentState.getTargetStepName())
            .setStatus("SUCCESS")
            .addProcessorLogs(log)
            .build();
        presentState.addHistory(record);
        return this;
    }

    @Override
    public List<RouteData> calculateNextRoutes() {
        List<RouteData> routes = new ArrayList<>();

        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(presentState.getCurrentPipelineName());
        if (pipelineConfig.isEmpty()) {
            return routes; // Empty list if pipeline not found
        }

        // Get the current step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(presentState.getTargetStepName());
        if (stepConfig == null) {
            return routes; // Empty list if step not found
        }

        // Calculate routes based on outputs
        for (Map.Entry<String, OutputTarget> entry : stepConfig.outputs().entrySet()) {
            String outputKey = entry.getKey();
            OutputTarget target = entry.getValue();

            if (target.transportType() == TransportType.GRPC) {
                // Create gRPC route
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.grpcTransport().serviceName())
                    .streamId(presentState.getStreamId())
                    .build());
            } else if (target.transportType() == TransportType.KAFKA) {
                // Create Kafka route - handled similarly to gRPC since both take PipeStream requests
                // The KafkaForwarder is already asynchronous by default in Micronaut
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.kafkaTransport().topicName())
                    .streamId(presentState.getStreamId())
                    .transportType(TransportType.KAFKA)
                    .build());
            }
        }

        return routes;
    }

    @Override
    public PipeStream build() {
        endTime = System.currentTimeMillis();
        // Add any final metadata or processing
        // For example, we could add a final history entry with timing information

        // Build the final response
        responseState = presentState.build();
        return responseState;
    }
}
```

### 5. PipeStreamEngineImpl Implementation

This is the central component that implements the PipeStreamEngine gRPC service.

**Location**: `com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl`

**Implementation**:
```java
/**
 * Implementation of the PipeStreamEngine gRPC service.
 */
@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private final PipeStepExecutorFactory executorFactory;
    private final PipeStreamGrpcForwarder grpcForwarder;
    private final KafkaForwarder kafkaForwarder;
    private final DynamicConfigurationManager configManager;

    @Inject
    public PipeStreamEngineImpl(PipeStepExecutorFactory executorFactory,
                               PipeStreamGrpcForwarder grpcForwarder,
                               KafkaForwarder kafkaForwarder,
                               DynamicConfigurationManager configManager) {
        this.executorFactory = executorFactory;
        this.grpcForwarder = grpcForwarder;
        this.kafkaForwarder = kafkaForwarder;
        this.configManager = configManager;
    }

    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        try {
            // First, validate that the target step is set in the request
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                throw new IllegalArgumentException("Target step name must be set in the request");
            }

            // Create state builder
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, configManager);

            // Increment hop number
            stateBuilder.withHopNumber(request.getCurrentHopNumber() + 1);

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                request.getCurrentPipelineName(), 
                request.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(stateBuilder.getPresentState().build());

            // Update the state with the processed stream
            stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Build the final response
            PipeStream response = stateBuilder.build();

            // Add route information to the response
            // This is for testing purposes only - we don't actually forward the data
            PipeStream.Builder responseWithRoutes = response.toBuilder();

            // Add route information to context params
            for (int i = 0; i < routes.size(); i++) {
                RouteData route = routes.get(i);
                responseWithRoutes.putContextParams("route_" + i + "_target_pipeline", route.targetPipeline());
                responseWithRoutes.putContextParams("route_" + i + "_next_step", route.nextTargetStep());
                responseWithRoutes.putContextParams("route_" + i + "_destination", route.destination());
                responseWithRoutes.putContextParams("route_" + i + "_transport_type", route.transportType().toString());
            }

            // Send response with route information
            responseObserver.onNext(responseWithRoutes.build());
            responseObserver.onCompleted();

            // Note: We do NOT forward to next steps in testPipeStream
            // This method is for testing only and returns where it would have forwarded
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    @Override
    public void processPipe(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            // First, validate that the target step is set in the request
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                throw new IllegalArgumentException("Target step name must be set in the request");
            }

            // Send empty response immediately after validation
            // This is the default for pipeline-to-pipeline communication
            // We don't have to wait for it to process to let the caller know we're good to go
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

            // Create state builder
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, configManager);

            // Increment hop number
            stateBuilder.withHopNumber(request.getCurrentHopNumber() + 1);

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                request.getCurrentPipelineName(), 
                request.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(stateBuilder.getPresentState().build());

            // Update the state with the processed stream
            stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Build the final response
            PipeStream response = stateBuilder.build();

            // Forward to next steps - both gRPC and Kafka are handled similarly
            // Both take PipeStream requests and both run asynchronously
            for (RouteData route : routes) {
                // Create a new PipeStream for each destination with the correct target step
                PipeStream.Builder destinationPipeBuilder = response.toBuilder()
                    .setTargetStepName(route.nextTargetStep());

                if (route.transportType() == TransportType.KAFKA) {
                    // Forward to Kafka - this is asynchronous by default in Micronaut
                    // We need to set the target step for each Kafka destination
                    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
                } else {
                    // Forward to gRPC
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
                }
            }
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    @Override
    public void processConnectorDoc(ConnectorRequest request, StreamObserver<ConnectorResponse> responseObserver) {
        try {
            // Get the pipeline configuration for the source identifier
            // This would involve looking up the appropriate pipeline based on the source identifier
            // For now, we'll use a simple approach

            Optional<PipelineClusterConfig> clusterConfig = configManager.getCurrentPipelineClusterConfig();
            if (clusterConfig.isEmpty()) {
                throw new RuntimeException("No cluster configuration found");
            }

            // Use the default pipeline if available, otherwise use the first one
            String pipelineName = clusterConfig.get().defaultPipelineName();
            if (pipelineName == null && !clusterConfig.get().pipelineGraphConfig().pipelines().isEmpty()) {
                pipelineName = clusterConfig.get().pipelineGraphConfig().pipelines().keySet().iterator().next();
            }

            if (pipelineName == null) {
                throw new RuntimeException("No pipeline found for connector");
            }

            // Create a new PipeStream
            String streamId = request.getSuggestedStreamId().isEmpty() ? 
                UUID.randomUUID().toString() : request.getSuggestedStreamId();

            PipeStream.Builder pipeStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(request.getDocument())
                .setCurrentPipelineName(pipelineName)
                .setCurrentHopNumber(0)
                .putAllContextParams(request.getInitialContextParamsMap());

            // Get the first step in the pipeline
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
            if (pipelineConfig.isEmpty()) {
                throw new RuntimeException("Pipeline not found: " + pipelineName);
            }

            // Find the first step in the pipeline
            // In a real implementation, we would have a way to determine the entry point
            // For now, we'll just use the first step in the pipeline
            if (pipelineConfig.get().pipelineSteps().isEmpty()) {
                throw new RuntimeException("Pipeline has no steps: " + pipelineName);
            }

            String firstStepName = pipelineConfig.get().pipelineSteps().keySet().iterator().next();
            pipeStreamBuilder.setTargetStepName(firstStepName);

            // Create the response
            ConnectorResponse response = ConnectorResponse.newBuilder()
                .setStreamId(streamId)
                .setAccepted(true)
                .setMessage("Ingestion accepted for stream ID " + streamId + ", targeting pipeline " + pipelineName)
                .build();

            // Send the response
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            // Process the document asynchronously
            PipeStream pipeStream = pipeStreamBuilder.build();

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                pipeStream.getCurrentPipelineName(), 
                pipeStream.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(pipeStream);

            // Create state builder for routing
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Forward to next steps - both gRPC and Kafka are handled similarly
            // Both take PipeStream requests and both run asynchronously
            for (RouteData route : routes) {
                // Create a new PipeStream for each destination with the correct target step
                PipeStream.Builder destinationPipeBuilder = processedStream.toBuilder()
                    .setTargetStepName(route.nextTargetStep());

                if (route.transportType() == TransportType.KAFKA) {
                    // Forward to Kafka - this is asynchronous by default in Micronaut
                    // We need to set the target step for each Kafka destination
                    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
                } else {
                    // Forward to gRPC
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
                }
            }
        } catch (Exception e) {
            // Create error response
            ConnectorResponse response = ConnectorResponse.newBuilder()
                .setAccepted(false)
                .setMessage("Error processing connector document: " + e.getMessage())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
```

## Implementation Tasks and Next Steps / Progress Report

### Task 1: Create Interface Definitions (100% completed)

1. Create the `PipeStepExecutor` interface in `com.krickert.search.pipeline.step`
2. Create the `PipeStepExecutorFactory` interface in `com.krickert.search.pipeline.step`
3. Create the `PipelineStepGrpcProcessor` interface in `com.krickert.search.pipeline.step.grpc`
4. Create the `PipeStreamStateBuilder` interface in `com.krickert.search.pipeline.engine.state`
5. Create necessary exception classes:
   - `PipeStepExecutionException`
   - `PipeStepExecutorNotFoundException`
   - `PipeStepProcessingException`

### Task 2: Implement Interface Implementations (100% completed)

1. Create the `GrpcPipeStepExecutor` implementation in `com.krickert.search.pipeline.step.impl`
2. Create the `PipeStepExecutorFactoryImpl` implementation in `com.krickert.search.pipeline.step.impl`
3. Create the `PipelineStepGrpcProcessorImpl` implementation in `com.krickert.search.pipeline.step.grpc`
4. Create the `PipeStreamStateBuilderImpl` implementation in `com.krickert.search.pipeline.engine.state`

### Task 3: Implement PipeStreamEngineImpl (Three mothods completed (enhancements in progress))

1. Create the `PipeStreamEngineImpl` class in `com.krickert.search.pipeline.engine.grpc`
2. Implement the three methods:
   - `testPipeStream`
   - `processPipe`
   - `processConnectorDoc`

### Task 4: Create Unit Tests (20% complete - IN PROGRESS)

1. Create unit tests for each interface implementation
2. Create integration tests that use the chunker and echo implementations
3. Test the routing logic with different pipeline configurations

## This is where we are now - and below is a project plan 

The following sections below go over the remaining work for the full project.  

### Project Plan
The unit tests must me methodical: a full integration test is the goal.

1. Create mock test to ensure the structure is sound (completed)
2. Create tests that prove Apicurio, Kafka, and Consul all start up without error (completed)
3. Create tests that run the Echo and Chunker service at the same time (not started)
4. Once Echo and Chunker are started, configure the engine step-by-step simulating a full end-to-end

### Next steps to complete
1. Run the Echo service in a separate application-test-echo.yml file 
2. Run the Chunker service in a separate application-text-chunker-yml file
3. Run both the echo and the chunker at the same time in a single test, connecting to the same consul/apicurio/kafka instance

#### Plan to run echo, chunker, and connector all in the same test
   1. Write tests that prove chunker and echo are both working from the same consul app by running the Admin front end API to put together the full pipeline
      1. Launch kafka/apicurio
         1. Create new topics
         2. Register all protobufs into apicurio
         3. Create same test but different configuration to register all protobufs in moto
         4. Create sample end-to-end that ensure kafka can send/receive simulated PipeStream objects
         5. Bonus: try any other request/response object so we can also run connectors or async pipe arch events
      6. Create new cluster
         1. Simulate admin doing this - so far this is done via seeding in the consul-config
            1. Create an API call for registering a new cluster
            9. Write an API front end to register a new cluster
         10. Add this to uber-test that launches all 3 services
      6. Create new pipeline
         1. Create an API for registering a new pipeline and API front end
         8. Add this to uber-test that launches all 3 services
      7. Create kaka topics
         1. Create an API that creates new topics 
         9. Add this to uber-test that launches all 3 services
      7. Register the echo service
         1. Create an API for registering a new module
         9. Test that module launches and registers itself with consul
         10. Create an API that returns the status of the echo service
            1. make sure that this uses the work from the section "Using Consul Configuration for Routing"
         9. Add this to uber-test that launches all 3 services
      9. Register the chunker service
         1.  See same steps for echo service
      11. Create dummy service loader that loads in 20 pipe stream objects
      13. Create an API for registering a new connector
      13. Create an API for ensuring the connector was properly registered
#### Future connectors and pipelines
##### Create sample data loading service
1. Create python connector example
13. Create python pipeline step example
14. Create python pipeline sink step example
14. Register new python connectors with services
12. Create wikipedia connector
13. Create common crawl connector
14. Create Open search sink
15. Create Docstore sink
16. Create Dockstore pipeline to save in new Open Search collection

##### More simluated front end tasks
1. Start making connections in the pipeline configuration
   1. Wikipedia Connector --> Echo --> Chunker --> Embedder --> Open Search
   17. Add Common Crawl Connector
   18. Add Docstore (Mongo) sink
19. New pipeline
   1. Dockstore --> Echo --> Chunker --> Embedder --> Open Search
21. Create search API
22. Add front end for search
23. Add Open Source analytics to search
24. Create data training via analytics to improve chunks

### Task 5: Future Enhancements (10% complete - to be done as above is going on)

1. Implement Kafka routing in the `PipeStreamStateBuilder`
2. Add support for internal processors in the `PipeStepExecutorFactory`
3. Implement error handling and retry logic
4. Add metrics and monitoring
5. Implement configuration-driven setup for pipeline steps

## Using Consul Configuration for Routing (90% complete)

The implementation uses the DynamicConfigurationManager to access pipeline configurations from Consul. This manager provides access to:

1. **PipelineClusterConfig** - The top-level configuration for a cluster of pipelines
2. **PipelineGraphConfig** - A container for pipeline configurations
3. **PipelineConfig** - Configuration for a single pipeline
4. **PipelineStepConfig** - Configuration for a single step in a pipeline

The routing logic in the PipeStreamStateBuilder uses these configurations to determine where to route the processed data:

1. Get the pipeline configuration using `configManager.getPipelineConfig(pipelineName)`
2. Get the step configuration using `pipelineConfig.pipelineSteps().get(stepName)`
3. Examine the step's outputs to determine the next steps:
   ```java
   for (Map.Entry<String, OutputTarget> entry : stepConfig.outputs().entrySet()) {
       String outputKey = entry.getKey();
       OutputTarget target = entry.getValue();

       if (target.transportType() == TransportType.GRPC) {
           // Create gRPC route
           routes.add(RouteData.builder()
               .targetPipeline(presentState.getCurrentPipelineName())
               .nextTargetStep(target.targetStepName())
               .destination(target.grpcTransport().serviceName())
               .streamId(presentState.getStreamId())
               .build());
       } else if (target.transportType() == TransportType.KAFKA) {
           // Handle Kafka routing
       }
   }
   ```

This approach ensures that routing is driven by configuration, making it flexible and adaptable to changes without code modifications.

It's important to note that both gRPC and Kafka transports are treated similarly in the routing logic. They both take PipeStream requests and both run asynchronously. The only difference is the transport mechanism (gRPC vs Kafka). The KafkaForwarder is, by default, a future task in Micronaut that runs asynchronously, just like the gRPC calls. This consistent treatment simplifies the code and makes it easier to understand and maintain.

A critical aspect of the implementation is ensuring that each destination receives a PipeStream with its specific target step. This is essential for fan-out scenarios where a document might be processed in multiple ways (e.g., chunked in 3 different ways and embedded with 2 different embedders, resulting in 6 new fields). Each service needs to know which specific implementation it should run, which is determined by the target_step_name field in the PipeStream. 

For this reason, we create a new PipeStream for each destination with the correct target step before forwarding:
```java
// Create a new PipeStream for each destination with the correct target step
PipeStream.Builder destinationPipeBuilder = response.toBuilder()
    .setTargetStepName(route.nextTargetStep());

// Then forward to the appropriate transport
if (route.transportType() == TransportType.KAFKA) {
    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
} else {
    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
}
```

This ensures that each service receives a PipeStream with its specific target step, allowing it to execute the correct implementation.

This plan provides a structured approach to implementing the required components while focusing on creating a clean, maintainable architecture that follows the project's design principles.

## Using PipelineModuleMap for Module Management

The PipelineModuleMap is a critical component in the system that serves as a catalog of available pipeline modules. It is defined in the PipelineClusterConfig and contains information about all the modules that can be used in the pipeline.

### Structure of PipelineModuleMap

The PipelineModuleMap contains a map of available modules, where each entry maps a module's implementationId to its definition:

```java
public record PipelineModuleMap(
        @JsonProperty("availableModules") Map<String, PipelineModuleConfiguration> availableModules
) {
    // Canonical constructor making map unmodifiable and handling nulls
    public PipelineModuleMap {
        availableModules = (availableModules == null) ? Collections.emptyMap() : Map.copyOf(availableModules);
    }
}
```

Each PipelineModuleConfiguration contains:
- implementationName: A user-friendly display name (e.g., "Document Ingest Service")
- implementationId: A unique identifier (e.g., "document-ingest-service")
- customConfigSchemaReference: A reference to the schema for the module's configuration

### How to Utilize PipelineModuleMap

The PipelineModuleMap serves several important purposes in the system:

1. **Module Registration**: It registers all available modules in the system, making them discoverable and usable in pipeline configurations.

2. **Configuration Validation**: When a pipeline step references a module via its processorInfo.grpcServiceName, the system can validate that the module exists in the PipelineModuleMap.

3. **Schema Validation**: The customConfigSchemaReference in each module configuration points to the schema that defines the structure for the module's custom configuration. This allows the system to validate that the custom configuration provided in a pipeline step conforms to the expected schema.

4. **Service Discovery**: The implementationId in the module configuration typically matches the service name used for discovery in Consul, allowing the system to locate and connect to the appropriate service.

Example usage in code:

```java
// Get the module configuration for a step
Optional<PipelineClusterConfig> clusterConfig = configManager.getCurrentPipelineClusterConfig();
if (clusterConfig.isPresent()) {
    PipelineModuleMap moduleMap = clusterConfig.get().pipelineModuleMap();
    String serviceId = stepConfig.processorInfo().grpcServiceName();
    PipelineModuleConfiguration moduleConfig = moduleMap.availableModules().get(serviceId);

    if (moduleConfig != null) {
        // Use the module configuration
        String moduleName = moduleConfig.implementationName();
        SchemaReference schemaRef = moduleConfig.customConfigSchemaReference();

        // Validate custom configuration against schema
        if (schemaRef != null) {
            Optional<String> schemaContent = configManager.getSchemaContent(schemaRef);
            if (schemaContent.isPresent()) {
                // Validate custom configuration against schema
                boolean valid = validateConfig(stepConfig.customConfig(), schemaContent.get());
                if (!valid) {
                    throw new ConfigurationException("Invalid custom configuration for module: " + moduleName);
                }
            }
        }
    }
}
```

## Admin Features Left to Implement

### 1. Validating and Registering New Modules

To validate and register new modules, the following features need to be implemented:

1. **Module Validation Service**:
   - Verify that the module implements the required gRPC interface (PipeStepProcessor)
   - Validate that the module's custom configuration schema is valid
   - Check that the module's implementation ID is unique
   - Ensure that the module's service name is registered in Consul

2. **Module Registration API**:
   - Create a REST API for registering new modules
   - Add endpoints for:
     - Registering a new module
     - Updating an existing module
     - Removing a module
     - Listing all registered modules
   - Implement authentication and authorization for these endpoints

3. **Schema Registry Integration**: (100% complete? Not needed for custom json?)
   - Decide if we need this for JSON schemas.  If it's little effort to add to both glue and apicurio, we will do it.
   - Integrate with the schema registry to store and retrieve module configuration schemas
   - Implement versioning for schemas to support backward compatibility
   - Add validation of custom configurations against schemas

### 2. Creating Containers that Package Modules with the Engine (not started)

To create containers that package modules with the engine, the following features need to be implemented:

1. **Container Build System**:
   - Create a build system that packages a module with the engine in a single container
   - Support both Java and Python modules
   - Include necessary dependencies and configuration

2. **Java Module Container Example**:
   ```dockerfile
   # Base image with Java and Micronaut
   FROM openjdk:21-slim

   # Install dependencies
   RUN apt-get update && apt-get install -y curl

   # Set up environment
   ENV MICRONAUT_ENVIRONMENTS=prod

   # Copy the engine and module JAR files
   COPY build/libs/yappy-engine-*.jar /app/engine.jar
   COPY build/libs/module-*.jar /app/module.jar

   # Copy configuration
   COPY src/main/resources/application.yml /app/

   # Expose ports
   EXPOSE 8080 50051

   # Start the application
   CMD ["java", "-jar", "/app/engine.jar"]
   ```

3. **Python Module Container Example**:
   ```dockerfile
   # Base image with Python
   FROM python:3.9-slim

   # Install dependencies
   RUN pip install grpcio grpcio-tools protobuf

   # Copy the engine JAR file
   COPY build/libs/yappy-engine-*.jar /app/engine.jar

   # Copy the Python module
   COPY src/main/python /app/module

   # Copy configuration
   COPY src/main/resources/application.yml /app/

   # Install Java for running the engine
   RUN apt-get update && apt-get install -y openjdk-21-jre-headless

   # Expose ports
   EXPOSE 8080 50051

   # Start both the engine and the Python module
   CMD ["sh", "-c", "java -jar /app/engine.jar & python /app/module/main.py"]
   ```

4. **Module Project Generator**:
   - Enhance the existing project generator to create module projects with Docker support
   - Add templates for both Java and Python modules
   - Include CI/CD pipeline configurations for building and deploying containers

### 3. Keeping Module Information in Consul Configuration

To keep module information in Consul configuration, the following features need to be implemented:

1. **Module Configuration Management**:
   - Implement a service for managing module configurations in Consul
   - Support CRUD operations for module configurations
   - Implement versioning for module configurations
   - Add validation of module configurations against schemas

2. **Live Configuration Updates**:
   - Implement a mechanism for updating module configurations in real-time
   - Add support for rolling updates to minimize downtime
   - Implement a rollback mechanism for failed updates

3. **Module Health Monitoring**:
   - Add health checks for modules
   - Implement automatic failover for unhealthy modules
   - Add metrics collection for module performance

4. **Module Discovery**:
   - Enhance the service discovery mechanism to support module discovery
   - Add support for load balancing across multiple instances of the same module
   - Implement circuit breakers for handling module failures

These admin features will provide a comprehensive system for managing pipeline modules, from validation and registration to deployment and monitoring.

## Kafka Topic Naming Conventions and Consumer Groups

### Kafka Topic Permission Validation

The system enforces strict validation of Kafka topic names to ensure security and consistency. Topic validation happens through the `isKafkaTopicPermitted` method in the `WhitelistValidator` class, which checks if a topic is permitted in two ways:

1. **Explicit Whitelist**: Topics can be explicitly allowed by adding them to the `allowedKafkaTopics` list in the `PipelineClusterConfig`. This is useful for topics that don't follow the naming convention or for cross-pipeline communication.

2. **Naming Convention**: If a topic is not explicitly whitelisted, it must follow a specific naming convention pattern:
   ```
   yappy.pipeline.[pipelineName].step.[stepName].(input|output|error|dead-letter)
   ```

   For example: `yappy.pipeline.search-pipeline.step.document-ingest.output`

   > **Note**: In future implementations, the "yappy." prefix will be removed from the naming convention, making it:
   > ```
   > pipeline.[pipelineName].step.[stepName].(input|output|error|dead-letter)
   > ```
   > This change will be implemented in the "next steps" phase of development.

   There is no restriction on non-standard topics as long as they don't begin with "pipeline." and are included in the `allowedKafkaTopics` list in the PipelineClusterConfig. Any Kafka topic name can be used for cross-pipeline communication or other purposes as long as it's properly authorized.

#### Variable Resolution in Topic Names

The system supports variable substitution in topic names, allowing for dynamic topic naming based on the current context. Variables are specified using the `${variableName}` syntax. The following variables are supported:

- `${clusterName}` - Replaced with the current cluster name
- `${pipelineName}` - Replaced with the current pipeline name
- `${stepName}` - Replaced with the current step name

For example, a topic name defined as `yappy.pipeline.${pipelineName}.step.${stepName}.output` would be resolved to `yappy.pipeline.search-pipeline.step.document-ingest.output` when used in the "document-ingest" step of the "search-pipeline" pipeline.

#### Topic Validation Process

When validating a Kafka topic, the system follows these steps:

1. **Variable Resolution**: Replace any variables in the topic name with their actual values
2. **Explicit Whitelist Check**: Check if either the original or resolved topic name is in the explicit whitelist
3. **Naming Convention Check**: If not explicitly whitelisted, check if the resolved topic matches the naming convention
4. **Context Validation**: Ensure that the topic's pipeline and step names match the current context

This validation process ensures that only authorized topics are used in the pipeline, preventing unauthorized data access or routing.

### Consumer Groups

Consumer groups are organized by pipeline name to ensure that each pipeline instance processes messages independently. The consumer group naming convention follows this pattern:

```
[pipeline-name]-consumer-group
```

For example, if the pipeline name is `search-pipeline`, the consumer group would be `search-pipeline-consumer-group`.

This ensures that if multiple instances of the same pipeline are running, they will all be part of the same consumer group and will not process the same messages multiple times.

### Cross-Pipeline Communication

Cross-pipeline communication is achieved through Kafka topics. When a step in one pipeline needs to send data to a step in another pipeline, it uses a Kafka topic that has been added to the `allowedKafkaTopics` list in the PipelineClusterConfig.

To enable cross-pipeline communication, the topic must be added to the `allowedKafkaTopics` list in the PipelineClusterConfig. This ensures that only authorized topics can be used for cross-pipeline communication.

The workflow for adding a new cross-pipeline communication channel is as follows:

1. Identify the source pipeline and step that will send the data
2. Identify the target pipeline and step that will receive the data (note that non-application targets like external apps are also supported)
3. Request the admin to create a custom topic
4. Update the source step's outputs to include the new topic
5. Update the target step's inputs to include the new topic

A pipeline step CAN listen to more than one topic. The default topic is always listened to, but additional topics can be added to the step's inputs as needed.

### Topic Creation Process

When a pipeline step is created, two Kafka topics are automatically created:
1. The main topic for the step
2. The error topic for the step

The topic creation process is as follows:

1. When a new pipeline step is added to the configuration, the system checks if the required topics exist
2. If the topics do not exist, they are automatically created with the default settings
3. If the topics already exist, the system verifies that they have the correct configuration
4. If the configuration is incorrect, the system logs a warning but does not modify the topics

### Admin Automation for Pausing Consumers

The system includes admin automation for pausing consumers, which is useful during upgrades or emergencies when processing needs to stop. This can be done at two levels:

1. **Consumer Group Level**: Pause all consumers in a specific consumer group
   ```
   POST /api/admin/kafka/consumer-groups/{consumer-group-name}/pause
   ```

2. **Pipeline Level**: Pause all consumers for a specific pipeline
   ```
   POST /api/admin/pipelines/{pipeline-name}/pause
   ```

When consumers are paused, they stop consuming messages from Kafka topics but do not lose their current offsets. This ensures that when they are resumed, they will continue from where they left off.

## Pipeline Configuration Validation

The system includes several validators that ensure the pipeline configuration is valid and consistent. These validators are run when the configuration is loaded and when it is updated.

### 1. CustomConfigSchemaValidator

This validator ensures that custom configurations for pipeline steps conform to their defined JSON schemas:

- Validates custom configurations against schemas defined in the step's `customConfigSchemaId`
- Validates custom configurations against schemas defined in the module's `customConfigSchemaReference`
- Reports errors if a schema is missing or if the configuration doesn't conform to the schema
- Handles cases where a step has a custom configuration but no schema is defined

### 2. InterPipelineLoopValidator

This validator detects loops between different pipelines in the Kafka data flow:

- Builds a directed graph where vertices are pipeline names and edges represent Kafka topic connections
- Detects cycles in the graph using the JohnsonSimpleCycles algorithm
- Reports detailed error messages showing the cycle paths
- Prevents infinite processing loops between pipelines

### 3. IntraPipelineLoopValidator

This validator detects loops within a single pipeline in the Kafka data flow:

- Builds a directed graph for each pipeline where vertices are step names and edges represent Kafka topic connections
- Detects cycles in the graph using the JohnsonSimpleCycles algorithm
- Reports detailed error messages showing the cycle paths
- Prevents infinite processing loops within a pipeline

### 4. ReferentialIntegrityValidator

This validator ensures that all references within the pipeline configuration are valid:

- Validates that pipeline names match their keys in the pipelines map
- Validates that step names match their keys in the steps map
- Ensures there are no duplicate pipeline or step names
- Validates that ProcessorInfo references valid modules in the availableModules map
- Ensures custom configuration schemas are properly referenced
- Validates that Kafka input definitions have valid properties
- Ensures output targets have valid properties
- Validates that target step names in outputs refer to existing steps within the same pipeline
- Handles cross-pipeline references by detecting when a targetStepName contains a dot (indicating a "pipelineName.stepName" format)

### 5. StepTypeValidator

This validator ensures that steps are configured correctly based on their type:

- INITIAL_PIPELINE: Must not have Kafka inputs, should have outputs
- SINK: Should have Kafka inputs or be an internal processor, must not have outputs
- PIPELINE: Logs a warning if it has no Kafka inputs and no outputs (might be orphaned)
- Reports detailed error messages for each validation failure

### 6. WhitelistValidator

This validator ensures that only authorized Kafka topics and gRPC services are used in the pipeline:

- Validates that Kafka topics used in outputs are either in the explicit whitelist or match the naming convention
- Validates that gRPC services referenced in processorInfo and outputs are in the allowedGrpcServices list
- Handles variable substitution in topic names
- Ensures that topics used in a step's outputs are either in the whitelist or match the naming convention

## Future Plans

### Observability

The system will include comprehensive observability features to monitor the health and performance of the pipeline system:

- **Metrics Collection**: Collect metrics on message throughput, processing time, error rates, etc.
- **Distributed Tracing**: Implement distributed tracing to track the flow of messages through the pipeline
- **Logging**: Centralized logging with structured log formats for easy querying
- **Alerting**: Set up alerts for critical errors, performance degradation, etc.
- **Dashboards**: Create dashboards to visualize the health and performance of the system

### Admin UI

The admin UI will provide a user-friendly interface for managing the pipeline system:

- **Pipeline Management**: Create, update, and delete pipelines and steps
- **Configuration Management**: Manage configuration for pipelines, steps, and modules
- **Monitoring**: Monitor the health and performance of the system
- **Troubleshooting**: Identify and resolve issues in the pipeline
- **User Management**: Manage users and permissions

### Search API

The search API will provide a RESTful interface for searching indexed documents:

- **Full-Text Search**: Search for documents using full-text queries
- **Faceted Search**: Filter search results by facets
- **Semantic Search**: Search for documents using semantic queries
- **Hybrid Search**: Combine full-text and semantic search
- **Personalized Search**: Customize search results based on user preferences

### Search API Analytics

The search API analytics will provide insights into how users are interacting with the search API:

- **Query Analytics**: Analyze the types of queries users are making
- **Result Analytics**: Analyze the results users are clicking on
- **User Analytics**: Analyze user behavior and preferences
- **Performance Analytics**: Analyze the performance of the search API
- **Trend Analytics**: Identify trends in search behavior over time

### White Label for Search

The white label for search will allow organizations to customize the search experience with their own branding:

- **Custom UI**: Customize the look and feel of the search interface
- **Custom Domain**: Use a custom domain for the search interface
- **Custom Branding**: Add custom logos, colors, and other branding elements
- **Custom Features**: Enable or disable specific search features
- **Custom Analytics**: Customize the analytics dashboard

### Pipeline Editor

The pipeline editor will provide a visual interface for creating and editing pipelines:

- **Visual Editor**: Drag-and-drop interface for creating pipelines
- **Step Configuration**: Configure pipeline steps with a user-friendly interface
- **Validation**: Validate pipeline configurations before saving
- **Testing**: Test pipelines with sample data
- **Versioning**: Track changes to pipelines over time

### Dashboard

The dashboard will provide a high-level overview of the pipeline system:

- **System Health**: Monitor the health of the pipeline system
- **Performance Metrics**: Track key performance metrics
- **Error Rates**: Monitor error rates across the system
- **Resource Usage**: Track resource usage (CPU, memory, disk, etc.)
- **Throughput**: Monitor message throughput across the system

### Appendix: Additional Considerations for Integration Testing

While the overall plan is comprehensive, here are a few additional points to keep in mind as you progress with integration testing:

1.  **Test Configuration Management**:
   * For multi-context tests, you're currently using `Map<String, Object>` for properties. Consider if loading properties from dedicated test YAML files per service context (e.g., `application-test-echo.yml`, `application-test-chunker.yml`) and passing those to `ApplicationContext.builder()` would be cleaner for more complex configurations. Micronaut Test Resources should continue to provide shared infrastructure like Consul and Kafka ports.

2.  **Schema Registry in Multi-Context Tests**:
   * When testing Kafka flows that require schema validation (Apicurio/Glue), ensure all relevant service contexts (producer engine, consumer engine) are configured to point to the *same* schema registry instance (again, likely provided by Test Resources).
   * Ensure schemas are pre-registered or registered by the test setup before messages are produced.

3.  **Admin API for Test Setup**:
   * While seeding Consul directly is faster initially for testing core flows, eventually, using the Admin APIs (once developed) to set up `PipelineClusterConfig`, `PipelineConfig`, etc., in your tests will provide more realistic end-to-end validation of the control plane. This can be a later phase.

4.  **Idempotency and Retries**:
   * While "Error Handling" is listed, specifically consider testing idempotency of module processors if they might be retried by the engine (e.g., after a temporary Kafka hiccup or a failed gRPC call).

5.  **Security Considerations (Later Stage)**:
   * For now, `usePlainText=true` for gRPC is fine for local testing. Later, you'll want to consider mTLS between services and Kafka ACLs/authentication.

6.  **Clarity of "Engine" Role in Tests**:
   * When writing tests, be clear about which "engine" you're interacting with:
      * Are you calling a module's `PipeStepProcessor` gRPC endpoint directly?
      * Are you calling an engine's `PipeStreamEngine` gRPC endpoint (e.g., `processPipe` or `processConnectorDoc`) which then orchestrates calls to modules?
   * This distinction will help in designing focused tests.

7.  **`PipeStreamStateBuilderImpl` and Immutability**:
   * In `PipeStreamStateBuilderImpl`, the `presentState` is a `PipeStream.Builder`. While this allows modification, ensure that when `execute()` is called on a `PipeStepExecutor`, it receives an immutable `PipeStream` (e.g., `stateBuilder.getPresentState().build()`). The executor then returns a *new* immutable `PipeStream`. The subsequent line `stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);` correctly re-initializes the state builder with the new immutable state, which is good.

8.  **`testPipeStream` Method in `PipeStreamEngineImpl`**:
   * You correctly note: "We do NOT forward to next steps in `testPipeStream`". Ensure tests for this method specifically check the `contextParams` for the *intended* routing information, rather than expecting actual forwarding.
