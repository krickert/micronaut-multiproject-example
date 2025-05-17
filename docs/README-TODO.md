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

Here's how the code SHOULD work for pipeline-engine...



Wea re building a flexible pipeline.  



A cluster is the entire runing ecosystem.  All applicaitons run on a cluster.  Each network of nodes work through multiple pipelines.  



Each node in the pipeline is an INSTANCE of a pipstep.  There can be multiple instances of it within a pipeline.  For example, a chunker can run 3x but just have different configurations.



That brings up two main points: how nodes forward from one node to the next and how nodes get configuration.



We'll start with configuration.



Custom config for PipeStep implementations



The configuration runs on a PipeStep service basis.  The config is validated against a JSON schema validator.



This automatically loads from consul.  That code is considered complete and is backed up by a schema registry - which will aid in "registering" a new service into a pipeline.



How nodes forward from one node to another:



Everything under the hood is a PipeStep implementation.  There are three types of implementattions (Sink is not done yet in grpc).  All three of these implmentations in the config exist as an extension of a regular pipestep except that a connector takes no inputs and a sink takes no outputs.



Each pipestep implementation is connected to a pipeline.  These pipelines a meant to work with two processes on a container:

1) gRPC engine - handles all the routing between nodes.  Will either forward to a kafka topic owned by another pipeline step, a sink step, or another grpc engine endpoint that represents a pipeline implementation.

2) kafka - inputs are the same inputs as a gRPC engine.  Both take in a PipeStream and output the same way as I mentioned above for a grpc step.  In other words, when a kafka request comes in, it is the same as a grpc step coming in.  They both sare the same routing strategy







The entire pipeline is an acyclical graph. 



Each "node" is a PipeStep implementation.  There are multiple engines that run, each with their own pipe step processor.  The pipe step processor is mentioned in more detail below.



The pipestep can define a schema.  This is a global schema for that particular pipestep.



This allows for custom step configuration options on a step level.





here are the grpc definitions:





yappy_core_types.proto: 

Defines all the basic structures to be used by the service



engine_service.proto:

Engine-to-engine communication... 







syntax = "proto3";



package com.krickert.search.model;



option java_multiple_files = true;

option java_package = "com.krickert.search.model";

option java_outer_classname = "YappyCoreTypesProto";



import "google/protobuf/timestamp.proto";

import "google/protobuf/struct.proto";



// --- Core Document Representation ---



// Represents a single named vector embedding, typically for a whole document or a non-chunk segment.

message Embedding {

// Optional: Name or identifier for the model that generated this embedding.

optional string model_id = 1;

repeated float vector = 2;        // The vector representation.

}



// Represents the text content and vector embedding for a single chunk.

message ChunkEmbedding {

string text_content = 1;        // The actual text content of the chunk.

repeated float vector = 2;       // The vector embedding for this chunk's text.

optional string chunk_id = 3;     // Unique identifier for this chunk.

optional int32 original_char_start_offset = 4; // Optional: start offset in original document.

optional int32 original_char_end_offset = 5;   // Optional: end offset in original document.

optional string chunk_group_id = 6; // Optional: Identifier for a group of related chunks.

optional string chunk_config_id = 7; // Optional: Identifier for the chunking configuration used.

}



// Represents a single semantic chunk of text with its embedding.

message SemanticChunk {

string chunk_id = 1;              // Unique identifier for this specific chunk within its parent SemanticProcessingResult.

int64 chunk_number = 2;           // Sequential number of the chunk within its parent SemanticProcessingResult.

ChunkEmbedding embedding_info = 3; // The text and embedding for this chunk.

map<string, google.protobuf.Value> metadata = 4; // Optional metadata specific to this chunk (e.g., original page number, section).

}



// Represents the complete result of one specific semantic chunking and/or embedding process

// applied to a field of a PipeDoc. A PipeDoc can store multiple such results.

message SemanticProcessingResult {

string result_id = 1;               // Unique ID for this specific result set (e.g., UUID for this instance of processing).

string source_field_name = 2;       // Name of the field within the parent PipeDoc that was processed (e.g., "body", "title").



string chunk_config_id = 3;         // Identifier for the chunking configuration used (e.g., "sentence_splitter_v1", "token_chunker_512_overlap_50").

string embedding_config_id = 4;     // Identifier for the embedding model/configuration used (e.g., "ada_002_v1", "minilm_l6_v2").



// A generated identifier/name for this set of (chunked and) embedded data.

// Useful as a prefix for field names in a search index or for display/selection.

// Example: "body_chunks_ada_002", "title_sentences_minilm"

// This would typically be generated by the pipeline step that produces this result.

optional string result_set_name = 5;



repeated SemanticChunk chunks = 6;    // List of semantic chunks with their embeddings produced by this specific configuration.

map<string, google.protobuf.Value> metadata = 7; // Metadata about this specific processing run (e.g., model version details, execution time).

}



