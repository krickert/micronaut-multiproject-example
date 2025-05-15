# YAPPY Pipeline Platform: Remaining Tasks & Architecture Alignment

**Last Updated**: 2025-05-15 (Reflecting universal gRPC interface for modules, dual inter-framework transport, and precise Consul roles)

## Component Status Summary

| Component                                     | Description                                                                                                                                                              | Status Highlights                                  | Details Section                                                              |
| :-------------------------------------------- | :----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------- | :--------------------------------------------------------------------------- |
| **Core Data Models** | Protobufs & Java records for pipeline/schema configs.                                                                                                                      | LARGELY STABLE                                     | [I. Foundational Components Status](#i-foundational-components-status)       |
| **Dynamic Configuration Management** | Loads, validates, caches, & watches pipeline configs from Consul.                                                                                                          | LARGELY STABLE, Needs Validator/Retest             | [I. Foundational Components Status](#i-foundational-components-status)       |
| **Connector Service (Initial Ingest)** | Implements `PipeStreamEngine.IngestDataAsync`; starts pipelines via Kafka/gRPC. Registers with Consul.                                                                   | REFACTOR/IMPLEMENT                                 | [II.A. Connector Service (Initial Ingest Logic)](#a-connector-service-initial-ingest-logic) |
| **Module Framework (Step Engine)** | Runtime for step execution: `PipeStream` I/O (Kafka/gRPC), step config, **universal gRPC `PipeStepProcessor` invocation (in-process/network)**, routing. Registers with Consul. | **PRIORITY DEVELOPMENT** | [II.B. Module Framework (Step Engine)](#b-module-framework-step-engine)         |
| **SDK for Pipeline Module Processors** | Tools for implementing the universal `PipeStepProcessor` gRPC interface (local Java or polyglot remote gRPC).                                                              | NEEDS REFINEMENT                                   | [III. SDK for Pipeline Module Processors](#iii-sdk-for-pipeline-module-processors) |
| **Example Pipeline Module Processors** | Reference modules (internal Java/gRPC & remote gRPC) demonstrating `PipeStepProcessor`. Remote gRPC ones register with Consul.                                               | DEVELOPMENT/ALIGNMENT NEEDED                       | [IV. Example Pipeline Module Processors](#iv-example-pipeline-module-processors) |
| **Custom JSON Schema Registry Service** | gRPC service (`SchemaRegistryService.proto`) for module `custom_json_config` schemas. Registers with Consul.                                                              | IMPLEMENTED (per previous status), Verify Integration | [V. Custom JSON Schema Registry Service](#v-custom-json-schema-registry-service) |
| **Admin API Service** | Service for CRUD on `PipelineClusterConfig` (Consul) & potentially schemas. Registers with Consul.                                                                         | CONCEPTUAL, NEEDS IMPLEMENTATION                   | [VI. Admin API Service](#vi-admin-api-service)                               |
| **Operational Considerations & Security** | Logging, metrics, tracing, health checks (Consul for all services), ACLs, mTLS.                                                                                            | ONGOING for all components                         | [VII. Operational Considerations & Security](#vii-operational-considerations--security) |

---

This document outlines remaining tasks for YAPPY, reflecting a decentralized architecture where each step is managed by a **Module Framework** (a lightweight step engine). Module Frameworks route **`PipeStream`s** via **Kafka (async) or gRPC (sync to another Module Framework's `PipeStreamEngine` gRPC endpoint)**, as configured. All **Pipeline Module Processors** (business logic) implement a **universal gRPC interface (`PipeStepProcessor.proto`)** but can be invoked by their Module Framework either as an **in-process Java call (for internal Java/gRPC modules) or a network gRPC call (for localhost/remote modules).** Consul is key for configuration, service discovery (for gRPC interactions), and health monitoring of all YAPPY services.

## I. Foundational Components Status

### Core Data Models (`pipeline-config-models`, `schema-registry-models`, Protobufs):
* **Status**: LARGELY STABLE.
* **Protobufs**: `yappy_core_types.proto`, `pipe_step_processor.proto` (universal module interface), `engine_service.proto` (for `IngestDataAsync` and inter-framework `PipeStream` gRPC transport), `schema_registry.proto` (for Custom JSON Schema Registry).
* **Java Config Models**: Records in `pipeline-config-models` define `PipelineClusterConfig`, `PipelineConfig`, `PipelineStepConfig`, `PipelineModuleConfiguration`, `StepTransition` (with `KafkaTransportConfig` / `GrpcTransportConfig`).
* **Action**:
  * Ensure `PipelineModuleConfiguration` robustly defines `processorInvocationType` ("INTERNAL\_JAVA\_GRPC", "REMOTE\_GRPC\_SERVICE") and associated details (`javaClassName` for internal, `grpcProcessorServiceId` for remote).
  * Ensure `StepTransition` and its `TransportConfig` clearly define routing to the *next Module Framework's* Kafka topic or its `PipeStreamEngine` gRPC ingress endpoint (including `targetModuleFrameworkServiceId` for gRPC).

### Dynamic Configuration Management (`yappy-consul-config` module):
* **Status**: LARGELY STABLE and TESTED.
* **Action**: Confirm validators correctly interpret `StepTransition` with dual transport options for loop detection and referential integrity. Retest.

---

## II. Core Runtime Development: Decentralized Step Execution

### A. Connector Service (Initial Ingest Logic - Implements `PipeStreamEngine.IngestDataAsync`)

* **Goal**: Standardized, robust entry point for all pipeline initiations.
* **Tasks**:
  1.  **Implement `IngestDataAsync` RPC**: (Uses `IngestDataRequest` from `engine_service.proto`).
    * Determines initial `pipelineName` & `target_step_name` for the first Module Framework from `PipelineClusterConfig` via `DynamicConfigurationManager`.
    * Creates initial `PipeStream`.
  2.  **Initial `PipeStream` Dispatch**:
    * If first step's `TransportConfig` is Kafka: Publish `PipeStream` to the first step's Kafka topic.
    * If first step's `TransportConfig` is gRPC: Discover the first Module Framework's `PipeStreamEngine` gRPC ingress endpoint via Consul (using `targetModuleFrameworkServiceId`) and send `PipeStream` (e.g., via `processAsync` RPC).
  3.  **Configuration & Consul Registration**: Bootstrap with Kafka/gRPC client settings, `DynamicConfigurationManager`. Register this service with Consul for health/visibility.
* **Notes**: This is the primary "engine interface" for starting pipelines.

### B. Module Framework (Step Engine - Hosts/Proxies Module Processors)

* **Goal**: Reusable, lightweight engine executing a single `PipelineStepConfig`.
* **Core Responsibilities & Tasks**:
  1.  **`PipeStream` Ingress (Dual Mode)**:
    * Implement Kafka consumer logic to receive `PipeStream`s.
    * Implement a gRPC service endpoint (e.g., methods from `PipeStreamEngine.proto` like `process` or `processAsync`) to receive `PipeStream`s from other Module Frameworks or the Connector Service. This gRPC service endpoint is registered with Consul.
  2.  **Configuration & Validation**: Fetch `PipelineStepConfig` & `PipelineModuleConfiguration` from Consul; validate `custom_json_config` against Custom JSON Schema Registry.
  3.  **Pipeline Module Processor Invocation (Universal `PipeStepProcessor` gRPC Interface)**:
    * Prepare `ProcessRequest`.
    * Based on `PipelineModuleConfiguration.processorInvocationType`:
      * **"INTERNAL\_JAVA\_GRPC"**: Make a direct, in-process Java method call to the co-deployed Java object that implements the `PipeStepProcessor` gRPC interface.
      * **"REMOTE\_GRPC\_SERVICE"**: Act as gRPC client. Use Consul to discover the target Pipeline Module Processor service (via `grpcProcessorServiceId`) and make a network gRPC call to its `ProcessData` method. Implement client-side resilience (retries, circuit breakers).
    * Handle `ProcessResponse`.
  4.  **PipeStream State Update & History**: Update `PipeStream` from `ProcessResponse`, append `StepExecutionRecord`.
  5.  **`PipeStream` Egress (Dual Mode Routing)**:
    * Based on `ProcessResponse.success` and `nextSteps`/`errorSteps` (containing `StepTransition` with `TransportConfig`) from `PipelineStepConfig`:
      * If next transport is Kafka: Publish updated `PipeStream` to target Kafka topic.
      * If next transport is gRPC: Discover the next Module Framework's `PipeStreamEngine` gRPC ingress endpoint via Consul (using `targetModuleFrameworkServiceId`) and send `PipeStream`.
  6.  **Consul Registration**: Each Module Framework service instance registers with Consul for its own health, visibility, and for discovery of its gRPC `PipeStream` ingress endpoint (if offered).
* **Development**: Build as a core Java library/Micronaut application.

### C. Concurrency & Resource Management (for Module Frameworks)
* Manage thread pools for Kafka consumers and gRPC server handlers (for `PipeStream` ingress).

---

## III. SDK for Pipeline Module Processors

* **Goal**: Enable easy development of business logic units (Pipeline Module Processors) that conform to the universal `PipeStepProcessor` gRPC interface.
* **Tasks**:
  1.  **Finalize `pipe_step_processor.proto`**: Ensure `ProcessRequest` (`document`, `config`, `metadata`) and `ProcessResponse` are comprehensive for all invocation types.
  2.  **Java Support**:
    * Provide clear guidance and/or base classes for implementing the `PipeStepProcessor` gRPC interface as a standard Java class intended for **in-process invocation** by a Java Module Framework.
    * Standard Java gRPC service generation for **remote Java gRPC Processors**.
    * Helper library for parsing `ProcessRequest.config.custom_json_config` (Struct).
  3.  **Polyglot Support (Python, Rust, etc.)**:
    * Provide `.proto` files and instructions for generating gRPC stubs in other languages.
    * Clear examples of implementing the `PipeStepProcessor` service.
    * Emphasize that the Module Framework provides the platform integration benefits (Kafka, Consul config access, routing, observability hooks from Micronaut, etc.), allowing polyglot developers to focus solely on the `ProcessData` logic.
  4.  **Schema Usage Documentation**: Clarify handling of optional `custom_json_config` and schema references.

---

## IV. Example Pipeline Module Processors

* **Goal**: Reference implementations for testing and developer guidance.
* **Tasks**:
  1.  **Echo Module**:
    * **Internal Java/gRPC Type**: Java class implementing `PipeStepProcessor`, invoked in-process by a test Module Framework.
    * **Remote gRPC Type**: Python gRPC service implementing `PipeStepProcessor`, registers with Consul, invoked over network by a test Module Framework.
  2.  **FieldMapper Module (e.g., as a Remote Java gRPC Service)**: Registers with Consul.
  3.  **Chunker Module (Align `yappy-modules/chunker` as a Remote Java gRPC Service)**: Registers with Consul.
* **Note on Registration**: Separately deployed remote gRPC Pipeline Module Processors manage their own Consul registration. Module Frameworks also register themselves. Internal Java/gRPC modules do not register separately.

---

## V. Custom JSON Schema Registry Service (`SchemaRegistryService.proto` implementation)

* **Status**: Marked as "Implemented" in previous architecture diagrams. (Verify current implementation status and ensure it aligns with being a gRPC service that registers with Consul).
* **Tasks**:
  1.  Ensure service implements `SchemaRegistryService.proto` for CRUD and validation of module JSON schemas.
  2.  Ensure it registers with Consul for discovery by Module Frameworks and Admin API.
  3.  Develop client libraries or usage patterns for Module Frameworks and Admin API to interact with it.

---

## VI. Admin API Service

* **Status**: Conceptual.
* **Tasks**:
  1.  **API Design**: For CRUD on `PipelineClusterConfig` (in Consul) and interaction with Custom JSON Schema Registry Service.
  2.  **Service Logic**: Use `DefaultConfigurationValidator` *before* writing to Consul.
  3.  **Consul Registration**: Registers this service with Consul.
  4.  **Security**: AuthN/AuthZ.

---

## VII. Operational Considerations & Security

* **Consul Registrations**: Explicitly list all YAPPY services that register with Consul:
  1.  Connector Service (Initial Ingest)
  2.  Module Framework instances (for own health/visibility, and for gRPC `PipeStream` ingress if offered)
  3.  Remote gRPC Pipeline Module Processor services
  4.  Custom JSON Schema Registry service
  5.  Admin API service
* **Health Checks**: Define health check mechanisms for all registered services.
* **Observability**: Structured Logging, Metrics, Distributed Tracing across all components and transport hops (Kafka & gRPC).
* **Security**: ACLs for Consul/Kafka; mTLS for all inter-service gRPC calls (Framework-to-Framework, Framework-to-RemoteProcessor, calls to SchemaRegistry/AdminAPI).
* **Module Service Registration Lifecycle**: Define processes for deploying and registering/deregistering remote gRPC Pipeline Module Processors in Consul.
