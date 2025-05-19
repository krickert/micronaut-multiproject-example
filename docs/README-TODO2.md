
Wea re building a flexible pipeline.

A cluster is the entire runing ecosystem. All applicaitons run on a cluster. Each network of nodes work through multiple pipelines.

Each node in the pipeline is an INSTANCE of a pipstep. There can be multiple instances of it within a pipeline. For example, a chunker can run 3x but just have different configurations.

That brings up two main points: how nodes forward from one node to the next and how nodes get configuration.

We'll start with configuration.

Custom config for PipeStep implementations

The configuration runs on a PipeStep service basis. The config is validated against a JSON schema validator.

This automatically loads from consul. That code is considered complete and is backed up by a schema registry - which will aid in "registering" a new service into a pipeline.

How nodes forward from one node to another:

Everything under the hood is a PipeStep implementation. There are three types of implementattions (Sink is not done yet in grpc). All three of these implmentations in the config exist as an extension of a regular pipestep except that a connector takes no inputs and a sink takes no outputs.

Each pipestep implementation is connected to a pipeline. These pipelines a meant to work with two processes on a container:
1) gRPC engine - handles all the routing between nodes. Will either forward to a kafka topic owned by another pipeline step, a sink step, or another grpc engine endpoint that represents a pipeline implementation.
2) kafka - inputs are the same inputs as a gRPC engine. Both take in a PipeStream and output the same way as I mentioned above for a grpc step. In other words, when a kafka request comes in, it is the same as a grpc step coming in. They both sare the same routing strategy



The entire pipeline is an acyclical graph.

Each "node" is a PipeStep implementation. There are multiple engines that run, each with their own pipe step processor. The pipe step processor is mentioned in more detail below.

The pipestep can define a schema. This is a global schema for that particular pipestep.

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
repeated float vector = 2;    // The vector representation.
}

// Represents the text content and vector embedding for a single chunk.
message ChunkEmbedding {
string text_content = 1;    // The actual text content of the chunk.
repeated float vector = 2;   // The vector embedding for this chunk's text.
optional string chunk_id = 3;  // Unique identifier for this chunk.
optional int32 original_char_start_offset = 4; // Optional: start offset in original document.
optional int32 original_char_end_offset = 5; // Optional: end offset in original document.
optional string chunk_group_id = 6; // Optional: Identifier for a group of related chunks.
optional string chunk_config_id = 7; // Optional: Identifier for the chunking configuration used.
}

// Represents a single semantic chunk of text with its embedding.
message SemanticChunk {
string chunk_id = 1;       // Unique identifier for this specific chunk within its parent SemanticProcessingResult.
int64 chunk_number = 2;     // Sequential number of the chunk within its parent SemanticProcessingResult.
ChunkEmbedding embedding_info = 3; // The text and embedding for this chunk.
map<string, google.protobuf.Value> metadata = 4; // Optional metadata specific to this chunk (e.g., original page number, section).
}

// Represents the complete result of one specific semantic chunking and/or embedding process
// applied to a field of a PipeDoc. A PipeDoc can store multiple such results.
message SemanticProcessingResult {
string result_id = 1;       // Unique ID for this specific result set (e.g., UUID for this instance of processing).
string source_field_name = 2;   // Name of the field within the parent PipeDoc that was processed (e.g., "body", "title").

string chunk_config_id = 3;    // Identifier for the chunking configuration used (e.g., "sentence_splitter_v1", "token_chunker_512_overlap_50").
string embedding_config_id = 4;  // Identifier for the embedding model/configuration used (e.g., "ada_002_v1", "minilm_l6_v2").

// A generated identifier/name for this set of (chunked and) embedded data.
// Useful as a prefix for field names in a search index or for display/selection.
// Example: "body_chunks_ada_002", "title_sentences_minilm"
// This would typically be generated by the pipeline step that produces this result.
optional string result_set_name = 5;

repeated SemanticChunk chunks = 6;  // List of semantic chunks with their embeddings produced by this specific configuration.
map<string, google.protobuf.Value> metadata = 7; // Metadata about this specific processing run (e.g., model version details, execution time).
}