message PipeDoc {

string id = 1;                          // REQUIRED. Unique identifier for the document.

optional string source_uri = 2;         // Optional. URI where the original document came from (e.g., s3://, http://).

optional string source_mime_type = 3;   // Optional. Original MIME type of the content that *led* to this PipeDoc (e.g., "application/pdf").



optional string title = 4;

optional string body = 5;               // Main textual content, often the target for chunking/embedding.

repeated string keywords = 6;

optional string document_type = 7;      // e.g., "article", "product", "email".

optional string revision_id = 8;        // Optional. Version identifier for the source content.



optional google.protobuf.Timestamp creation_date = 9;   // When the source document was created or first seen.

optional google.protobuf.Timestamp last_modified_date = 10; // When the source document content was last modified.

optional google.protobuf.Timestamp processed_date = 11;   // When this PipeDoc representation was last significantly processed/updated by the pipeline.



optional google.protobuf.Struct custom_data = 12; // For flexible, non-standard structured data related to the document.



// Holds results from potentially multiple, different chunking and/or embedding processes applied to this document.

repeated SemanticProcessingResult semantic_results = 13;



// Map for storing other named embeddings (e.g., whole document embeddings, image embeddings).

// Key could be a descriptive name like "full_document_ada_002_embedding" or "header_image_clip_embedding".

map<string, Embedding> named_embeddings = 14;



optional Blob blob = 15;

}



// --- Binary Data Handling ---



message Blob {

optional string blob_id = 1;            // Optional: Unique identifier for this blob (e.g., hash of content or UUID).

bytes data = 2;                     // REQUIRED. The raw binary content.

optional string mime_type = 3;        // MIME type of the content in 'data' (e.g., "application/pdf", "image/jpeg").

optional string filename = 4;           // Optional: Original filename associated with this binary data.

optional string encoding = 5;           // Optional: Character encoding if 'data' represents text (e.g., "UTF-8").

map<string, string> metadata = 6;     // Optional: Additional key-value metadata specific to this blob.

}



// --- Error and History Structures ---



// Captures input state for a failed step attempt, used within ErrorData.

message FailedStepInputState {

// The PipeDoc as it was *before* the failed step was attempted.

optional PipeDoc doc_state = 1;

// The Blob as it was *before* the failed step was attempted.

optional Blob blob_state = 2;

// The custom_json_config (as Struct) provided to the failed step.

optional google.protobuf.Struct custom_config_struct = 3;

// The config_params provided to the failed step.

map<string, string> config_params = 4;

}



message ErrorData {

string error_message = 1;               // REQUIRED. Human-readable description of the error.

optional string error_code = 2;         // Optional. Machine-readable error code (e.g., "CONFIG_VALIDATION_ERROR", "TIMEOUT_ERROR").

optional string technical_details = 3;    // Optional. Snippet of stack trace, detailed diagnostic information.

string originating_step_name = 4;     // REQUIRED. The 'stepName' of the PipelineStepConfig where the error originated or was detected.

optional string attempted_target_step_name = 5; // Optional. If error occurred during an attempt to route or dispatch to a *next* step.

optional FailedStepInputState input_state_at_failure = 6; // Optional. State of data/config when the step failed, for reproducibility.

google.protobuf.Timestamp timestamp = 7;  // REQUIRED. When the error occurred or was logged.

}



message StepExecutionRecord {

int64 hop_number = 1;                       // Sequential hop number for this step in the stream.

string step_name = 2;                        // 'stepName' of the PipelineStepConfig that was executed.

optional string service_instance_id = 3;      // Optional. Identifier of the specific service instance/pod that executed the step.

google.protobuf.Timestamp start_time = 4;     // When step processing began.

google.protobuf.Timestamp end_time = 5;       // When step processing ended.

// Expected statuses: "SUCCESS", "FAILURE", "SKIPPED" (add more as needed, e.g., "RETRYING")

string status = 6;                           // REQUIRED. Outcome of the step.

repeated string processor_logs = 7;           // Logs specifically from the processor for this step's execution.

optional ErrorData error_info = 8;            // Specific error from *this step* if status is "FAILURE".

// This is distinct from PipeStream.stream_error_data.

}



// --- Pipeline Execution State ---



message PipeStream {

string stream_id = 1;                       // REQUIRED. Unique ID for this execution flow instance.

PipeDoc document = 2;                       // REQUIRED (can be an empty message initially). The primary document being processed.

string current_pipeline_name = 3;           // REQUIRED. Name of the PipelineConfig being executed.



// REQUIRED by the sender (Engine or Kafka Framework).

// The 'stepName' (key from PipelineConfig.steps map) of the PipelineStepConfig

// that is the intended next recipient/processor of this PipeStream.

string target_step_name = 4;



int64 current_hop_number = 5;               // For logging/tracing; incremented by the engine/framework *before* dispatching to

// target_step_name.

repeated StepExecutionRecord history = 6;     // History of executed steps in this stream.

optional ErrorData stream_error_data = 7;     // Holds the first critical error that puts the *entire stream* into a general error

// state, possibly halting further processing unless handled by an error pipeline.

map<string, string> context_params = 8;     // Optional. Key-value parameters for the entire run's context (e.g., tenant_id, user_id,

// correlation_id).

}







Schema registry grpc

syntax = "proto3";



