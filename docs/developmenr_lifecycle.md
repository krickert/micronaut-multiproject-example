# YAPPY Development Lifecycle: Modules & Connectors

This document outlines the steps involved in developing and integrating new processing modules (pipeline steps) and new data connectors into the YAPPY platform.

## I. Developing a New Processing Module

A "Processing Module" is a gRPC service that implements the `PipeStepProcessor` interface. It performs a specific data transformation, enrichment, or analysis task within a YAPPY pipeline. You can implement modules in various languages, with Java/Kotlin and Python being the most well-supported.

**Target gRPC Interface (from `pipe_step_processor_service.proto`):**

*   Service: `PipeStepProcessor`
*   RPC: `rpc ProcessData(ProcessRequest) returns (ProcessResponse);`
*   Request: `ProcessRequest` (contains `PipeDoc document`, `ProcessConfiguration config`, `ServiceMetadata metadata`)
*   Response: `ProcessResponse` (contains `success`, optional `PipeDoc output_doc`, optional `google.protobuf.Struct error_details`, repeated string `processor_logs`)

**Module Developer's Responsibilities:**

1.  **Define Module Logic & Configuration Needs:**
    *   Clearly define the specific task your module will perform (e.g., sentiment analysis, PII redaction, language translation).
    *   Determine what parameters your module instance will need to be configurable. This will be provided to your module as a JSON string at runtime (e.g., via an environment variable).
    *   Determine if any simple key-value `config_params` (passed via `ProcessConfiguration` in each `ProcessRequest`) are needed for per-request variations.

2.  **Create JSON Schema for Your Module's Custom Configuration:**
    *   Author a JSON Schema (`.json` file) that defines the structure, data types, and validation rules for your module's instance-specific custom configuration JSON.
    *   This schema will be used by pipeline designers and the YAPPY platform to validate the configuration provided for your module in a pipeline.
    *   **Example:** For a sentiment analyzer, the schema might define fields for `model_endpoint`, `api_key`, `output_field_name`.

3.  **Implement the gRPC Service (`PipeStepProcessor`):**
    *   Create a new project for your module (see language-specific instructions below).
    *   Generate gRPC stubs from Yappy's `.proto` files (`yappy_core_types.proto`, `pipe_step_processor_service.proto`).
    *   Implement the `PipeStepProcessor` service interface in your chosen language.
    *   **Implement the `ProcessData` RPC method:**
        *   Access the input `PipeDoc` from `request.getDocument()` (Java) or `request.document` (Python).
        *   Access your module's custom configuration (which was provided at pipeline design time) from `request.getConfig().getCustomJsonConfig()` (Java) or `request.config.custom_json_config` (Python). This will be a `google.protobuf.Struct` that you'll need to parse into your language's native data structures.
        *   Optionally, use information from `request.getMetadata()` (Java) or `request.metadata` (Python) for logging or advanced logic.
        *   Perform the core processing logic of your module.
        *   Construct and return the `ProcessResponse`.
    *   Your module application must start a gRPC server that serves this `PipeStepProcessor` implementation.
    *   Your module should also implement a health check endpoint (e.g., a simple HTTP endpoint or a gRPC health check) that the YAPPY platform can use.

