## **YAPPY Development Lifecycle: Modules & Connectors**

This document outlines the theoretical steps involved in developing and integrating new processing modules (pipeline steps) and new data connectors into the YAPPY platform.

### **I. Developing a New Processing Module (e.g., a new yappy-module-xyz)**

A "Processing Module" is a gRPC service that implements the PipeStepProcessor interface. It performs a specific data transformation, enrichment, or analysis task within a YAPPY pipeline.

**Target gRPC Interface (from pipe\_step\_processor\_service.proto):**

* Service: PipeStepProcessor
* RPC: rpc ProcessData(ProcessRequest) returns (ProcessResponse);
* Request: ProcessRequest (contains PipeDoc document, ProcessConfiguration config, ServiceMetadata metadata)
* Response: ProcessResponse (contains success, optional PipeDoc output\_doc, optional google.protobuf.Struct error\_details, repeated string processor\_logs)

**Development Steps:**

1. **Define Module Logic & Configuration Needs:**
    * Clearly define the specific task the module will perform (e.g., sentiment analysis, PII redaction, format conversion).
    * Determine what parameters this module will need to be configurable for different pipeline steps (e.g., model name, confidence threshold, redaction patterns). These will become the custom\_json\_config.
    * Determine if any simple key-value config\_params are needed.
2. **Create JSON Schema for custom\_json\_config:**
    * Author a JSON Schema (.json file) that defines the structure, data types, and validation rules for the module's custom\_json\_config.
    * This schema ensures that any pipeline step using this module provides valid custom parameters.
    * **Example:** For a sentiment analyzer, the schema might define fields for model\_endpoint, api\_key, output\_field\_name.
3. **Register the JSON Schema:**
    * The administrator or module developer uploads this JSON Schema to the central Schema Registry (e.g., Apicurio, or your Consul-based implementation).
    * The schema gets a unique identifier (e.g., subject or groupId/artifactId, and version). This identifier will be referenced in the module's configuration.
4. **Implement the gRPC Service (PipeStepProcessor):**
    * Create a new project for the module (e.g., yappy-module-sentiment-analyzer using Micronaut for Java).
    * Include dependencies for gRPC, Micronaut, your yappy-models.protobuf-models.jar (which contains the generated classes from yappy\_core\_types.proto and pipe\_step\_processor\_service.proto).
    * Implement the PipeStepProcessorGrpc.PipeStepProcessorImplBase class.
    * Override the processData(ProcessRequest request, StreamObserver\<ProcessResponse\> responseObserver) method:
        * Access the input PipeDoc from request.getDocument().
        * Access custom\_json\_config (as google.protobuf.Struct) and config\_params from request.getConfig(). Parse the Struct into usable objects (e.g., POJOs using Jackson, or directly access its fields). The grpc-developer-sdk could provide helpers for this.
        * Optionally, use information from request.getMetadata() for logging, tracing, or advanced logic.
        * Perform the core processing logic of the module.
        * Construct the ProcessResponse:
            * Set success (true/false).
            * If successful and data is modified, set the output\_doc (which includes the Blob if any).
            * If failed, populate error\_details (as Struct).
            * Add any relevant processor\_logs.
        * Call responseObserver.onNext(response) and responseObserver.onCompleted().
    * Annotate the service class (e.g., @Singleton, @GrpcService for Micronaut).
5. **Build and Containerize the Module:**
    * Configure the module's build.gradle.kts to produce a runnable JAR (as we did for the Echo service).
    * Create a Dockerfile to package this JAR into a container image.
    * Publish the container image to a registry.
6. **Platform Configuration (Admin/Pipeline Designer Task in Consul):**
    * **Define PipelineModuleConfiguration:**
        * Create an entry in Consul (e.g., under pipelineModuleMap).
        * Key: A unique pipelineImplementationId for this module type (e.g., "com.example.SentimentAnalyzerV1").
        * Value (PipelineModuleConfiguration object):
            * implementationType: "GRPC"
            * serviceId: The Consul service name under which instances of this module will register (e.g., "sentiment-analyzer-service"). This is what the engine uses for discovery.
            * customConfigSchemaReference: Points to the JSON Schema registered in Step 3 (using its subject and version).
    * **Register Module Instances with Consul:**
        * When deploying instances of your module's container, ensure they register themselves with Consul using the serviceId defined above. Micronaut's Consul integration can handle this.
7. **Usage in Pipelines (Pipeline Designer Task in Consul):**
    * Pipeline designers can now create PipelineStepConfig entries in their PipelineConfig definitions that use this new module:
        * Set pipelineImplementationId to the one defined in PipelineModuleConfiguration (e.g., "com.example.SentimentAnalyzerV1").
        * Provide the customConfig.jsonConfig string, conforming to the module's JSON Schema.
        * Provide any configParams.
        * Define nextSteps, errorSteps, etc.

