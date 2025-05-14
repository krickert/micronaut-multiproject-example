# Pipeline Platform: Remaining Tasks & Architecture Checkpoint 

This document outlines the current status and remaining high-level tasks for the development of the configurable pipeline platform.

## I. Current Status: Configuration Management Subsystem - COMPLETE & TESTED
The foundational configuration management subsystem is now fully implemented, comprehensively tested (unit, integration, and concurrency), and validated. This critical phase is complete.

### Key Components & Status:

#### Core Data Models:

* `pipeline-config-models` (including `PipelineStepConfig` with `nextSteps` and `errorSteps`) and `schema-registry-models` are 
implemented as framework-agnostic Java records/enums.
* **Status**: **REFACTORED - NEEDS UPDATING**

#### Dynamic Configuration Loading & Management (DynamicConfigurationManagerImpl):

* Orchestrates loading, validation, caching, and live updates from Consul.
* Handles `WatchCallbackResult` for robust error propagation from the fetcher.
* Publishes `ClusterConfigUpdateEvent`.
* **Status**: **NEEDS ATTENTION** 
  * (Unit and Full End-to-End Integration Tested with all real dependencies via MicronautTest and Testcontainers).

#### Consul Interaction (KiwiprojectConsulConfigFetcher):

* Connects to Consul, fetches KVs, deserializes JSON.
* Implements live watches using `KVCache`.
* Correctly propagates success, deletion, or errors (e.g., deserialization failures) via WatchCallbackResult.
* **Status**: **NEEDS RE-TESTING AFTER ABOVE IS DONE** 
  * Unit and Integration Tested with Testcontainers Consul).
  
#### Configuration Validation (DefaultConfigurationValidator and ClusterValidationRules):

* Modular validation framework in place.
* Implemented and Tested Rules:
`ReferentialIntegrityValidator` (ID/name uniqueness, valid references including nextSteps/errorSteps, null/blank checks in lists).
* `CustomConfigSchemaValidator` (JSON schema validation for `JsonConfigOptions`).
* `WhitelistValidator` (Kafka topic and gRPC service whitelisting).
* `IntraPipelineLoopValidator` (detects loops within single pipelines via Kafka and/or nextSteps/errorSteps, using `JGraphT`).
* `InterPipelineLoopValidator` (detects loops between pipelines via Kafka, using `JGraphT`).

#### Orchestrator (DefaultConfigurationValidator) unit-tested (mock rules) and integration-tested (real rules via Micronaut DI).
* **Status**: **NEEDS RE-TESTING AFTER ABOVE IS DONE**.

#### In-Memory Caching (InMemoryCachedConfigHolder):

* Thread-safe, atomic caching.
* **Status**: **COMPLETE** (Unit and Concurrency Tested).

#### Eventing & Test Utilities:

* `ClusterConfigUpdateEvent` and `TestApplicationEventListener`.
* **Status**: **NEEDS RE-TESTING AFTER ABOVE IS DONE**.

#### Micronaut Integration & Testing Infrastructure:

* `ConsulClientFactory`, `ConsulCacheConfigFactory`.
* `ConsulTestResourceProvider` for `Testcontainers`.
* **Status**: **NEEDS RE-TESTING AFTER ABOVE IS DONE**.

#### Conclusion

The system is now confirmed to load a complex `PipelineClusterConfig` from Consul, perform deep static validation (including loop detection and schema validation), cache it thread-safely, and react to live updates (including errors and deletions) from Consul in a robust and observable manner.

## II. Immediate Follow-up Tasks (Finalizing Validator Updates for PipelineStepConfig)

While the core logic is in place, ensure the following are fully completed and tested based on the addition of `nextSteps` and `errorSteps` to `PipelineStepConfig`:

### `ReferentialIntegrityValidator`:

* **Verify Task**: Confirm that it robustly validates that all step IDs listed in `PipelineStepConfig.nextSteps()` and `PipelineStepConfig.
errorSteps()` exist as valid step IDs within the same `PipelineConfig`.
* **Verify Testing**: Confirm unit tests in `ReferentialIntegrityValidatorTest`.java cover these specific scenarios (valid/invalid 
  `nextSteps`/`errorSteps` references, empty lists).

### `IntraPipelineLoopValidator`:

* **Verify Task**: Confirm its graph construction logic now correctly uses nextSteps and errorSteps as the primary sources for directed edges.
* Re-evaluate and confirm the strategy for handling Kafka-mediated flows within the same pipeline if `nextSteps`/`errorSteps` are also 
  present (recommendation: explicit flow via `nextSteps`/`errorSteps` takes precedence for loop detection by this validator).