4.  **Implement Startup Registration Call to YAPPY Platform:**
    *   **At your module's startup:**
        *   Load its unique **`implementation_id`**. This ID identifies the *type* of your module (e.g., "my-python-sentiment-analyzer-v1"). This will likely come from an environment variable (e.g., `YAPPY_IMPLEMENTATION_ID`).
        *   Load its **instance-level custom configuration as a JSON string**. This is the specific configuration for *this running instance* of your module (e.g., from `YAPPY_MODULE_INSTANCE_CONFIG_JSON` environment variable or a mounted config file).
        *   Determine its network-accessible `host` and `port` where its gRPC server is running.
        *   Determine its `health_check_type` (e.g., `HTTP`, `GRPC`) and `health_check_endpoint`.
        *   Optionally, determine its `module_software_version`, `instance_id_hint`, and any `additional_tags`.
    *   **Make the gRPC Call:**
        *   Using a gRPC client for the `YappyModuleRegistrationService` (stubs generated from `yappy_service_registration.proto`), call the `RegisterModule` RPC.
        *   Populate the `RegisterModuleRequest` with the information above, crucially including the `instance_custom_config_json` string.
        *   **You do NOT need to calculate any MD5/SHA sums.** The Yappy Registration Service will handle canonicalizing your provided JSON string and calculating the official digest.
        *   Handle the `RegisterModuleResponse`:
            *   Check `response.success`.
            *   Log `response.message`, `response.registered_service_id`, and `response.calculated_config_digest`. The `registered_service_id` is important if you plan to implement TTL heartbeating or graceful deregistration in the future. The `calculated_config_digest` tells you what digest the platform computed for your configuration.

5.  **Build and Containerize Your Module:**
    *   Package your module application (see language-specific instructions below).
    *   Create a `Dockerfile` to package your module and its gRPC server into a container image.
    *   Ensure your container is configured to receive necessary environment variables at runtime (e.g., `YAPPY_IMPLEMENTATION_ID`, `YAPPY_MODULE_INSTANCE_CONFIG_JSON`, address of the Yappy Registration Service, its own `MODULE_HOST`, `MODULE_PORT`).
    *   Publish the container image to a registry.

### I.A. Java/Kotlin Module Development

For Java/Kotlin modules, you can use the existing project structure and build system:

1. **Create a new module directory** in the `yappy-modules` directory.
2. **Use the project generator** to create a new module template:
   ```bash
   cd yappy-modules/project-generator
   ./gradlew run --args="--name=MyNewModule --package=com.krickert.yappy.modules.mynewmodule"
   ```
3. **Implement your module** by extending the generated template.
4. **Build your module** using Gradle:
   ```bash
   cd yappy-modules/my-new-module
   ./gradlew build
   ```

### I.B. Python Module Development

For Python modules, follow these steps:

1. **Create a new module directory** in the `yappy-modules` directory:
   ```bash
   mkdir -p yappy-modules/my-python-module
   ```

2. **Set up the Python project structure**:
   ```bash
   mkdir -p yappy-modules/my-python-module/src/main/python/my_python_module
   mkdir -p yappy-modules/my-python-module/src/test/python/my_python_module
   touch yappy-modules/my-python-module/src/main/python/my_python_module/__init__.py
   touch yappy-modules/my-python-module/src/test/python/my_python_module/__init__.py
   ```