package com.krickert.search.schema.registry;



option java_multiple_files = true;

option java_package = "com.krickert.search.schema.registry";

option java_outer_classname = "SchemaRegistryServiceProto";



import "google/protobuf/empty.proto";

import "google/protobuf/timestamp.proto";



// Service for managing JSON schema definitions used by pipeline steps.

// These schemas define the expected structure for the 'custom_json_config'

// field within a PipelineStepConfig.

service SchemaRegistryService {

// Registers a new JSON schema or updates an existing one.

// The schema content itself is validated for being a valid JSON schema.

// If a schema with the same schema_id already exists, it will be updated.

rpc RegisterSchema(RegisterSchemaRequest) returns (RegisterSchemaResponse);



// Retrieves a specific JSON schema by its ID.

rpc GetSchema(GetSchemaRequest) returns (GetSchemaResponse);



// Deletes a JSON schema by its ID.

rpc DeleteSchema(DeleteSchemaRequest) returns (DeleteSchemaResponse);



// Lists all registered JSON schemas, with optional filtering.

rpc ListSchemas(ListSchemasRequest) returns (ListSchemasResponse); // <--- Description updated



// Validates the provided JSON schema content without registering it.

// This can be used to check schema correctness before attempting registration.

rpc ValidateSchemaContent(ValidateSchemaContentRequest) returns (ValidateSchemaContentResponse);

}



// Contains the definition and metadata of a JSON schema.

message SchemaInfo {

// REQUIRED. A unique identifier for this schema (e.g., "my-processor-config-schema-v1").

// This ID is used in PipelineStepConfig.customConfigSchemaId to reference this schema.

string schema_id = 1;



// REQUIRED. The actual JSON schema content as a string.

string schema_content = 2;



// Optional. A human-readable description of the schema.

optional string description = 3;



// Timestamp of when this schema was first created.

google.protobuf.Timestamp created_at = 4;



// Timestamp of when this schema was last updated.

google.protobuf.Timestamp updated_at = 5;



// Optional. Additional metadata for the schema (e.g., author, version tags).

map<string, string> metadata = 6;



// Reserved for future use, e.g., "JSON_SCHEMA", "AVRO". Currently implies "JSON_SCHEMA".

// string schema_type = 7;

}



// Request to register or update a JSON schema.

message RegisterSchemaRequest {

// REQUIRED. The unique identifier for the schema.

string schema_id = 1;



// REQUIRED. The JSON schema content as a string.

string schema_content = 2;



// Optional. A human-readable description.

optional string description = 3;



// Optional. Additional metadata.

map<string, string> metadata = 4;

}



// Response from a RegisterSchema operation.

message RegisterSchemaResponse {

// The schema_id of the registered or updated schema.

string schema_id = 1;



// True if the schema was successfully validated and registered/updated.

// False if validation of the schema_content itself failed.

bool success = 2;



// List of validation errors if success is false. Empty if successful.

// These errors pertain to the validity of the schema_content as a JSON schema document.

repeated string validation_errors = 3;



// Timestamp of the registration or update.

google.protobuf.Timestamp timestamp = 4;

}



// Request to retrieve a JSON schema.

message GetSchemaRequest {

// REQUIRED. The unique identifier of the schema to retrieve.

string schema_id = 1;

// Optional: string version_id = 2; // If versioning per schema_id is implemented later.

}



// Response containing the retrieved JSON schema.

message GetSchemaResponse {

// The requested schema information.

// If not found, a gRPC NOT_FOUND error status will be returned.

SchemaInfo schema_info = 1;

}



// Request to delete a JSON schema.

message DeleteSchemaRequest {

// REQUIRED. The unique identifier of the schema to delete.

string schema_id = 1;

// Optional: string version_id = 2; // If versioning per schema_id is implemented later.

}



// Response from a DeleteSchema operation.

// Returns google.protobuf.Empty on successful deletion.

// Returns gRPC NOT_FOUND error status if schema_id does not exist.

message DeleteSchemaResponse {

google.protobuf.Empty acknowledgement = 1;

}



// Request to list JSON schemas.

message ListSchemasRequest {

// Optional. A filter to apply to schema_id (e.g., prefix match).

optional string id_filter = 1;

// page_size and page_token removed

}



// Response containing a list of JSON schemas.

message ListSchemasResponse {

// A list of schema information objects.

repeated SchemaInfo schemas = 1;

// next_page_token removed

}



// Request to validate JSON schema content.

message ValidateSchemaContentRequest {

// REQUIRED. The JSON schema content as a string to be validated.

string schema_content = 1;

}



// Response from a ValidateSchemaContent operation.

message ValidateSchemaContentResponse {

// True if the provided schema_content is a valid JSON schema document.

bool is_valid = 1;



// List of validation errors if is_valid is false. Empty if valid.

repeated string validation_errors = 2;

}





Engine service

syntax = "proto3";



package com.krickert.search.engine;



option java_multiple_files = true;

option java_package = "com.krickert.search.engine";

option java_outer_classname = "EngineServiceProto";



import "yappy_core_types.proto"; // This should define com.krickert.search.model.PipeDoc