### **II. Developing a New Data Connector**

A "Data Connector" is an application or service responsible for fetching or receiving data from an external source and ingesting it into the YAPPY system by calling the PipeStreamEngine.

**Target gRPC Interface (from engine\_service.proto \- artifact engine\_service\_v2\_proto):**

* Service: PipeStreamEngine
* RPC: rpc IngestDataAsync(IngestDataRequest) returns (IngestDataResponse);
* Request: IngestDataRequest (contains string source\_identifier, PipeDoc document, map\<string, string\> initial\_context\_params, optional string suggested\_stream\_id)
* Response: IngestDataResponse (contains string stream\_id, bool accepted, string message)

**Development Steps:**

1. **Identify Data Source & Ingestion Logic:**
    * Determine the source system (e.g., S3 bucket, Kafka topic, REST API, database query, local filesystem).
    * Define how data will be acquired (e.g., polling, subscribing, receiving HTTP POSTs).
    * Define how raw source data will be transformed into a com.krickert.search.model.PipeDoc (including its embedded Blob if applicable).
2. **Define a Unique source\_identifier:**
    * Choose a unique string that will identify this connector or data source type (e.g., "s3-customer-uploads-connector", "webhook-event-ingestor").
    * This source\_identifier is crucial as it will be used by the PipeStreamEngine to look up the target pipeline.
3. **Platform Configuration (Admin/Pipeline Designer Task in Consul):**
    * **Create Connector Mapping:**
        * In Consul, define a mapping for this source\_identifier. This mapping links the source\_identifier to a specific targetPipelineName and targetInitialStepName within YAPPY.
        * (Refer to the "Configuration Impact (Conceptual)" section from our previous discussion for an example JSON structure for these mappings).
4. **Implement the Connector Application/Service:**
    * Create a new project for the connector (can be any technology/language that can make gRPC calls).
    * Include dependencies for interacting with the data source (e.g., AWS SDK for S3, Kafka client, HTTP client) and for making gRPC calls (including generated stubs for PipeStreamEngine).
    * **Core Logic:**
        * Connect to the data source.
        * Fetch/receive raw data.
        * Transform the raw data into a com.krickert.search.model.PipeDoc object. Remember the Blob is now part of PipeDoc.
        * Construct an IngestDataRequest:
            * Set source\_identifier to the unique ID defined in Step 2\.
            * Set document to the newly created PipeDoc.
            * Optionally, set initial\_context\_params if the connector has relevant contextual data for the entire pipeline run.
            * Optionally, set suggested\_stream\_id if the connector can provide a meaningful ID for idempotency or external correlation.
        * Obtain a gRPC client for the PipeStreamEngine service.
        * Call pipeStreamEngineClient.IngestDataAsync(ingestDataRequest).
        * Handle the IngestDataResponse:
            * Log the returned stream\_id for tracking.
            * Check the accepted status and log any message.
            * Implement retry logic or error handling if the ingestion is not accepted by the engine.
    * Consider how the connector will run (e.g., long-running service, scheduled job, serverless function).
5. **Build, Containerize (if applicable), and Deploy the Connector:**
    * Package the connector application.
    * If it's a long-running service, containerize it and deploy it.
    * Ensure it's configured with the address of the PipeStreamEngine and any necessary credentials for the data source.

**Summary of Flow:**

* **Connector:** DataSource \-\> Raw Data \-\> Transform to PipeDoc \-\> IngestDataRequest (with source\_identifier, PipeDoc) \-\> Calls PipeStreamEngine.IngestDataAsync()
* **Engine (on IngestDataAsync):**
    1. Receives IngestDataRequest.
    2. Uses source\_identifier to look up (targetPipelineName, targetInitialStepName) from Consul config.
    3. Generates stream\_id.
    4. Creates initial PipeStream (with input PipeDoc, initial\_context\_params, etc.).
    5. Determines the first PipelineStepConfig based on looked-up targetPipelineName and targetInitialStepName.
    6. Constructs ServiceMetadata and ProcessConfiguration for this first step.
    7. Creates ProcessRequest for the first module.
    8. Dispatches to the first module (which implements PipeStepProcessor.ProcessData).
    9. Returns IngestDataResponse (with stream\_id) to the connector.
* **Module (on ProcessData):**
    1. Receives ProcessRequest.
    2. Uses request.getDocument(), request.getConfig(), and optionally request.getMetadata().
    3. Performs its logic.
    4. Returns ProcessResponse.
* **Engine (continues orchestration):** Uses ProcessResponse to update PipeStream and route to the next step or finalize.

This lifecycle provides clear separation and well-defined interfaces, making the system extensible and maintainable.