3. **Create a Gradle build file** (`build.gradle.kts`) to integrate with the YAPPY build system:
   ```kotlin
   plugins {
       id("org.jetbrains.kotlin.jvm") version "1.9.22" // For Kotlin DSL support
       id("com.github.johnrengelman.shadow") version "8.1.1" // For creating fat JARs
   }

   version = "1.0.0-SNAPSHOT"
   group = "com.krickert.yappy.modules.mypythonmodule"

   repositories {
       mavenCentral()
   }

   // Custom task to generate Python gRPC code from protobuf files
   tasks.register<Exec>("generatePythonProto") {
       workingDir = projectDir
       commandLine("bash", "-c", """
           # Create directories if they don't exist
           mkdir -p src/main/python/generated

           # Get the protobuf files from the protobuf-models project
           PROTO_DIR=${rootProject.projectDir}/yappy-models/protobuf-models/src/main/proto

           # Generate Python code from proto files
           python -m grpc_tools.protoc \\
               -I$PROTO_DIR \\
               --python_out=src/main/python/generated \\
               --grpc_python_out=src/main/python/generated \\
               $PROTO_DIR/yappy_core_types.proto \\
               $PROTO_DIR/pipe_step_processor_service.proto \\
               $PROTO_DIR/yappy_service_registration.proto

           # Create __init__.py files to make the generated code importable
           touch src/main/python/generated/__init__.py
           find src/main/python/generated -type d -exec touch {}/__init__.py \\;
       """)
   }

   // Custom task to install Python dependencies
   tasks.register<Exec>("installPythonDependencies") {
       workingDir = projectDir
       commandLine("bash", "-c", """
           # Create and activate virtual environment if it doesn't exist
           if [ ! -d "venv" ]; then
               python -m venv venv
           fi

           # Install dependencies
           ./venv/bin/pip install -r requirements.txt
       """)
   }

   // Custom task to run Python tests
   tasks.register<Exec>("pythonTest") {
       workingDir = projectDir
       commandLine("bash", "-c", """
           # Activate virtual environment and run tests
           source venv/bin/activate
           python -m pytest src/test/python
       """)
       dependsOn("installPythonDependencies", "generatePythonProto")
   }

   // Custom task to build Python package
   tasks.register<Exec>("buildPythonPackage") {
       workingDir = projectDir
       commandLine("bash", "-c", """
           # Activate virtual environment and build package
           source venv/bin/activate
           python setup.py sdist bdist_wheel
       """)
       dependsOn("installPythonDependencies", "generatePythonProto")
   }

   // Custom task to run the Python service
   tasks.register<Exec>("runPythonService") {
       workingDir = projectDir
       commandLine("bash", "-c", """
           # Activate virtual environment and run service
           source venv/bin/activate
           python src/main/python/service.py
       """)
       dependsOn("installPythonDependencies", "generatePythonProto")
   }

   // Make the build task depend on the Python build
   tasks.named("build") {
       dependsOn("buildPythonPackage")
   }

   // Make the test task depend on the Python tests
   tasks.named("test") {
       dependsOn("pythonTest")
   }
   ```

4. **Create a requirements.txt file** for Python dependencies:
   ```
   # gRPC dependencies
   grpcio==1.62.1
   grpcio-tools==1.62.1
   protobuf==4.25.3

   # Testing dependencies
   pytest==7.4.4
   pytest-cov==4.1.0

   # Add your module-specific dependencies here
   ```

5. **Create a setup.py file** for Python packaging:
   ```python
   from setuptools import setup, find_packages

   setup(
       name="my-python-module",
       version="1.0.0",
       packages=find_packages(where="src/main/python"),
       package_dir={"": "src/main/python"},
       install_requires=[
           "grpcio",
           "grpcio-tools",
           "protobuf",
           # Add your module-specific dependencies here
       ],
       python_requires=">=3.8",
       description="A YAPPY module for [your module's purpose]",
       author="YAPPY Team",
       author_email="yappy@example.com",
   )
   ```

6. **Implement your module** by creating a Python class that implements the `PipeStepProcessor` gRPC service.

7. **Generate the gRPC code** using the Gradle task:
   ```bash
   cd yappy-modules/my-python-module
   ./gradlew generatePythonProto
   ```

8. **Build and test your module** using Gradle:
   ```bash
   ./gradlew buildPythonPackage
   ./gradlew pythonTest
   ```

9. **Run your module** using Gradle:
   ```bash
   ./gradlew runPythonService
   ```

10. **Create a Dockerfile** for containerization:
    ```dockerfile
    FROM python:3.10-slim

    WORKDIR /app

    # Copy requirements and install dependencies
    COPY requirements.txt .
    RUN pip install --no-cache-dir -r requirements.txt

    # Copy the Python module code
    COPY src/main/python /app/src/main/python
    COPY setup.py .

    # Install the module
    RUN pip install -e .

    # Set environment variables
    ENV MODULE_HOST=0.0.0.0
    ENV MODULE_PORT=50051
    ENV PYTHONPATH=/app

    # Expose the port
    EXPOSE 50051

    # Run the service
    CMD ["python", "src/main/python/service.py"]
    ```

**Conceptual Python Module Snippet (Illustrating Interaction with Registration Service)**