import "google/protobuf/empty.proto";



// --- Messages for the IngestDataAsync RPC ---



message IngestDataRequest {

// REQUIRED. An identifier for the connector or data source submitting this data.

// The engine will use this ID to look up the pre-configured target pipeline and initial step.

// Example: "s3-landing-bucket-connector", "customer-api-ingest-v1".

string source_identifier = 1;



// REQUIRED. The initial document data to be processed.

// The Blob is expected to be within this PipeDoc as per your updated yappy_core_types.proto.

com.krickert.search.model.PipeDoc document = 2;



// Optional. Key-value parameters to be included in the PipeStream's context_params.

// Useful for passing global run context like tenant_id, user_id, correlation_id from the connector.

map<string, string> initial_context_params = 3;



// Optional. If the connector wants to suggest a stream_id.

// If empty, the engine MUST generate a unique one.

// If provided, the engine MAY use it or generate its own if there's a conflict or policy.

optional string suggested_stream_id = 4;

}



message IngestDataResponse {

// The unique stream_id assigned by the engine to this ingestion flow.

// This allows the connector to correlate this ingestion with the pipeline execution.

string stream_id = 1;



// Indicates if the ingestion request was successfully accepted and queued by the engine.

// This does not guarantee the pipeline itself will succeed, only that ingestion was accepted.

bool accepted = 2;



// Optional message, e.g., "Ingestion accepted for stream ID [stream_id], targeting configured pipeline."

string message = 3;

}





// PipeStreamEngine service orchestrates pipeline execution.

service PipeStreamEngine {

// --- Existing RPCs (can be kept for specific internal/advanced use cases or deprecated over time) ---



rpc process(com.krickert.search.model.PipeStream) returns (com.krickert.search.model.PipeStream);

rpc processAsync(com.krickert.search.model.PipeStream) returns (google.protobuf.Empty);



// --- New Recommended RPC for Connectors ---



// Ingests new data (as a PipeDoc identified by a source_identifier) into the system

// to start a pipeline asynchronously. The engine will:

// 1. Use source_identifier to look up the configured target pipeline and initial step.

// 2. Create the PipeStream, generate a stream_id.

// 3. Initiate the pipeline.

rpc IngestDataAsync(IngestDataRequest) returns (IngestDataResponse);

}







Pipestep processor:

syntax = "proto3";



// Assuming your yappy_core_types.proto is in com.krickert.search.model

// and generates Java classes into com.krickert.search.model

package com.krickert.search.model; // Or com.krickert.search.sdk if that's where PipeStepProcessor lives



option java_package = "com.krickert.search.sdk"; // Or com.krickert.search.model - for generated Java

option java_multiple_files = true;

option java_outer_classname = "PipeStepProcessorServiceProto";



// Import definitions from your core types file

import "yappy_core_types.proto"; // This file should contain PipeDoc, Blob, StepExecutionRecord, ErrorData etc.

import "google/protobuf/struct.proto"; // For custom_json_config



// Service definition for a pipeline step processor.

// This interface is implemented by developer-created gRPC modules/services.

service PipeStepProcessor {

// Processes a document according to the step's configuration and logic.

rpc ProcessData(ProcessRequest) returns (ProcessResponse);

}



// Contains metadata provided by the pipeline engine for context.

// This data is generally for informational purposes, logging, tracing, or advanced conditional logic.

message ServiceMetadata {

// The 'pipelineName' from PipelineConfig providing context for this call.

string pipeline_name = 1;



// The 'stepName' from PipelineStepConfig that this gRPC service instance is currently executing as.

string pipe_step_name = 2;



// Unique ID for the entire execution flow (equivalent to PipeStream.stream_id).

string stream_id = 3;



// The current hop number in the pipeline for this step's execution.

int64 current_hop_number = 4;



// History of previously executed steps in this stream.

// Note: This can be large. Modules should use it judiciously.

repeated StepExecutionRecord history = 5;



// If the overall stream was previously marked with a critical error.

// Modules might use this to alter behavior (e.g., skip processing if stream is already failed).

optional ErrorData stream_error_data = 6;



// Key-value parameters for the entire run's context (e.g., tenant_id, user_id, correlation_id).

// Equivalent to PipeStream.context_params.

map<string, string> context_params = 7;

}



// Contains configuration specific to this instance of the pipeline step.

message ProcessConfiguration {

// The specific, validated custom JSON configuration for this step,

// converted by the engine from PipelineStepConfig.customConfig.jsonConfig.

google.protobuf.Struct custom_json_config = 1;



// The 'configParams' map from PipelineStepConfig for this step.

map<string, string> config_params = 2;

}



// Request message for the ProcessData RPC.

message ProcessRequest {

// The primary document data to be processed.

// The Blob is now expected to be within PipeDoc if used.

PipeDoc document = 1;



// Configuration for this specific processing step.

ProcessConfiguration config = 2;



// Engine-provided metadata for context and observability.

ServiceMetadata metadata = 3;

}



// Response message for the ProcessData RPC.

// This is returned by the gRPC Service Implementation back to the engine.

// This message can remain largely the same as your existing ProcessResponse.