* **Verify Testing**: Confirm `IntraPipelineLoopValidatorTest.java` has been updated to reflect the new graph logic, thoroughly testing loops 
formed by these explicit execution paths.

## III. Next Major Phase: grpc-pipeline-engine - Core Runtime Logic
This is the largest remaining piece, responsible for executing pipelines based on the validated and loaded configurations.

#### 3.1. Engine Configuration & Bootstrap:
Define static application.yml properties for the engine node (Kafka brokers, gRPC client defaults, thread pool sizes).
Ensure `DynamicConfigurationManager` is utilized to access the active `PipelineClusterConfig`.

### 3.2. Pipeline Instance Lifecycle & Context:
* Design and implement `PipelineInstanceContext` for runtime execution tracking (e.g., trace ID, current data, step history).
* Create `PipelineExecutionManager` to manage pipeline instances based on triggers (e.g., Kafka messages, API calls) and configuration 
  updates from `DynamicConfigurationManager`.

### 3.3. Step Execution Orchestration (StepExecutor Service):

* **Data Flow Management**: Implement logic to route data/control based on `PipelineStepConfig.nextSteps()` (on success from gRPC module) and 
`PipelineStepConfig.errorSteps()` (on failure from gRPC module).
* **gRPC Client Integration**:
Use Micronaut's `DiscoveryClient` (backed by Consul) to find developer module instances based on `PipelineStepConfig.pipelineImplementationId()`.
* Manage gRPC client stubs, channels, incorporating deadlines, retries (for idempotent operations), and circuit breakers (e.g., using 
  `Resilience4j`).
* Construct `ProcessRequest` (from SDK) including customConfig, input data, and context. Handle `ProcessResponse`.

### 3.4. Kafka Integration (for steps with Kafka I/O):

* Publish to kafkaPublishTopics based on step output (if defined in PipelineStepConfig and after successful gRPC call if applicable).

### 3.4. Kafka Integration (Data Plane Entry/Exit Points):

* `PipelineEntryKafkaListener`: Dynamically manage/create Micronaut Kafka `Listener`s for `kafkaListenTopics` that act as pipeline entry points.
Pass consumed messages to `PipelineExecutionManager`/`StepExecutor`. Manage Kafka consumer groups and offset commits robustly.
* `KafkaOutputProducer`: A service/utility for the `StepExecutor` to publish messages, handling serialization and producer errors.

### 3.5. Runtime Error Handling (Engine Level):
* Define comprehensive strategies for unrecoverable Kafka errors (e.g., poison pills post-DLQ, persistent connection failures) and gRPC 
errors (e.g., service permanently unavailable post-retries/circuit breaker open).

### 3.6. Concurrency Model & Resource Management:

* Configure and manage thread pools for Kafka message processing, gRPC client calls, and asynchronous step transitions. Implement 
backpressure strategies. 

## grpc-developer-sdk (Software Development Kit)
   
### 4.1. Finalize .proto Service Contract: (e.g., PipelineStepProcessor.Process(ProcessRequest) returns (ProcessResponse)). 

* Ensure `ProcessRequest` includes `custom_config_json` and `ProcessResponse` allows clear success/failure indication and output.
   
### 4.2. Java Helper Library: Utilities for parsing custom_config_json, standardized error/response construction.
   
### 4.3. Documentation & Examples: Clear guides for module developers.

## Example developer-pipeline-step-modules
   
* **Task**: Implement and deploy (locally for testing) simple gRPC services (Echo, Transform, Filter) using the SDK. These are vital for 
testing the engine.

## 6. Admin API Service (Dedicated Micronaut Service)
   
### 6.1. API Endpoints Design (RESTful): For CRUD on PipelineClusterConfig and Schemas in Consul.
   
### 6.2. Service Logic:
* Use your ConsulKvService for writes to Consul.
* **Mandatory**: Use the `DefaultConfigurationValidator` (as a library/dependency) to validate any `PipelineClusterConfig` before writing it to 
  Consul.
* **Optional**: Pre-write checks for Kafka topic existence (using Kafka `AdminClient`) or schema compatibility.
### 6.3. Security: Implement authentication and authorization.

## 7. Operational Considerations & Security Hardening (Ongoing)
   
* Structured Logging, Metrics (`Micrometer`), Alerting, Distributed Tracing, Health Checks.
* Consul & Kafka ACLs, gRPC security (e.g., mTLS).

## Conclusion - next steps

This detailed plan, starting with the final validator updates and then moving to the engine, sets a clear path forward. The configuration subsystem is exceptionally well-prepared for these next stages.