```python
import grpc
import json
import os
import socket
import uuid
import base64 # For potentially decoding canonical_config_json_base64 from response for logging

# Assume generated stubs from yappy_service_registration.proto are available
# These would be generated by protoc for Python.
# e.g., import yappy_module_registration_pb2
# e.g., import yappy_module_registration_pb2_grpc

# --- Placeholder for actual generated gRPC types ---
class yappy_module_registration_pb2:
    class RegisterModuleRequest:
        def __init__(self, implementation_id, instance_service_name, host, port,
                     health_check_type, health_check_endpoint,
                     instance_custom_config_json, # Module sends its config as JSON string
                     module_software_version=None, instance_id_hint=None, additional_tags=None):
            self.implementation_id = implementation_id
            self.instance_service_name = instance_service_name
            self.host = host
            self.port = port
            self.health_check_type = health_check_type
            self.health_check_endpoint = health_check_endpoint
            self.instance_custom_config_json = instance_custom_config_json
            self.module_software_version = module_software_version
            self.instance_id_hint = instance_id_hint
            self.additional_tags = additional_tags if additional_tags is not None else {}
            # Helper for protobuf optional field presence (simplified)
            self.HasField = lambda field_name: hasattr(self, field_name) and getattr(self, field_name) is not None

    class HealthCheckType:
        HEALTH_CHECK_TYPE_UNKNOWN = 0
        HTTP = 1
        GRPC = 2
        TCP = 3
        TTL = 4

    class RegisterModuleResponse:
        def __init__(self, success=False, message="", registered_service_id="",
                     calculated_config_digest="", canonical_config_json_base64=""): # Platform returns these
            self.success = success
            self.message = message
            self.registered_service_id = registered_service_id
            self.calculated_config_digest = calculated_config_digest
            self.canonical_config_json_base64 = canonical_config_json_base64
            self.HasField = lambda field_name: hasattr(self, field_name) and getattr(self, field_name) is not None

class yappy_module_registration_pb2_grpc:
    class YappyModuleRegistrationServiceStub:
        def __init__(self, channel):
            self.RegisterModule = channel.unary_unary(
                '/com.krickert.yappy.registration.api.YappyModuleRegistrationService/RegisterModule',
                request_serializer=lambda x: x, # Placeholder for actual Protobuf serializer
                response_deserializer=lambda x: x # Placeholder for actual Protobuf deserializer
            )
# --- End of placeholder generated types ---

def get_my_instance_config_as_json_string():
    """
    Loads the module's instance-specific custom configuration and returns it as a JSON string.
    This could read from an environment variable, a file, or be constructed programmatically.
    Best practice: If constructing from a dictionary, sort keys before serializing to JSON
                   to make the string more predictable for your own debugging, though the
                   Yappy platform will perform the authoritative canonicalization.
    """
    raw_config_json_str = os.environ.get("YAPPY_MODULE_INSTANCE_CONFIG_JSON", "{}")
    try:
        # Optional: Parse and re-serialize with sorted keys for local consistency
        config_dict = json.loads(raw_config_json_str)
        # Sort keys for a more canonical representation from the module's side
        return json.dumps(config_dict, sort_keys=True, separators=(',', ':'))
    except json.JSONDecodeError:
        print(f"Warning: YAPPY_MODULE_INSTANCE_CONFIG_JSON is not valid JSON: '{raw_config_json_str}'. Sending as is.")
        return raw_config_json_str # Send as is, platform will try to parse

def register_this_module_with_yappy(registration_service_address):
    implementation_id = os.environ.get("YAPPY_IMPLEMENTATION_ID")
    if not implementation_id:
        print("CRITICAL ERROR: YAPPY_IMPLEMENTATION_ID environment variable not set. Cannot register.")
        return False

    # Get the module's own custom configuration as a JSON string
    instance_custom_json = get_my_instance_config_as_json_string()

    # Determine module's own host and port
    # This needs to be specific to how your Python module determines its accessible address
    my_host = os.environ.get("MODULE_HOST", socket.getfqdn())
    my_port = int(os.environ.get("MODULE_PORT", 50051)) # Example port where this Python gRPC service runs

    # Define instance service name (can be made unique or common for load balancing)
    instance_service_name = os.environ.get("CONSUL_SERVICE_NAME", f"{implementation_id}-instance-{uuid.uuid4().hex[:8]}")

    # Health check details
    health_check_type = yappy_module_registration_pb2.HealthCheckType.HTTP # Example
    health_check_endpoint = os.environ.get("HEALTH_CHECK_ENDPOINT", "/health") # Example for HTTP

    # Optional details
    module_version = os.environ.get("MODULE_VERSION")
    instance_id_hint_val = os.environ.get("INSTANCE_ID_HINT")
    additional_tags_str = os.environ.get("ADDITIONAL_TAGS_JSON") # e.g., '{"env":"dev", "region":"us-west1"}'
    additional_tags_map = {}
    if additional_tags_str:
        try:
            additional_tags_map = json.loads(additional_tags_str)
        except json.JSONDecodeError:
            print(f"Warning: Could not parse ADDITIONAL_TAGS_JSON: {additional_tags_str}")

    # --- Prepare the gRPC Request ---
    # In a real scenario, you would use the actual generated gRPC stub
    # request_pb = yappy_module_registration_pb2.RegisterModuleRequest(
    #     implementation_id=implementation_id,
    #     instance_service_name=instance_service_name,
    #     host=my_host,
    #     port=my_port,
    #     health_check_type=health_check_type,
    #     health_check_endpoint=health_check_endpoint,
    #     instance_custom_config_json=instance_custom_json, # Send the JSON string
    #     module_software_version=module_version if module_version else None, # Handle optional
    #     instance_id_hint=instance_id_hint_val if instance_id_hint_val else None, # Handle optional
    #     additional_tags=additional_tags_map
    # )

    # Conceptual print of what would be sent
    print(f"--- Conceptual Registration Request ---")
    print(f"  Implementation ID: {implementation_id}")
    print(f"  Instance Service Name: {instance_service_name}")
    print(f"  Host: {my_host}, Port: {my_port}")
    print(f"  Health Check: Type={health_check_type}, Endpoint='{health_check_endpoint}'")
    print(f"  Instance Custom Config JSON: {instance_custom_json}")
    if module_version: print(f"  Module Version: {module_version}")
    if instance_id_hint_val: print(f"  Instance ID Hint: {instance_id_hint_val}")
    if additional_tags_map: print(f"  Additional Tags: {additional_tags_map}")
    print(f"------------------------------------")

    # --- Make the gRPC Call (Conceptual) ---
    # try:
    #     channel = grpc.insecure_channel(registration_service_address)
    #     stub = yappy_module_registration_pb2_grpc.YappyModuleRegistrationServiceStub(channel)
    #
    #     print(f"Attempting to register module with Yappy Registration Service at {registration_service_address}...")
    #     response = stub.RegisterModule(request_pb, timeout=10) # Example timeout
    #
    #     if response.success:
    #         print(f"SUCCESS: Module registered with Yappy Platform.")
    #         print(f"  Registered Service ID: {response.registered_service_id}")
    #         print(f"  Platform Calculated Config Digest: {response.calculated_config_digest}")
    #         if response.HasField("canonical_config_json_base64") and response.canonical_config_json_base64:
    #             try:
    #                 decoded_canonical_json = base64.b64decode(response.canonical_config_json_base64).decode('utf-8')
    #                 print(f"  Platform Canonical JSON (used for digest):\n    {decoded_canonical_json}")
    #             except Exception as e:
    #                 print(f"  Could not decode canonical_config_json_base64: {e}")
    #         return True
    #     else:
    #         print(f"FAILURE: Module registration failed. Message: {response.message}")
    #         print(f"  Registered Service ID (if any): {response.registered_service_id}")
    #         print(f"  Platform Calculated Config Digest (if any): {response.calculated_config_digest}")
    #         return False
    #
    # except grpc.RpcError as e:
    #     print(f"FAILURE: gRPC error during registration: {e.code()} - {e.details()}")
    #     return False
    # except Exception as e:
    #     print(f"FAILURE: An unexpected error occurred during registration: {e}")
    #     return False
    return True # Placeholder for conceptual success

# Example of how a Python module might call this at startup:
# if __name__ == '__main__':
#     yappy_registration_service_addr = os.environ.get("YAPPY_REGISTRATION_SERVICE_ADDRESS", "localhost:50050")
#     if register_this_module_with_yappy(yappy_registration_service_addr):
#         print("Module registration call successful (check logs for platform confirmation). Starting gRPC server...")
#         # ... code to start your module's PipeStepProcessor gRPC server ...
#         # server = grpc.server(...)
#         # my_module_servicer = MyPipeStepProcessorServicer() # Your implementation
#         # pipe_step_processor_pb2_grpc.add_PipeStepProcessorServicer_to_server(my_module_servicer, server)
#         # server.add_insecure_port(f'[::]:{my_port}') # Use the port determined earlier
#         # server.start()
#         # print(f"Module gRPC server started on port {my_port}")
#         # server.wait_for_termination()
#     else:
#         print("Module registration call failed. Module will not start its gRPC server.")
```