message ProcessResponse {

// Outcome: True if this step's processing was successful, false otherwise.

bool success = 1;



// Output Data: The modified or newly created PipeDoc.

// either remains unchanged or that this step does not modify the PipeDoc directly.

// The Blob is part of the PipeDoc.

optional PipeDoc output_doc = 2;



// Error Details: Structured error information from *this processor* if success is false.

// This is for errors specific to the processor's execution logic.

optional google.protobuf.Struct error_details = 3; // Using Struct for flexibility.



// Logging: Logs or summary information generated by this processor step.

repeated string processor_logs = 4;

}





The config service handles all the configuration to the engine. The registry handles the JSON configuration for a particualr pipestep (the pipestep config schema itself is on a per application / Internal processor basis)



I am having trouble deciding how to code all of this effectively. I started to, but most of the pipeengine code is a bit off with bad assumptions.



can we go over the next steps we can methodically go over?



I think we should first attack the routing because it's most complicated. Then implement the grpc forwarder and write tests. Then implement the kafka forwarder and write tests. Then we can tie it together and implement the specific routing.



Filling out the new response along the way is key - as soon as a new PipeStream starts, the response PipeStream should be created and hydrated as the service is rendered.



Can we come up with a plan for this? Then lets execute it

# TODO part 2
Okay, this is a comprehensive overview of your "Yappy" pipeline engine! It's an ambitious and well-thought-out system. Let's break down your request and formulate a plan.

You've got a solid grasp of the overall architecture, the role of gRPC, Kafka, Consul for configuration, and the schema registry. Your protobuf definitions are detailed and cover many necessary aspects of data representation, execution history, and error handling.

Your instinct to tackle **routing first** is excellent. It's the heart of the engine, and getting it right will make implementing the other pieces much smoother. The idea of creating and hydrating the `PipeStream` (which acts as both the data carrier and the "response" in terms of history and status) throughout the process is also spot on.

Here's a proposed plan, building on your suggestions, and incorporating the context of your project structure and existing code (like the `GrpcForwarder` we discussed):

**Phase 0: Foundations & Configuration (Partially Done)**

* **Goal:** Ensure all configuration models (Java POJOs like `PipelineConfig`, `PipelineStepConfig`, etc.) are stable and can be loaded/validated from Consul. Ensure protobuf models are generated and accessible.
* **Status:**
  * Protobuf definitions: Looks very good and detailed. Java classes should be generated via your Gradle build (`yappy-models/protobuf-models`).
  * Configuration POJOs: You have a `yappy-models/pipeline-config-models` module. These seem to represent the structure of your Consul configurations.
  * Consul Configuration Loading: `yappy-consul-config` module exists, which seems responsible for fetching, caching, and validating these configurations. `DynamicConfigurationManagerImpl` looks like a key class.
  * Schema Registry for `custom_json_config`: `SchemaRegistryService` (gRPC) and `yappy-schema-registry` module are in place.
* **Next Steps (Consolidation/Verification):**
  1.  **Verify Protobuf Generation:** Ensure `yappy-models/protobuf-models/build.gradle.kts` is correctly configured to generate Java classes from all your `.proto` files (`yappy_core_types.proto`, `engine_service.proto`, `pipestep_processor.proto`, `schema_registry.proto`). Make sure these generated classes are easily usable by other modules.
  2.  **Consul Config Loading Robustness:**
    * Thoroughly test the `yappy-consul-config` module. Ensure it can reliably load `PipelineClusterConfig`, `PipelineConfig`, and `PipelineStepConfig` from Consul.
    * Ensure the validation rules you've outlined (and potentially implemented in the `validator` package) are comprehensive (e.g., referential integrity, no cycles, valid step types, schema validation for `custom_json_config`).
    * Confirm that updates from Consul are correctly propagated and events (like `ClusterConfigUpdateEvent`) are published if needed.
  3.  **Schema Registry Integration (for `custom_json_config`):**
    * When `PipelineStepConfig` with a `customConfigSchemaId` is loaded by the engine, ensure the `yappy-consul-config` or a dedicated service can use the `SchemaRegistryService` client to fetch the schema and validate the `custom_json_config` against it. This is vital for step-specific configuration.

**Phase 1: Core Engine Logic & Routing Strategy (The "Brain")**

* **Goal:** Define and implement the central routing logic within the `PipeStreamEngine`. This component will decide where a `PipeStream` goes next based on the current state and configuration.
* **Key Classes/Modules:**
  * `PipeStreamEngineImpl` (in `yappy-engine/src/main/java/com/krickert/search/engine/orchestration/`) - This will be the central orchestrator.
  * `PipelineConfig`, `PipelineStepConfig`, `PipelineGraphConfig` (from `yappy-models/pipeline-config-models`) - These will drive the routing decisions.
  * `PipeStream` (protobuf) - The message flowing through the system.