message PipeDoc {
string id = 1;             // REQUIRED. Unique identifier for the document.
optional string source_uri = 2;    // Optional. URI where the original document came from (e.g., s3://, http://).
optional string source_mime_type = 3; // Optional. Original MIME type of the content that *led* to this PipeDoc (e.g., "application/pdf").

optional string title = 4;
optional string body = 5;       // Main textual content, often the target for chunking/embedding.
repeated string keywords = 6;
optional string document_type = 7;   // e.g., "article", "product", "email".
optional string revision_id = 8;    // Optional. Version identifier for the source content.

optional google.protobuf.Timestamp creation_date = 9; // When the source document was created or first seen.
optional google.protobuf.Timestamp last_modified_date = 10; // When the source document content was last modified.
optional google.protobuf.Timestamp processed_date = 11; // When this PipeDoc representation was last significantly processed/updated by the pipeline.

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
optional string blob_id = 1;      // Optional: Unique identifier for this blob (e.g., hash of content or UUID).
bytes data = 2;          // REQUIRED. The raw binary content.
optional string mime_type = 3;    // MIME type of the content in 'data' (e.g., "application/pdf", "image/jpeg").
optional string filename = 4;     // Optional: Original filename associated with this binary data.
optional string encoding = 5;     // Optional: Character encoding if 'data' represents text (e.g., "UTF-8").
map<string, string> metadata = 6;  // Optional: Additional key-value metadata specific to this blob.
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
string error_message = 1;       // REQUIRED. Human-readable description of the error.
optional string error_code = 2;    // Optional. Machine-readable error code (e.g., "CONFIG_VALIDATION_ERROR", "TIMEOUT_ERROR").
optional string technical_details = 3;  // Optional. Snippet of stack trace, detailed diagnostic information.
string originating_step_name = 4;  // REQUIRED. The 'stepName' of the PipelineStepConfig where the error originated or was detected.
optional string attempted_target_step_name = 5; // Optional. If error occurred during an attempt to route or dispatch to a *next* step.
optional FailedStepInputState input_state_at_failure = 6; // Optional. State of data/config when the step failed, for reproducibility.
google.protobuf.Timestamp timestamp = 7; // REQUIRED. When the error occurred or was logged.
}

message StepExecutionRecord {
int64 hop_number = 1;           // Sequential hop number for this step in the stream.
string step_name = 2;            // 'stepName' of the PipelineStepConfig that was executed.
optional string service_instance_id = 3;   // Optional. Identifier of the specific service instance/pod that executed the step.
google.protobuf.Timestamp start_time = 4;  // When step processing began.
google.protobuf.Timestamp end_time = 5;   // When step processing ended.
// Expected statuses: "SUCCESS", "FAILURE", "SKIPPED" (add more as needed, e.g., "RETRYING")
string status = 6;             // REQUIRED. Outcome of the step.
repeated string processor_logs = 7;     // Logs specifically from the processor for this step's execution.
optional ErrorData error_info = 8;      // Specific error from *this step* if status is "FAILURE".
// This is distinct from PipeStream.stream_error_data.
}

// --- Pipeline Execution State ---