## II. YAPPY Platform: Engine Lifecycle & Module Integration

This section outlines how the YAPPY Engine and its components work together and integrate with your developed modules. Module developers typically don't need to manage these platform-side steps but understanding the flow is beneficial.

1.  **Yappy Engine Startup & Cluster Association:**
    *   A Yappy Engine instance starts. It requires a connection to Consul.
    *   The Engine is configured with a `cluster-name` (e.g., via an `app.config.cluster-name` property).
    *   The Engine's `DynamicConfigurationManager` loads the `PipelineClusterConfig` for this `cluster-name` from Consul. This configuration contains:
        *   Definitions of all pipelines (`PipelineConfig`).
        *   Definitions of all steps within those pipelines (`PipelineStepConfig`).
        *   Definitions of all known module types (`PipelineModuleConfiguration` in a map keyed by `implementation_id`), including:
            *   The `implementation_id` (which your module reports).
            *   A reference to the JSON Schema for the module's `customConfig`.
            *   The *template/default* `customConfig` (as a `Map<String, Object>`) for this module *type*. The Engine uses this to calculate an *expected configuration digest*.
    *   The Engine may serve Admin/Design APIs (if enabled).

2.  **Module Deployment & Self-Registration (As described in Section I):**
    *   Your deployed module instance starts up.
    *   It calls the Yappy Engine's `YappyModuleRegistrationService.RegisterModule` RPC, sending its `implementation_id`, network details, health check info, and its **`instance_custom_config_json` string**.