* **Next Steps:**
  1.  **Define `PipeStream` Lifecycle Management:**
    * **Initialization:** When does a `PipeStream` get created?
      * Primarily via `PipeStreamEngine.IngestDataAsync()`: This RPC will be the main entry point for new data. It needs to:
        * Generate a `stream_id`.
        * Create an initial `PipeStream` object, populating it with the `PipeDoc` from `IngestDataRequest`, `source_identifier` (to find the initial pipeline/step), `current_pipeline_name`, and `initial_context_params`.
        * Determine the *first* `target_step_name` based on `source_identifier` and the loaded `PipelineConfig`.
    * **Hydration (History & Status):** As a `PipeStream` moves:
      * Increment `current_hop_number`.
      * Before dispatching to a step, add a `StepExecutionRecord` to its `history` (with `startTime`, `step_name`).
      * After a step executes (or fails), update that `StepExecutionRecord` (`endTime`, `status`, `processor_logs`, `error_info`).
      * If a critical error occurs, populate `PipeStream.stream_error_data`.
  2.  **Implement Routing Logic in `PipeStreamEngineImpl`:**
    * **`process(PipeStream)` / `processAsync(PipeStream)` (Internal):** These methods (from your gRPC `PipeStreamEngine` service) will be the core for advancing a `PipeStream`. The `processAsync` is likely what your Kafka listeners and internal steps will eventually call.
    * **Input:** An incoming `PipeStream`.
    * **Action:**
      a.  **Load Configurations:** Ensure `PipeStreamEngineImpl` has access to the necessary `PipelineConfig` (identified by `PipeStream.current_pipeline_name`) and the specific `PipelineStepConfig` for the `PipeStream.target_step_name`.
      b.  **Execute Current Step (Conceptual):** Determine *how* to execute the `target_step_name`. This involves:
      * Identifying the `StepType` (INTERNAL, GRPC, KAFKA_PUBLISH).
      * Preparing the request for that step (e.g., for a gRPC `PipeStepProcessor`, create `ProcessRequest`).
      c.  **Determine Next Hop(s):** After the current step *conceptually* completes (or even before, for Kafka publishing if it's a non-blocking "send"), consult the `PipelineStepConfig.outputs` (or similar in `PipelineGraphConfig` if you use that directly for graph traversal):
      * A step can have multiple outputs, potentially conditional. For now, assume simple named outputs.
      * For each active output, determine the *next* `target_step_name(s)` and the `transportType` (GRPC, KAFKA) to reach them.
      d.  **Forwarding:** Based on the `transportType` to the next step:
      * If GRPC: Use the `GrpcForwarder`.
      * If KAFKA: Use the `KafkaForwarder`.
      * If SINK: (To be designed) Potentially call a specific method or use a "SinkForwarder."
      * If INTERNAL: Call another method/processor within the same engine instance.
  3.  **Data Structures for Routing:**
    * Consider how `PipelineGraphConfig` (with its `nodes` and `edges`) will be used. It might be more explicit for routing than just relying on `PipelineStepConfig.outputs`. The engine needs to easily look up "if I am at step X, and it produces output Y, what is step Z and how do I get there?"
  4.  **Initial `PipeStreamEngine.IngestDataAsync()` Implementation:**
    * Implement the `IngestDataAsync` RPC.
    * It should create the `PipeStream`, identify the first `target_step_name`.
    * It should then trigger the internal processing mechanism (e.g., call a method that embodies the logic from step 2.b above, which then leads to 2.c and 2.d).
    * Return `IngestDataResponse` quickly (ack).
  5.  **Unit Tests for Routing Logic:**
    * Mock `PipelineConfig` data.
    * Test various scenarios: single next step, multiple next steps (fan-out), conditional routing (if you add it later).
    * Verify `PipeStream` history and hop numbers are updated correctly.

**Phase 2: gRPC Forwarding & `PipeStepProcessor` Interaction**

* **Goal:** Enable the engine to forward a `PipeStream` to an external gRPC `PipeStepProcessor` service and handle its response.
* **Key Classes/Modules:**
  * `GrpcForwarder` (from `yappy-engine/.../grpc/`) - You've already started this.
  * `PipeStepProcessorGrpc.PipeStepProcessorStub` (generated) - For calling external processors.
  * `PipeStreamEngineImpl` - To invoke the `GrpcForwarder`.
  * `ProcessRequest`, `ProcessResponse` (protobuf) - The contract with `PipeStepProcessor`.
* **Next Steps:**
  1.  **Refine `GrpcForwarder` (as discussed previously):**
    * Ensure it uses the *asynchronous* stub for `PipeStepProcessor`.
    * The `forwardToGrpc` method should take the `ProcessRequest` (built by the engine) and the target service name (e.g., Consul app name for the `PipeStepProcessor` module).
    * It should handle the `StreamObserver` callbacks from the async gRPC call.
      * `onNext(ProcessResponse response)`: This is key. The engine receives the `ProcessResponse`. It needs to:
        * Update the `PipeStream` with the `output_doc` from the response.
        * Log `processor_logs`.
        * If `response.success == false`, record the `error_details` in the current `StepExecutionRecord` and potentially in `PipeStream.stream_error_data`.
        * Crucially, after processing the response, trigger the *next phase of routing* within the engine for this `PipeStream` (i.e., determine next step(s) based on this successful or failed step completion).
      * `onError(Throwable t)`: Handle gRPC communication errors. Update `StepExecutionRecord` and `PipeStream.stream_error_data`. Potentially trigger error handling pipelines.
  2.  **Engine Logic to Call `PipeStepProcessor`:**
    * When the routing logic in `PipeStreamEngineImpl` determines the current step is a gRPC call to a `PipeStepProcessor`:
      a.  **Build `ProcessRequest`:**
      * `document`: The current `PipeStream.document`.
      * `config`:
      * `custom_json_config`: Load the `PipelineStepConfig.customConfig.jsonConfig` for the target step.
      * `config_params`: From `PipelineStepConfig.configParams`.
      * `metadata`:
      * `pipeline_name`: `PipeStream.current_pipeline_name`.
      * `pipe_step_name`: `PipeStream.target_step_name`.
      * `stream_id`: `PipeStream.stream_id`.
      * `current_hop_number`: `PipeStream.current_hop_number`.
      * `history`: `PipeStream.history`.
      * `stream_error_data`: `PipeStream.stream_error_data`.
      * `context_params`: `PipeStream.context_params`.
      b.  **Invoke `GrpcForwarder`:** Pass the `ProcessRequest` and the Consul service name of the `PipeStepProcessor`.
  3.  **Develop a Sample `PipeStepProcessor` (e.g., the "Echo" module):**
    * Create a simple gRPC service implementing `PipeStepProcessor`.
    * It should receive `ProcessRequest`, perhaps log some info, and return a `ProcessResponse`.
    * The "Echo" module in `yappy-modules/echo` could be adapted/used for this. It already has an `EchoService` which could be made to implement `PipeStepProcessorGrpc.PipeStepProcessorImplBase`.
  4.  **Integration Tests for gRPC Forwarding:**
    * Engine receives a `PipeStream`.
    * Engine correctly identifies a gRPC step.
    * Engine builds `ProcessRequest`.
    * `GrpcForwarder` calls the sample `PipeStepProcessor`.
    * Sample `PipeStepProcessor` returns a `ProcessResponse`.
    * Engine correctly processes the `ProcessResponse`, updates `PipeStream`, and (crucially) proceeds to determine the *next* step in the pipeline.

**Phase 3: Kafka Forwarding & Consumption**

* **Goal:** Enable the engine to publish a `PipeStream` to a Kafka topic and for an engine instance (possibly a different one) to consume it from Kafka and continue processing.
* **Key Classes/Modules:**
  * `KafkaForwarder` (you have a `tmp/KafkaForwarder.java`) - For publishing.
  * Micronaut Kafka Listeners (`@KafkaListener`) - For consuming.
  * `PipeStreamEngineImpl` - To invoke `KafkaForwarder` and to handle messages from Kafka listeners.
* **Next Steps:**
  1.  **Implement `KafkaForwarder`:**
    * It should take a `PipeStream` and a target Kafka topic name (derived from `PipelineStepConfig` for the *next* step that's Kafka-based).
    * Use Micronaut Kafka's declarative Kafka clients (`@KafkaClient`) for robust and configurable publishing.
    * Ensure it's asynchronous ("fire and forget" publish). Log success/failure of the send operation itself.
  2.  **Engine Logic for Kafka Publishing:**
    * When routing logic determines the next step is reached via Kafka:
      * Get the target topic from `PipelineStepConfig.outputs.get("outputName").kafkaTransport.topic` (or similar based on your config structure).
      * The `PipeStream` sent to Kafka should have its `target_step_name` updated to be the name of the step that will *consume* from this Kafka topic.
      * Invoke `KafkaForwarder.send(topic, pipeStream)`.
  3.  **Implement Kafka Listener in the Engine:**
    * Create a Kafka listener method within a Micronaut service in `yappy-engine`.
    * This listener will subscribe to topics that are inputs to gRPC or internal steps managed by *this* engine.
    * `@KafkaListener(groupId = "engine-group-${micronaut.application.name}-${pipeline.name}-${step.name}")` // Group ID needs careful consideration for scalability and step processing.
    * The listener method receives `PipeStream mesage`.
    * Upon receiving a `PipeStream` from Kafka:
      * It's like an internal entry point. Log its arrival.
      * Pass this `PipeStream` to the core routing/processing logic of `PipeStreamEngineImpl` (e.g., by calling its `processAsync(pipeStream)` method). The engine will then treat it like any other `PipeStream` it needs to process for its `target_step_name`.
  4.  **Configuration for Kafka Topics:**
    * Ensure `PipelineStepConfig` clearly defines input Kafka topics (if a step consumes from Kafka) and output Kafka topics (if a step publishes to Kafka).
    * The engine needs to know which topics *itself* should be listening to, based on the `PipelineStepConfig` instances it is responsible for. This might involve the engine inspecting all pipeline configurations at startup to determine its Kafka listener subscriptions.
  5.  **Integration Tests for Kafka:**
    * Engine processes a `PipeStream`, determines next step is Kafka.
    * `KafkaForwarder` publishes to a Testcontainer Kafka.
    * A Kafka listener (in the same or separate test context representing another engine part) consumes the message.
    * The consuming part correctly processes the `PipeStream` and continues the pipeline flow.

**Phase 4: Internal Step Execution & Sink Implementation**

* **Goal:** Handle pipeline steps that are executed directly within the engine (Java code) and implement the "Sink" step type.
* **Next Steps:**
  1.  **Internal Step Execution:**
    * Define an interface for internal pipeline step processors (e.g., `InternalPipeStepProcessor`).
    * These would be Spring/Micronaut beans discoverable by the engine.
    * `PipelineStepConfig` for an internal step would specify the bean name/ID.
    * The engine, when encountering an internal step, would look up the bean and invoke its processing method, passing necessary parts of the `PipeStream` (like `PipeDoc`, config).
    * The internal processor returns a result similar to `ProcessResponse` (e.g., updated `PipeDoc`, success status, logs).
  2.  **Sink Step Implementation:**
    * Define what a "Sink" means. Does it write to a database, an S3 bucket, an external API?
    * Create `SinkForwarder` or specific sink implementations (e.g., `S3Sink`, `DatabaseSink`).
    * `PipelineStepConfig` for a sink step would define the sink type and its specific configuration.
    * The engine routes the `PipeStream` to the appropriate sink implementation.
    * A sink typically marks the end of a branch of processing for that `PipeStream`. The `StepExecutionRecord` should be updated accordingly.

**Phase 5: Advanced Features & Robustness**

* **Goal:** Implement error handling, retries, monitoring, and more complex pipeline patterns.
* **Next Steps:**
  1.  **Error Handling Pipelines:** Design how `PipeStream.stream_error_data` can trigger routing to dedicated error-handling pipelines.
  2.  **Retries:** Implement retry mechanisms (e.g., in `GrpcForwarder` or `KafkaForwarder` for transient errors, or configurable retries at the step level).
  3.  **Dead Letter Queues (DLQs):** For persistent errors after retries, forward to a DLQ.
  4.  **Monitoring & Metrics:** Integrate Micronaut Metrics for call counts, latencies, error rates per step/pipeline.
  5.  **Conditional Routing:** If `PipelineStepConfig.outputs` needs to support conditions (e.g., "route to stepA if X in PipeDoc is true, else to stepB").
  6.  **Scalability & Concurrency:**
    * Ensure `PipeStreamEngineImpl` and its components are thread-safe.
    * Configure thread pools appropriately for async operations (gRPC callbacks, Kafka message processing).
    * Consider how many concurrent `PipeStream` instances the engine can handle.

**Execution Strategy:**

* **Iterative Development:** Build and test each small piece.
* **Test-Driven Development (TDD):** Write unit tests before or alongside your code. Write integration tests as components come together. Micronaut's testing framework and Testcontainers are excellent for this.
* **Clear Interfaces:** Define clear Java interfaces between components (e.g., between engine and forwarders, engine and internal processors).
* **Dependency Injection:** Leverage Micronaut for DI heavily.
* **Configuration First:** Always ensure your configuration loading and parsing are solid before building logic that depends on it.
* **Focus on `PipeStream`:** It's the central artifact. Its state (document, history, errors, target step) drives everything.

**Answering your direct question about `PipeStream` creation and hydration:**

Yes, the `PipeStream` should be created as soon as data is ingested via `IngestDataAsync`.
* `stream_id`, `current_pipeline_name`, `target_step_name` (initial), `document`, `context_params` are set.
* `current_hop_number` starts at 0 or 1.
* `history` is initially empty.

As it moves:
1.  **Before dispatch to a step (e.g., before calling `GrpcForwarder` or `KafkaForwarder`):**
  * Increment `current_hop_number`.
  * Create a new `StepExecutionRecord` with `hop_number`, `step_name` (the one about to be executed), `start_time`. Add it to `PipeStream.history`.
  * Update `PipeStream.target_step_name` to the step being called.
2.  **After a step responds (e.g., in `GrpcForwarder`'s `onNext` or `onError`, or after an internal step executes):**
  * Find the *last* `StepExecutionRecord` in `history`.
  * Update its `end_time`, `status` (`SUCCESS`/`FAILURE`), `processor_logs`, `error_info` (if any).
  * If the step failed critically, populate `PipeStream.stream_error_data`.
  * The engine then uses the (updated) `PipeStream` and the step's output configuration to determine the *next* `target_step_name` and continues the loop.

This is a big project, but your design is solid. Breaking it down into these phases and steps should make it manageable. We can dive into the specifics of any of these points or start coding/refining specific classes.

Given you want to start with routing and then `GrpcForwarder`, **Phase 1 (Core Engine Logic & Routing Strategy)** and **Phase 2 (gRPC Forwarding & `PipeStepProcessor` Interaction)** are your immediate focus. The `PipeStreamEngineImpl` is where the majority of this initial work will reside.

Let me know where you'd like to zoom in first! For example, we could start by outlining the methods and data structures within `PipeStreamEngineImpl` or by refining the `GrpcForwarder` to correctly handle the `ProcessResponse` and then call back into the engine to continue routing.