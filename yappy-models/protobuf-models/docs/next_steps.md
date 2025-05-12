# Pipeline System - Next Implementation Steps

This document outlines the suggested priorities for implementing the core components of the PipeStreamEngine and related services, following the definition of the Protobuf interfaces. The goal is to build the system incrementally, ensuring foundational pieces are in place before adding more complex logic.

## Implementation Priorities

Here are the recommended next 5 steps, ordered to build the system logically:

3.  **`PipeStreamEngine` Core Orchestration Logic (`process` method):**
    * **Goal:** Implement the main loop within the engine that drives the execution of a pipeline based on the loaded configuration.
    * **Why Third:** With configuration loading and updating handled, you can now build the core state machine that executes a pipeline.
    * **Tasks:**
        * Implement the `PipeStreamEngineImpl.process` gRPC method.
        * Handle the `EngineProcessRequest`: Initialize a `PipeStream` object (new or from request), generate `stream_id` if needed, set `pipeline_name`, merge `initial_context_params`, prepare initial `PipeDoc` and `input_blob`.
        * Use the `ConsulConfigurationService` (Step 1) to load the `PipelineConfig` DTO for the requested `pipeline_name`.
        * Implement the main execution loop based on `PipeStream.current_hop_number` and the steps defined in the `PipelineConfig` DTO.
        * Inside the loop:
            * Determine the current step's configuration.
            * Prepare the data needed for the `PipelineStepExecutor` (Step 4) - primarily the `PipeDoc`, `input_blob`, step config params, and context.
            * **(Stub/Mock Step 4 initially):** Call a placeholder for the actual step execution.
            * Update the `PipeStream` state: Increment `current_hop_number`, add a `HistoryEntry` (with placeholder data initially), update `current_doc` and `input_blob` based on the (mocked) step response.
            * Handle loop termination (end of steps, failure, document dropped).
        * Prepare and return the final `EngineProcessResponse`.
        * Implement basic error handling (config not found, invalid hop number).

4.  **`PipelineStepExecutor` Implementation (gRPC Client & Service Discovery):**
    * **Goal:** Enable the engine to dynamically find and call the correct `PipeStepProcessor` gRPC service for each step in the pipeline.
    * **Why Fourth:** Replaces the mock/stub from Step 3 with actual remote calls, making the orchestration real.
    * **Tasks:**
        * Implement a service/bean within the engine (e.g., `GrpcPipelineStepExecutor`).
        * Inject Micronaut's `DiscoveryClient` (configured for Consul).
        * The executor method will take the target service name (from the step config DTO) and the `ProcessPipeDocRequest` as input.
        * Use `DiscoveryClient` to find healthy instances of the target service name registered in Consul.
        * Implement basic load balancing (e.g., random choice, round-robin) if multiple instances are found.
        * Use Micronaut's gRPC client facilities (`GrpcChannel` factory or `ManagedChannelBuilder`) to create a channel to the selected service instance address/port.
        * Create a gRPC stub for the `PipeStepProcessor` service (`PipeStepProcessorGrpc.newBlockingStub` or `newFutureStub`).
        * Call the `ProcessDocument` RPC using the stub and the request.
        * Handle the `ProcessPipeDocResponse`.
        * Implement robust error handling for network issues, service unavailable errors (map gRPC status codes), timeouts, etc.
        * Manage channel lifecycle appropriately.

5.  **Develop & Deploy Basic `PipeStepProcessor` Examples:**
    * **Goal:** Provide simple, working pipeline steps for the engine to call, allowing end-to-end testing of the core platform.
    * **Why Fifth:** Validates that the engine orchestration (Step 3) and step execution/discovery (Step 4) work correctly with actual, independently deployed services.
    * **Tasks:**
        * Create one or two separate, minimal Micronaut projects, each implementing a `PipeStepProcessor` service.
        * **Example 1: `EchoProcessor`**
            * Implement `ProcessDocument`.
            * Log the received `ProcessPipeDocRequest`.
            * Return a `ProcessPipeDocResponse` with `success = true`, `output_doc` set to the `input_doc`, and maybe a simple log message.
        * **Example 2: `AddMetadataProcessor`**
            * Implement `ProcessDocument`.
            * Add a key-value pair (e.g., "processed_by": "metadata_adder", "timestamp": current_time) to the `input_doc.custom_data` Struct.
            * Return `success = true` with the modified `PipeDoc` as `output_doc`.
        * Configure each service with a unique `micronaut.application.name` (e.g., `echo-processor`, `metadata-processor`).
        * Configure each service for Consul registration (`consul.client.registration.enabled=true`).
        * Build Docker images for these services.
        * Deploy these services (e.g., using Docker Compose alongside Consul, Kafka, and the Engine) so they register with Consul and are discoverable by the `PipeStreamEngine`.