3.  **Yappy Module Registration Service (Platform Action):**
    *   Receives the `RegisterModuleRequest` from your module.
    *   **Parses** the `instance_custom_config_json` string.
    *   **Canonicalizes** the parsed JSON object (e.g., sorts keys alphabetically, uses compact formatting) to ensure a consistent string representation.
    *   **Calculates the MD5/SHA256 digest** of this *platform-canonicalized JSON string*. This becomes the official `config_digest` for your running module instance.
    *   Uses its `ConsulBusinessOperationsService` to register your module instance in Consul. This Consul registration will include tags like:
        *   `yappy-module=true`
        *   `yappy-module-implementation-id:<your_module_implementation_id>`
        *   `yappy-config-digest:<platform_calculated_digest>`
        *   `yappy-module-version:<your_module_version>` (if provided)
        *   Other tags based on your request and platform standards.
    *   Returns a `RegisterModuleResponse` to your module, which includes the `success` status, `registered_service_id` (from Consul), and the `calculated_config_digest`.

4.  **Engine's Module Discovery & Status Monitoring (`ServiceStatusAggregator`):**
    *   The Engine continuously monitors Consul for services tagged with `yappy-module=true`.
    *   For each discovered module instance:
        *   It checks the Consul health status.
        *   It reads the `yappy-config-digest` tag (which was set by the Registration Service).
        *   It compares this *reported digest* with the *expected digest* that the Engine itself calculates from the `PipelineModuleConfiguration.customConfig` (the template config for that module type).
        *   If the digests mismatch, the module instance is marked with a `CONFIGURATION_ERROR` status.
    *   This aggregated status is made available (e.g., stored back in Consul, exposed via platform APIs).