message PipeStream {
string stream_id = 1;           // REQUIRED. Unique ID for this execution flow instance.
PipeDoc document = 2;           // REQUIRED (can be an empty message initially). The primary document being processed.
string current_pipeline_name = 3;     // REQUIRED. Name of the PipelineConfig being executed.

// REQUIRED by the sender (Engine or Kafka Framework).
// The 'stepName' (key from PipelineConfig.steps map) of the PipelineStepConfig
// that is the intended next recipient/processor of this PipeStream.
string target_step_name = 4;

int64 current_hop_number = 5;       // For logging/tracing; incremented by the engine/framework *before* dispatching to
// target_step_name.
repeated StepExecutionRecord history = 6;  // History of executed steps in this stream.
optional ErrorData stream_error_data = 7;  // Holds the first critical error that puts the *entire stream* into a general error
// state, possibly halting further processing unless handled by an error pipeline.
map<string, string> context_params = 8;  // Optional. Key-value parameters for the entire run's context (e.g., tenant_id, user_id,
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

Below is the changes we made so far, along with a plan in markdown:
```markdown
**Summary of Logic Changes and Decisions During Refactoring (Focus on Models and Validators):**

1.  **`PipelineStepConfig` Overhaul**:
    * **Identifier**: `stepName` replaced `pipelineStepId`.
    * **Module Linkage**: `ProcessorInfo` (containing `grpcServiceName` or `internalProcessorBeanName`) replaced `pipelineImplementationId`. This `implementationId` from `ProcessorInfo` is now the key to look up `PipelineModuleConfiguration` in `PipelineModuleMap.availableModules`.
    * **Kafka Inputs**: Introduced `List<KafkaInputDefinition> kafkaInputs` to explicitly define topics a step listens to, along with consumer group ID (optional, with engine deriving by convention) and consumer properties. This was crucial for loop validators.
    * **Outputs**: Standardized to `Map<String, OutputTarget> outputs`. `OutputTarget` now encapsulates `targetStepName`, `transportType`, and the specific nested transport config (`KafkaTransportConfig` or `GrpcTransportConfig`). This replaced `nextSteps`, `errorSteps`, and direct transport fields on `PipelineStepConfig`.
    * **`KafkaTransportConfig` (within `OutputTarget`)**: Simplified to hold the output `topic` and `kafkaProducerProperties`.
    * **`GrpcTransportConfig` (within `OutputTarget`)**: Now has `serviceName` (target gRPC service) and `grpcClientProperties`.
    * **Helper Constructors**: Added to `PipelineStepConfig` to simplify instantiation in tests by providing defaults for less critical fields in many scenarios.

2.  **`PipelineClusterConfig` Constructor**: Standardized to its 6-argument form, and its constructor now handles nulls for `allowedKafkaTopics` and `allowedGrpcServices` by defaulting them to empty sets.

3.  **`JsonConfigOptions`**: Clarified that `PipelineStepConfig.customConfig` uses the *inner record* `PipelineStepConfig.JsonConfigOptions`, where `jsonConfig` is a `JsonNode`. The standalone `JsonConfigOptions` (with `String jsonConfig`) was likely from an older iteration or a different context.

4.  **`StepType` Enum**: Corrected usage from a generic `SOURCE` to the defined `INITIAL_PIPELINE` in validators and tests.

5.  **`SchemaReference.toIdentifier()`**: Added this method for a consistent string representation (e.g., `subject:version`), used in logging and comparisons.

6.  **`PipelineModuleConfiguration`**: Confirmed its structure (`implementationName`, `implementationId`, `customConfigSchemaReference`). The `implementationId` is key for linking with `PipelineStepConfig.processorInfo`.

7.  **Kafka Topic Whitelisting Strategy (`WhitelistValidator`)**:
    * Refined to support both explicitly listed topics (`allowedKafkaTopics`) and topics matching a defined naming convention (e.g., `yappy.pipeline.<pipelineName>.step.<stepName>.output`).
    * The `isKafkaTopicPermitted` helper now checks convention first, then the explicit list. It also handles variable resolution (e.g., `${pipelineName}`, `${stepName}`) in topic strings.

8.  **Loop Validators (`IntraPipelineLoopValidator`, `InterPipelineLoopValidator`)**:
    * Now use `step.kafkaInputs()` to determine listen topics.
    * Use `step.outputs()` and `outputTarget.kafkaTransport().topic()` to determine published topics.
    * The `resolvePattern` helper is used to get concrete topic names before comparison.

9.  **`ReferentialIntegrityValidator`**:
    * Updated to check consistency of `stepName` with map keys.
    * Validates `processorInfo` linkage to `availableModules` via `implementationId`.
    * Checks `customConfigSchemaId` against `module.customConfigSchemaReference()`.
    * Validates properties within `KafkaInputDefinition.kafkaConsumerProperties` and `OutputTarget`'s `kafkaProducerProperties`/`grpcClientProperties`.
    * Validates `OutputTarget.targetStepName` against existing steps in the pipeline.

10. **`ConsulConfigFetcher` and `DynamicConfigurationManagerImpl` (Production Code Discrepancy)**:
    * Identified that `DynamicConfigurationManagerImpl.initialize()` expects a method like `WorkspaceFullClusterConfig` (to get config + all related schemas in one go) from `ConsulConfigFetcher`.
    * The provided `ConsulConfigFetcher` interface has more granular `WorkspacePipelineClusterConfig` and `WorkspaceSchemaVersionData` methods.
    * **Decision/Recommendation**: The `KiwiprojectConsulConfigFetcher`'s watch logic already bundles config and schemas. This bundling logic should be extracted into a helper. A new method, e.g., `WorkspaceInitialConfigAndSchemas(String clusterName): Optional<WatchCallbackResult>`, should be added to the `ConsulConfigFetcher` interface. `KiwiprojectConsulConfigFetcher` would implement this by calling its existing `WorkspacePipelineClusterConfig` and then using the (newly extracted) common helper to gather schemas. `DynamicConfigurationManagerImpl.initialize()` would then call this new unified fetch method.

---

**Reworked Initial Plan (Focusing on "Step 0" Completion and "Step 1" Kick-off)**

The original plan's "Phase 0" was about "Foundations & Configuration." We've made massive progress here. "Step 0" in your `step0.md` focused on model refactoring, which we've largely addressed for the `yappy-consul-config` module's direct needs.

**Phase 0: Foundations & Configuration (Finalizing `yappy-consul-config`)**

* **Goal:** The `yappy-consul-config` module is fully compiled, all its unit and integration tests pass, and the production code for configuration fetching and validation is internally consistent and robust.
* **Status:**
    * Models: Refactored and stabilized for `yappy-consul-config`'s needs.
    * Validators (Core Logic): Refactored.
    * Validator Unit Tests: Now *compiling*.
    * Other Unit Tests (`DefaultConfigurationValidatorTest`, `InMemoryCachedConfigHolderTest`): Now *compiling*.
    * `DynamicConfigurationManagerImplTest`: Now *compiling* after fixing its constructor call.
* **Remaining "Step 0" Tasks for `yappy-consul-config`:**

    1.  **Production Code - `ConsulConfigFetcher` Reconciliation (CRITICAL):**
        * **Action**: Implement the recommended change:
            * Add `Optional<WatchCallbackResult> fetchInitialConfigAndSchemas(String clusterName)` to `ConsulConfigFetcher.java`.
            * In `KiwiprojectConsulConfigFetcher.java`:
                * Extract the logic from the existing `watchClusterConfig`'s listener that takes a `PipelineClusterConfig` and fetches all its associated schemas (iterating through modules and potentially step-level schema IDs) into a private helper method that returns `Map<SchemaReference, String>`.
                * Implement `WorkspaceInitialConfigAndSchemas` by calling `this.fetchPipelineClusterConfig(clusterName)`, and if successful, using the new private helper to get the schemas, then bundling both into a `WatchCallbackResult`.
            * Modify `DynamicConfigurationManagerImpl.initialize()` to call this new `consulConfigFetcher.fetchInitialConfigAndSchemas(this.clusterName)` method.
        * **Impact**: This makes the initial load consistent with the watch update mechanism and resolves the method mismatch.

    2.  **Fix Remaining Compilation Errors in `yappy-consul-config` Tests (Post-Fetcher Change):**
        * **`DynamicConfigurationManagerImplTest.java`**: Update mocking for `ConsulConfigFetcher` to use `WorkspaceInitialConfigAndSchemas` in tests for the `initialize()` path.
        * **`KiwiprojectConsulConfigFetcherTest.java`**: This test will need significant updates. It must test the new `WorkspaceInitialConfigAndSchemas` method thoroughly, in addition to its existing `WorkspacePipelineClusterConfig` and `WorkspaceSchemaVersionData` tests. Test data creation for `PipelineClusterConfig` will need to use the new models.
        * **Micronaut Integration Tests**:
            * `DefaultConfigurationValidatorMicronautTest.java`
            * `DynamicConfigurationManagerImplMicronautTest.java`
            * `KiwiprojectConsulConfigFetcherMicronautTest.java`
            * `DynamicConfigurationManagerFullIntegrationTest.java`
            * **Action**: For each, update any instantiation of `PipelineClusterConfig` or `PipelineStepConfig` to use the new constructors/structures. Pay close attention to how test data might be loaded or defined (e.g., from test YAML/JSON files or constructed in code).

    3.  **Run All Tests in `yappy-consul-config` and Fix Test Logic Failures:**
        * Once all files compile, systematically run all tests in the module.
        * Debug logical failures. These will likely stem from:
            * Assertions still expecting data in the old format.
            * Test data not being correctly set up to trigger or pass the refactored validator logic (e.g., not providing `kafkaInputs` where a loop validator now expects them).
            * Subtle changes in validator behavior due to the model overhaul.
        * **Goal**: Get a green build for `yappy-consul-config`.

    4.  **Verify Protobuf Generation (Re-check from original plan):**
        * A quick check to ensure `yappy-models/protobuf-models/build.gradle.kts` is correctly generating Java classes from all your `.proto` files. This seems to have been stable, but a final check is good.

---
**Revised "TODO part 2" (Post-Phase 0)**

Once `yappy-consul-config` is stable and its tests are green, we can confidently move to the engine logic. Your original phases are still highly relevant:

**Phase 1: Core Engine Logic & Routing Strategy (The "Brain")**
* **Goal**: Define and implement the central routing logic within `PipeStreamEngineImpl`.
* **Key Change**: The engine will now consume the *newly refactored and validated* configuration models (e.g., `PipelineStepConfig` with its `stepName`, `processorInfo`, `kafkaInputs`, and `outputs` map) obtained via `DynamicConfigurationManager`.
* **Next Steps (from your original plan, adapted):**
    1.  **`PipeStream` Lifecycle Management**:
        * `IngestDataAsync()`: Create `PipeStream`, determine first `target_step_name` using the new `PipelineConfig` structure.
        * Hydration: `StepExecutionRecord` updates remain the same.
    2.  **Implement Routing Logic in `PipeStreamEngineImpl.processAsync(PipeStream)`**:
        * **Load Configs**: Use `dynamicConfigurationManager.getCurrentPipelineClusterConfig()` and then find the specific `PipelineConfig` and `PipelineStepConfig` (using `pipeline.name()` and `step.stepName()`).
        * **Execute Current Step**:
            * Identify how to execute based on `step.processorInfo()` (gRPC service name or internal bean name). This replaces relying on a top-level `StepType` for dispatch, making `ProcessorInfo` central to execution.
        * **Determine Next Hop(s)**: Use `step.outputs()` map. For each chosen `OutputTarget`:
            * Get `next_target_step_name = outputTarget.targetStepName()`.
            * Get `transportType = outputTarget.transportType()`.
            * If Kafka, get `topic = outputTarget.kafkaTransport().topic()`.
            * If gRPC, get `serviceName = outputTarget.grpcTransport().serviceName()`.
        * **Forwarding**:
            * If GRPC: Use `GrpcForwarder` with the `serviceName` from `OutputTarget`.
            * If KAFKA: Use `KafkaForwarder` with the `topic` from `OutputTarget`.
            * If INTERNAL: Use `internalProcessorBeanName` from `step.processorInfo()` to invoke the local bean.
    3.  **`PipeStreamEngine.IngestDataAsync()` Implementation**: As planned.
    4.  **Unit Tests for Routing Logic**: Using the new config models.

**Phase 2: gRPC Forwarding & `PipeStepProcessor` Interaction**
* **Goal**: Engine forwards to external gRPC `PipeStepProcessors`.
* **Key Change**: `ProcessRequest.config.custom_json_config` will be populated from `step.customConfig().jsonConfig()` (which is a `JsonNode`, so it needs serialization to string if the proto field is string, or direct mapping if proto also supports a generic JSON type like `google.protobuf.Value` or `Struct`). The proto definition for `ProcessConfiguration.custom_json_config` is `string`, so `JsonNode.toString()` will be needed. `config_params` comes from `step.customConfig().configParams()`.
* **Next Steps**: Largely as planned, adapting to new config access.

**Phase 3: Kafka Forwarding & Consumption**
* **Goal**: Engine publishes to Kafka; engine instances consume from Kafka.
* **Key Change**:
    * **Publishing**: Target topic comes from `outputTarget.kafkaTransport().topic()`.
    * **Consuming**: The engine, at startup or based on config updates, will inspect `PipelineStepConfig.kafkaInputs` for all managed steps. For each `KafkaInputDefinition`, it will dynamically create/manage a Kafka listener for the specified `listenTopics` using the `consumerGroupId` (defaulting if not provided) and `kafkaConsumerProperties`.
* **Next Steps**: Largely as planned.

**Phase 4 & 5 (Internal Steps, Sinks, Advanced Features)**: These phases build on the above. The core data flow using the new config models will be the foundation.

This revised "Step 0" completion plan, focusing on fixing the `ConsulConfigFetcher` interaction and then systematically getting all tests in `yappy-consul-config` to compile and pass, sets a solid foundation. The subsequent phases then leverage these stable, refactored configuration models.

How does this detailed summary and revised plan look to you? Specifically, what are your thoughts on the proposed reconciliation for `ConsulConfigFetcher` and `DynamicConfigurationManagerImpl.initialize()`? This seems like the most critical *production code* fix needed before we can robustly test `DynamicConfigurationManagerImplTest` and `KiwiprojectConsulConfigFetcherTest`.
```