# YAPPY gRPC API Manual

*(Last Updated: 2025-05-12)*

This document provides a reference for the gRPC services and messages used within the YAPPY platform. It is intended for developers creating new pipeline step processors (modules) and for clients interacting with the pipeline engine.

All Protobuf messages are defined within the `com.krickert.search.model` or `com.krickert.search.engine` packages as specified. For detailed field descriptions of common types like `PipeStream`, `PipeDoc`, etc., please refer to the `yappy_core_types.proto` definitions and the `architecture_overview.md`.

## Table of Contents

1.  [PipeStepProcessor Service](#1-pipestepprocessor-service)
    * 1.1. Overview
    * 1.2. RPC Methods
        * 1.2.1. `ProcessDocument`
    * 1.3. Request Messages
        * 1.3.1. `ProcessPipeDocRequest`
    * 1.4. Response Messages
        * 1.4.1. `ProcessResponse`
2.  [PipeStreamEngine Service](#2-pipestreamengine-service)
    * 2.1. Overview
    * 2.2. RPC Methods
        * 2.2.1. `process`
        * 2.2.2. `processAsync`
    * 2.3. Common Input/Output Messages
        * 2.3.1. `PipeStream` (as input and output)
        * 2.3.2. `google.protobuf.Empty` (as output)
3.  [Core Data Types (Reference)](#3-core-data-types-reference)
    * (Links or brief descriptions of `PipeStream`, `PipeDoc`, `Blob`, `ErrorData`, `StepExecutionRecord`, etc.)

---

## 1. PipeStepProcessor Service

**Package:** `com.krickert.search.model` (or `com.krickert.search.sdk` for generated client/server code if preferred)
**Proto File:** `pipe_step_processor_service.proto`

### 1.1. Overview

The `PipeStepProcessor` service defines the standard interface that all gRPC-based custom pipeline step processors (modules) must implement. The `grpc-pipeline-engine` calls this service to delegate the processing of a `PipeStream` to a specific module based on the pipeline configuration.

### 1.2. RPC Methods

#### 1.2.1. `ProcessDocument`

Processes a document (contained within a `PipeStream`) according to the step's specific configuration and logic. This is the primary method for custom data transformation and enrichment.

* **Request:** `ProcessPipeDocRequest`
* **Response:** `ProcessResponse`

**Flow:**
1.  The `grpc-pipeline-engine` constructs a `ProcessPipeDocRequest`. This request includes:
    * The specific configuration for the step instance being invoked (`custom_json_config`, `config_params`).
    * Contextual identifiers (`pipeline_name`, `pipe_step_name`).
    * The current `PipeStream` data payload (`pipe_stream_data`), with its `current_pipeline_name` and `target_step_name` fields set by the engine to match the context of this call.
2.  The engine sends this request to the appropriate gRPC module.
3.  The gRPC module performs its processing logic.
4.  The gRPC module returns a `ProcessResponse` indicating success/failure and any output data or errors specific to its processing.
5.  The engine uses this response to update its master `PipeStream` and determine the next step in the pipeline.

### 1.3. Request Messages

#### 1.3.1. `ProcessPipeDocRequest`

```protobuf
message ProcessPipeDocRequest {
  // Context: Identifies the pipeline this step execution is part of.
  string pipeline_name = 1;        
  
  // Context: Identifies the specific configured instance of the step being invoked.
  // This is the 'stepName' from the PipelineStepConfig.
  string pipe_step_name = 2;        
  
  // Configuration: The specific, validated custom JSON configuration for THIS pipe_step_name.
  // The engine converts the jsonConfig string from PipelineStepConfig into this Struct.
  google.protobuf.Struct custom_json_config = 3; 
  
  // Configuration: The 'configParams' map from PipelineStepConfig for THIS pipe_step_name.
  map<string, string> config_params = 4;        
  
  // Data: The current state of the pipeline's data to be processed.
  // The engine ensures:
  // - pipe_stream_data.current_pipeline_name == this.pipeline_name 
  // - pipe_stream_data.target_step_name == this.pipe_step_name
  // when sending this request, providing full context to the processor.
  PipeStream pipe_stream_data = 5;         
}
```

* **`pipeline_name` (string):** The `pipelineName` from `PipelineConfig` providing the overarching pipeline context for this call. (Populated by Engine)
* **`pipe_step_name` (string):** The `stepName` from `PipelineStepConfig` that this gRPC service instance is currently executing as. This identifies the specific configured instance of the processing logic. (Populated by Engine)
* **`custom_json_config` (google.protobuf.Struct):** The specific, validated custom JSON configuration for this `pipe_step_name`. The engine converts the `jsonConfig` string (from `PipelineStepConfig.customConfig.jsonConfig`) into this `Struct` after validating it against the schema defined in `PipelineModuleConfiguration.schemaReference`. Developers use the `grpc-developer-sdk` helpers to parse this `Struct` into native objects. (Populated by Engine)
* **`config_params` (map<string, string>):** The `configParams` map directly from `PipelineStepConfig` for this `pipe_step_name`. (Populated by Engine)
* **`pipe_stream_data` (PipeStream):** The actual data payload to be processed. The `PipeStream` message (defined in `yappy_core_types.proto`) contains the `document`, `blob`, `history`, etc. The engine ensures that `pipe_stream_data.current_pipeline_name` matches `this.pipeline_name`, and `pipe_stream_data.target_step_name` matches `this.pipe_step_name` for contextual alignment when this request is dispatched. (Populated by Engine)

### 1.4. Response Messages

#### 1.4.1. `ProcessResponse`

```protobuf
message ProcessResponse {
  // Outcome: True if this step's processing was successful, false otherwise.
  bool success = 1;                     
  
  // Output Data: The modified or newly created PipeDoc.
  // If not provided, the engine assumes the PipeDoc within the original pipe_stream_data
  // either remains unchanged or that this step does not modify the PipeDoc directly.
  optional PipeDoc output_doc = 2;              
  
  // Output Data: The modified or newly created Blob.
  // If not provided, similar assumptions as for output_doc apply to the blob.
  optional Blob output_blob = 3;               
  
  // Error Details: Structured error information from *this processor* if success is false.
  // This is for errors specific to the processor's execution logic.
  // The engine will use this to populate a StepExecutionRecord.
  optional google.protobuf.Struct error_details = 4; 
  
  // Logging: Logs or summary information generated by this processor step.
  // The engine will typically add these to the StepExecutionRecord for this step.
  repeated string processor_logs = 5;  
}
```

* **`success` (bool):** REQUIRED. Must be set to `true` if the step's processing was successful, `false` otherwise. The engine uses this to determine routing to `nextSteps` or `errorSteps`.
* **`output_doc` (optional PipeDoc):** If the processor modifies the `PipeDoc` or creates a new one, it should be returned here. If omitted, the engine assumes the `PipeDoc` in the input `PipeStream` is unchanged by this step.
* **`output_blob` (optional Blob):** If the processor modifies the `Blob` or creates a new one. If omitted, the input `Blob` is assumed unchanged.
* **`error_details` (optional google.protobuf.Struct):** If `success` is `false`, this field can provide structured details about the error that occurred within the processor. This allows for richer, machine-parseable error information beyond a simple message.
* **`processor_logs` (repeated string):** Any specific logs or summary messages generated by the processor during its execution. The engine will typically capture these and add them to the `StepExecutionRecord` for this step in the `PipeStream`'s history.

---

## 2. PipeStreamEngine Service

**Package:** `com.krickert.search.engine`
**Proto File:** `engine_service.proto`

### 2.1. Overview

The `PipeStreamEngine` service is the central entry point for initiating and orchestrating pipeline executions. Clients (e.g., an API gateway, a batch job scheduler, or test harnesses) call this service to start processing data through a defined pipeline.

### 2.2. RPC Methods

#### 2.2.1. `process`

Initiates a pipeline run and waits for its complete execution (synchronous pattern). The final state of the `PipeStream` (after all steps have run or a terminal error occurs) is returned.

* **Request:** `com.krickert.search.model.PipeStream`
* **Response:** `com.krickert.search.model.PipeStream`

**Client Responsibilities for Input `PipeStream`:**
* **`stream_id`:** Optional. If provided, it will be used. If empty, the engine may generate one.
* **`document` / `blob`:** Should contain the initial data payload to be processed.
* **`current_pipeline_name`:** REQUIRED. Specifies the `pipelineName` of the `PipelineConfig` definition to execute.
* **`target_step_name`:** REQUIRED. Specifies the `stepName` of the *first* step within the `current_pipeline_name` to execute.
* **`context_params`:** Optional map for providing global context for the entire pipeline run.
* `history` and `stream_error_data` should typically be empty/null on this initial call.

#### 2.2.2. `processAsync`

Initiates a pipeline run in a fire-and-forget manner (asynchronous pattern). The call returns immediately after the engine has successfully accepted the request for processing.

* **Request:** `com.krickert.search.model.PipeStream` (same input requirements as for the `process` RPC)
* **Response:** `google.protobuf.Empty`

**Notes:**
* A `google.protobuf.Empty` response indicates successful *initiation*.
* Errors encountered *during* the asynchronous pipeline execution will be handled according to the pipeline's error routing configuration and will not be directly returned by this RPC.
* Errors during the *initiation phase itself* (e.g., `current_pipeline_name` not found in configuration, invalid initial `PipeStream`) may still be returned via gRPC status codes.

### 2.3. Common Input/Output Messages

#### 2.3.1. `PipeStream`
* **Definition:** See `yappy_core_types.proto` and Section 4.1.1 of `architecture_overview.md`.
* **Role:** As input, it provides the initial data and targeting for the pipeline run. As output (for the `process` RPC), it represents the final state of the data and execution history after the pipeline has completed or terminally failed.

#### 2.3.2. `google.protobuf.Empty`
* Standard Protobuf message indicating an empty response, used by `processAsync` for acknowledgement.

---

## 3. Core Data Types (Reference)

The following core data types are used extensively by the services above. Their detailed definitions can be found in `yappy_core_types.proto` (package `com.krickert.search.model`) and are described in the `architecture_overview.md`.

* **`PipeStream`**: The central data carrier. Includes `document`, `blob`, `current_pipeline_name`, `target_step_name`, `history`, `stream_error_data`, `context_params`.
* **`PipeDoc`**: Represents the structured document being processed. Includes `id`, `source_uri`, `source_mime_type`, `title`, `body`, `keywords`, `document_type`, `custom_data`, `semantic_results` (list of `SemanticProcessingResult`), `named_embeddings`.
    * **`SemanticProcessingResult`**: Holds the output of a specific chunking/embedding configuration run, including `result_set_name`, `chunk_config_id`, `embedding_config_id`, and a list of `SemanticChunk`s.
    * **`SemanticChunk`**: Contains `chunk_id`, `chunk_number`, and `embedding_info` (`ChunkEmbedding`).
    * **`ChunkEmbedding`**: Contains `text_content` and `vector`.
    * **`Embedding`**: Contains a `vector` and optional `model_id`.
* **`Blob`**: Represents binary data, with `blob_id`, `data`, `mime_type`, `filename`.
* **`ErrorData`**: Structured error information, including `error_message`, `error_code`, `originating_step_name`, `input_state_at_failure`.
* **`StepExecutionRecord`**: Records the details of a single step's execution within the `PipeStream.history`.
* **`google.protobuf.Struct`**: Used for flexible, JSON-like structured data in `PipeDoc.custom_data`, `ProcessPipeDocRequest.custom_json_config`, and `ProcessResponse.error_details`.
* **`google.protobuf.Timestamp`**: Used for various date/time fields.

This document should serve as the primary reference for understanding and interacting with YAPPY's gRPC services.


**Next Steps Before Updating Tests:**

1.  **Review this `gRPC_API_Manual.md`:** Ensure it accurately reflects the Protobuf definitions and the intended interactions.
2.  **Place it in your repository:** Likely in `yappy-models/protobuf-models/docs/gRPC_API_Manual.md`.
3.  **Ensure your actual `.proto` files in `yappy-models/protobuf-models/src/main/proto/` are updated** to match the definitions we discussed (as outlined in my previous message showing the split proto files).
4.  **Regenerate Protobuf Java classes:** Run the appropriate Gradle task (e.g., `./gradlew :yappy-models:protobuf-models:generateProto` or a clean build of that submodule).

Once these steps are done, your codebase's understanding of the gRPC contracts will be aligned with the documentation, and you'll be ready to tackle the test updates.