5.  **Pipeline Design & Activation (Admin/Pipeline Designer Workflow):**
    *   Using a YAPPY Admin UI or APIs, a pipeline designer:
        *   Creates or modifies a `PipelineConfig`.
        *   Adds `PipelineStepConfig` entries. For each step that uses a custom module, they set `processorInfo.grpcServiceName` to your module's `implementation_id`.
        *   Provides the `customConfig.jsonConfig` (a JSON structure) specific to how your module should behave in *this particular step*. This JSON must conform to the JSON Schema you provided (Step I.2).
        *   The Admin UI/tool should validate this `customConfig.jsonConfig` against your module's JSON schema *before* saving.
    *   When the pipeline design is "committed" or "deployed," the `PipelineClusterConfig` in Consul is updated.
    *   The Yappy Engine's `DynamicConfigurationManager` detects this change.
    *   The Engine re-validates the entire new `PipelineClusterConfig`. If valid, it becomes the active configuration. The `ServiceStatusAggregator` then re-evaluates module statuses against this new expected configuration.

6.  **Kafka Topic Management (Engine Responsibility):**
    *   If pipeline steps are configured to use Kafka for input or output, the Yappy Engine (e.g., via `PipelineKafkaTopicService`) ensures the necessary Kafka topics are created and validated based on the step's configuration and platform naming conventions. Topic parameters can be specified in the `PipelineStepConfig` or will use platform defaults.

7.  **Data Ingestion & Processing:**
    *   Data Connectors (see Section III) call `PipeStreamEngine.processConnectorDoc` to ingest new data into a specific pipeline.
    *   The Engine, using its active `PipelineConfig`, orchestrates the flow of data (`PipeStream`) by:
        *   Identifying the correct, healthy, and properly configured module instance(s) for each step using `GrpcChannelManager` (which discovers services via Consul using tags like `yappy-module-implementation-id`).
        *   Dispatching `ProcessRequest` messages to these module instances.

## III. Developing a New Data Connector

(This section remains largely unchanged from previous versions, as the primary interaction is with the `PipeStreamEngine` and not directly with module registration details.)

A "Data Connector" is an application or service responsible for fetching or receiving data from an external source and ingesting it into the YAPPY system by calling the `PipeStreamEngine`.

**Target gRPC Interface (from `engine_service.proto`):**

*   Service: `PipeStreamEngine`
*   RPC: `rpc processConnectorDoc(ConnectorRequest) returns (ConnectorResponse);`
*   Request: `ConnectorRequest` (contains `string source_identifier`, `PipeDoc document`, `map<string, string> initial_context_params`, optional `string suggested_stream_id`)
*   Response: `ConnectorResponse` (contains `string stream_id`, `bool accepted`, `string message`)

**Development Steps:**

1.  **Identify Data Source & Ingestion Logic.**
2.  **Define a Unique `source_identifier`** (this will be the name of a `PipeStepConfig` that acts as the entry point for this connector).
3.  **Platform Configuration (Admin/Pipeline Designer Task in Consul):**
    *   Ensure a `PipeStepConfig` exists within a `PipelineConfig` whose name matches the `source_identifier` your connector will use. This step defines the starting point of the pipeline for data ingested by this connector.
    *   The Yappy Engine's `processConnectorDoc` method uses the `source_identifier` from the `ConnectorRequest` to find this corresponding `PipeStepConfig` and its parent `PipelineConfig` to initiate processing.
4.  **Implement the Connector Application/Service:**
    *   Transform source data to `PipeDoc`.
    *   Construct `ConnectorRequest`, setting the `source_identifier` to the name of the designated entry-point `PipeStepConfig`.
    *   Call `PipeStreamEngine.processConnectorDoc()`.
5.  **Build, Containerize (if applicable), and Deploy the Connector.**
