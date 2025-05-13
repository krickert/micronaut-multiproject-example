# **YAPPY gRPC API Manual**

*(Last Updated: 2025-05-13)*

This document provides a reference for the gRPC services and messages used within the YAPPY platform. It is intended for developers creating new pipeline step processors (modules) and for clients (connectors or other services) interacting with the pipeline engine.

All Protobuf messages are defined within the com.krickert.search.model (for core types) or com.krickert.search.engine (for engine service) and com.krickert.search.sdk (for processor service, if generated there) packages as specified. For detailed field descriptions of common types like PipeStream, PipeDoc, etc., please refer to the yappy\_core\_types.proto definitions and the architecture\_overview.md (which should also be updated to reflect these changes).

## **Table of Contents**

1. [PipeStepProcessor Service](#PipeStepProcessor-Service)
    * 1.1. Overview
    * 1.2. RPC Methods
        * 1.2.1. `ProcessData`
    * 1.3. Request Messages
        * 1.3.1. `ProcessRequest`
        * 1.3.2. `ServiceMetadata`
        * 1.3.3. `ProcessConfiguration`
    * 1.4. Response Messages
        * 1.4.1. `ProcessResponse`
2. [PipeStreamEngine Service](#PipeStreamEngine-Service)
    * 2.1. Overview
    * 2.2. RPC Methods
        * 2.2.1. `IngestDataAsync` (New Recommended Ingestion RPC)
        * 2.2.2. `process` (Existing)
        * 2.2.3. `processAsync` (Existing)
    * 2.3. Request Messages
        * 2.3.1. `IngestDataRequest`
    * 2.4. Response Messages
        * 2.4.1. `IngestDataResponse`
    * 2.5. Common Input/Output Messages (for existing RPCs)
        * 2.5.1. `PipeStream`
        * 2.5.2. `google.protobuf.Empty`
3. [Core Data Types (Reference)](#Core-Data-Types)

## **1\. PipeStepProcessor Service**

Package (Example for generated SDK): `com.krickert.search.sdk`  
Proto File: `pipe_step_processor_service.proto`

### **1.1. Overview**

The `PipeStepProcessor` service defines the standard interface that all gRPC-based custom pipeline step processors (modules) must implement. The grpc-pipeline-engine calls this service to delegate the processing of data to a specific module based on the pipeline configuration.

### **1.2. RPC Methods**

#### **1.2.1. ProcessData**

Processes document data according to the step's specific configuration and logic. This is the primary method for custom data transformation, enrichment, or analysis within the YAPPY framework.

* **Request:** `ProcessRequest`
* **Response:** `ProcessResponse`

**Flow:**

1. The `grpc-pipeline-engine` constructs a `ProcessRequest`. This request includes:
    * The primary data to be processed (`PipeDoc` document, which now contains the Blob).
    * The specific configuration for this step instance (`ProcessConfiguration` config).
    * Engine-provided contextual metadata (`ServiceMetadata` metadata).
2. The engine sends this request to the appropriate gRPC module.
3. The gRPC module performs its processing logic using the provided document, configuration, and optionally, the metadata.
4. The gRPC module returns a ProcessResponse indicating success/failure and any output data or errors specific to its processing.
5. The engine uses this response to update its master `PipeStream` and determine the next step in the pipeline.

### **1.3. Request Messages**

#### **1.3.1. ProcessRequest**
```protobuf
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
```

* **`document` (`com.krickert.search.model.PipeDoc`):** **REQUIRED**. The primary document being processed. As per the updated `yappy_core_types.proto`, this `PipeDoc` message now includes an optional Blob blob field.
* **`config` (`ProcessConfiguration`):** **REQUIRED**. Contains the specific configuration for this instance of the pipeline step.
* **`metadata` (`ServiceMetadata`):** **REQUIRED**. Contains engine-provided contextual information about the pipeline execution.

#### **1.3.2. ServiceMetadata**
```protobuf
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
```

* **`pipeline_name` (string):** The name of the PipelineConfig being executed. (Populated by Engine)
* **`pipe_step_name` (string):** The stepName of the PipelineStepConfig this module is currently executing as. (Populated by Engine)
* **`stream_id` (string):** The unique identifier for the overall PipeStream execution flow. (Populated by Engine)
* **`current_hop_number` (int64):** The sequence number of this step in the current PipeStream execution. (Populated by Engine)
* **`history` (repeated com.krickert.search.model.StepExecutionRecord):** A list of records for steps already executed on this PipeStream. (Populated by Engine)
* **`stream_error_data` (optional com.krickert.search.model.ErrorData):** If the PipeStream was previously marked with a critical error. (Populated by Engine)
* **`context_params` (map<string, string>):** Global key-value parameters for this PipeStream's execution context. (Populated by Engine)

#### **1.3.3. ProcessConfiguration**
```protobuf
// Contains configuration specific to this instance of the pipeline step.
message ProcessConfiguration {
  // The specific, validated custom JSON configuration for this step,
  // converted by the engine from PipelineStepConfig.customConfig.jsonConfig.
  google.protobuf.Struct custom_json_config = 1;

  // The 'configParams' map from PipelineStepConfig for this step.
  map<string, string> config_params = 2;
}
```

* **`custom_json_config` (`google.protobuf.Struct`):** The specific, validated custom JSON configuration for this `pipe_step_name`. The engine converts the `jsonConfig` `string` (from `PipelineStepConfig.customConfig.jsonConfig`) into this `Struct` after validating it against the schema defined in `PipelineModuleConfiguration.schemaReference`. (Populated by `Engine`)
* **`config_params` (`map<string, string>`):** The configParams map directly from PipelineStepConfig for this `pipe_step_name`. (Populated by `Engine`)

### **1.4. Response Messages**

#### **1.4.1. ProcessResponse**
```protobuf
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
```

* **`success` (`bool`):** REQUIRED. Must be set to true if the step's processing was successful, false otherwise.
* **`output_doc` (`optional com.krickert.search.model.PipeDoc`):** If the processor modifies the `PipeDoc` or creates a new one, it should be returned here. The `Blob` is now contained within this `PipeDoc`.
* **`error_details` (`optional google.protobuf.Struct`):** If `success` is `false`, this field can provide structured details about the error that occurred within the processor.
* **`processor_logs` (`repeated string`):** Any specific logs or summary messages generated by the processor.

## **2. PipeStreamEngine Service**

Package: `com.krickert.search.engine`  
Proto File: `engine_service.proto`

### **2.1. Overview**

The `PipeStreamEngine` service is the central entry point for initiating and orchestrating pipeline executions. Connectors and other client applications call this service to start processing data through a defined pipeline.

### **2.2. RPC Methods**

#### **2.2.1. `IngestDataAsync` (New Recommended Ingestion RPC)**

Ingests new data (as a `PipeDoc` identified by a `source_identifier`) into the system to start a pipeline asynchronously. The engine will use the source\_identifier to look up the configured target pipeline and initial step from its administrative configuration (e.g., `Consul`), create the `PipeStream`, generate a `stream_id`, and initiate the pipeline.

* **Request:** `IngestDataRequest`
* **Response:** `IngestDataResponse`

**Flow:**

1. A Connector service prepares an `IngestDataRequest` containing the `source_identifier` and the `PipeDoc` (which includes the `Blob`).
2. The Connector calls `PipeStreamEngine.IngestDataAsync()`.
3. The Engine:
    * Validates the `source_identifier`.
    * Looks up the associated `targetPipelineName` and `targetInitialStepName` from its configuration.
    * Generates a unique `stream_id`.
    * Creates a new `PipeStream` object, populating it with the input `PipeDoc`, `initial_context_params` (if any), and the determined pipeline/step targets.
    * Queues or begins the pipeline execution.
    * Returns an `IngestDataResponse` to the Connector with the `stream_id` and acceptance status.
4. The pipeline then proceeds as orchestrated by the engine, calling `PipeStepProcessor` services.

#### **2.2.2. `process` (Existing)**

Initiates a pipeline run with a pre-formed `PipeStream` and waits for its complete execution (synchronous pattern). The final state of the `PipeStream` is returned. *This method may be used for specific advanced use cases or internal processes where a `PipeStream` is already fully constructed.*

* **Request:** `com.krickert.search.model.PipeStream`
* **Response:** `com.krickert.search.model.PipeStream`

#### **2.2.3. `processAsync` (Existing)**

Initiates a pipeline run with a pre-formed `PipeStream` in a fire-and-forget manner (asynchronous pattern). Returns `google.protobuf.Empty` on successful *initiation*. *This method may be used for specific advanced use cases or internal processes.*

* **Request:** `com.krickert.search.model.PipeStream`
* **Response:** `google.protobuf.Empty`

### **2.3. Request Messages (for New Ingestion RPC)**

#### **2.3.1. `IngestDataRequest`**

```protobuf
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
```

* **`source_identifier` (`string`):** **REQUIRED**. Identifies the calling connector/source. Used by the engine to look up pipeline routing configuration.
* **`document` (`com.krickert.search.model.PipeDoc`):** **REQUIRED**. The data payload.
* **`initial_context_params` (`map<string, string>`):** Optional. Global context for the pipeline run.
* **`suggested_stream_id` (`optional string`):** Optional. Connector can suggest an ID.

### **2.4. Response Messages (for New Ingestion RPC)**

#### **2.4.1. `IngestDataResponse`**
```protobuf
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
```

* **`stream_id` (`string`):** The unique ID assigned by the engine for this pipeline run.
* **`accepted` (`bool`):** `True` if the engine accepted the request for processing.
* **`message` (`string)`:** Optional informational message.

### **2.5. Common Input/Output Messages (for existing process / processAsync RPCs)**

#### **2.5.1. `PipeStream`**

* **Definition:** See `yappy_core_types.proto`.
* **Role:** Used by the older process and processAsync RPCs. Requires the client to pre-construct the entire `PipeStream`.

#### **2.5.2. `google.protobuf.Empty`**

* Standard Protobuf message indicating an empty response, used by the older processAsync RPC.

## **3. Core Data Types (Reference)**

The following core data types are used extensively. Their detailed definitions are in `yappy_core_types.proto` (package `com.krickert.search.model`).

* **`PipeDoc`**: Represents the structured document. Now includes an optional Blob blob field. Other fields include `id`, `title`, `body`, `semantic_results`, `named_embeddings`, `custom_data`, etc.
* **`Blob`**: (Now part of `PipeDoc`) Represents binary `data`, with `blob_id`, `data`, `mime_type`, `filename`.
* **`PipeStream`**: The central data carrier for pipeline state when using the process or processAsync RPCs. Contains `stream_id`, `document` (which includes blob), `current_pipeline_name`, `target_pipeline_name`, `step_name`, `history`, `stream_error_data`, `context_params`.
* **`ServiceMetadata`**: (New, part of `ProcessRequest`) Contains engine-provided context for processing modules.
* **`ProcessConfiguration`**: (New, part of `ProcessRequest`) Contains step-specific configuration for processing modules.
* **`ErrorData`**: Structured error information.
* **`StepExecutionRecord`**: Records details of a single step's execution.
* **`google.protobuf.Struct`**: Used for flexible JSON-like structured data.
* **`google.protobuf.Timestamp`**: Used for date/time fields.

This document should serve as the primary reference for understanding and interacting with YAPPY's gRPC services. Ensure `yappy_core_types.proto`, `pipe_step_processor_service.proto`, and `engine_service.proto` are updated in your codebase and stubs are regenerated.