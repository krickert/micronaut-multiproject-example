You're asking excellent questions that get to the core of how configuration is managed and applied in a distributed processing system. Let's clarify the configuration hierarchy and then outline a task list.

**Configuration Hierarchy: `PipelineModuleConfiguration.customConfig` vs. `PipelineStepConfig.processorInfo.customConfig`**

You are right to question how these two levels of `customConfig` interact. Here's how it's designed to work, and it aligns with best practices for such systems:

1.  **`PipelineModuleConfiguration.customConfig` (Module-Type Default):**
    * This is defined in the `pipelineModuleMap` within your `PipelineClusterConfig`.
    * It represents the **default configuration** for a specific module *type* (e.g., for all "echo-module-v1" instances) within that Yappy cluster.
    * When a module instance starts up and calls the `YappyModuleRegistrationService.GetServiceRegistration(implementation_id)` RPC, the Yappy Engine will provide *this* default `customConfigJson` (from `PipelineModuleConfiguration.customConfig`) in the `ServiceMetadata`.
    * The module then uses this JSON to perform its initial setup and establish its default operational parameters. The `configDigest` registered by the engine in Consul for this module instance will be based on this default configuration.

2.  **`PipelineStepConfig.processorInfo.customConfig` (Step-Specific Override):**
    * This is defined within a specific `PipelineStepConfig` in a `PipelineConfig`.
    * It allows you to **override or provide specific configuration values** for a module *only for that particular instance of its use in that specific pipeline step*.
    * **How it's used:** When the Yappy Engine's `PipeStreamEngineImpl` processes a `PipeStream` and is about to invoke a module for a particular step:
        * It retrieves the `PipelineStepConfig` for that exact step.
        * The `ProcessorInfo` from this `PipelineStepConfig`, including its `customConfig` (the step-specific JSON), is packaged into the `PipeStream` message itself (specifically within `PipeStream.current_step_config.processor_info.custom_config_json`).
        * The module, upon receiving the `PipeStream` in its `Process` gRPC method, **must look at this `custom_config_json` from the incoming `PipeStream.current_step_config`**.
        * If this step-specific `custom_config_json` is present and non-empty, the module should parse it and use those parameters *for that specific `Process` call*.
        * If the `custom_config_json` in the `PipeStream.current_step_config` is not present or is empty, the module falls back to using its default operational parameters (those it configured itself with using the JSON from `GetServiceRegistration`).

**Is this how it's coded now?**

* The `PipeStream` proto (`pipe_stream.proto` in `current_proto.txt`) indeed has `PipelineStepConfig current_step_config = 6;`.
* `PipelineStepConfig` proto (`pipeline_config.proto` in `current_proto.txt`) has `ProcessorInfo processor_info = 4;`.
* `ProcessorInfo` proto (`pipeline_config.proto`) has `optional string custom_config_json = 3;`.

This structure fully supports passing the step-specific `custom_config_json` with each call. Your `PipeStreamEngineImpl.java` (around line 230 in `current_java.txt`, where `currentStepConfig` is obtained) and how it constructs the call to the module (via `GrpcPipeStepExecutor`) would use this structure. The module's `Process` method implementation is then responsible for checking and applying this per-call override.

This is a robust and flexible design. It allows modules to have a general default behavior set at the cluster/module-type level, while still allowing pipeline designers to fine-tune behavior for specific tasks within a pipeline. You did not develop this wrong; this is a good pattern.

**Tasks to Get Engine Running (DEV and TEST)**

Here's a set of tasks to build confidence and get your Yappy engine operational in both environments. This plan assumes iterative development and testing.

**Phase 1: Engine Core Bootstrap & Configuration**

* **Task 1.1: Solidify `EngineBootstrapManager`**
    * **Objective:** Ensure the engine correctly enters "Setup Mode" (if `~/.yappy/engine-bootstrap.properties` is missing/incomplete) or starts normally, loading local bootstrap properties.
    * **Actions:**
        * Review `EngineBootstrapManager.java` for logic regarding property file loading and system property setting for Consul.
        * **DEV Test:** Manually manage the bootstrap file to test both scenarios. Verify log outputs.
        * **TEST Test (Integration):** Write a Micronaut test that starts the engine context. Programmatically ensure the bootstrap file is absent or present with specific content before context startup. Check application state or beans.
    * **Key Files:** `EngineBootstrapManager.java`.

* **Task 1.2: Verify `BootstrapConfigService` (gRPC & UI)**
    * **Objective:** Confirm the setup UI and gRPC endpoints for setting Consul details and managing Yappy cluster selection/creation work as intended.
    * **Actions:**
        * Review `BootstrapConfigServiceImpl.java` and `SetupController.java`.
        * Focus on `SetConsulConfiguration`, `CreateNewCluster` (ensure it seeds a minimal valid `PipelineClusterConfig` in Consul as per your design, e.g., at `yappy/config/clusters/{clusterName}/pipeline-cluster-config.json`), and `SelectExistingCluster`.
        * **DEV Test:** Use a gRPC client (like `grpcurl` or a simple Java client) and a web browser to interact with these endpoints. Verify changes in `engine-bootstrap.properties` and Consul.
        * **TEST Test:** Use `@MicronautTest` with `ConsulTestResourceProvider`. Make gRPC calls to the service, inspect Consul KV directly using the test Consul client.
    * **Key Files:** `BootstrapConfigServiceImpl.java`, `SetupController.java`, `bootstrap_config_service.proto`.

* **Task 1.3: Validate `DynamicConfigurationManager`**
    * **Objective:** Ensure the engine loads the `PipelineClusterConfig` from Consul (based on `app.config.cluster-name` or bootstrap settings), watches for changes, and correctly validates it (including JSON schema validation for `customConfig`).
    * **Actions:**
        * Review `DynamicConfigurationManagerImpl.java`, `KiwiprojectConsulConfigFetcher.java`, and `CustomConfigSchemaValidator.java`.
        * Ensure schema fetching (e.g., from `ConsulSchemaRegistryDelegate` or Apicurio client if configured) is operational.
        * **DEV Test:**
            * Manually seed a `PipelineClusterConfig` in your local Consul. Start the engine and verify it loads (check logs, or if possible, an admin/status endpoint).
            * Modify the config in Consul; verify the engine picks up the change.
            * Test with invalid `customConfigJson` against a defined schema; verify validation errors.
        * **TEST Test:** Use `ConsulTestResourceProvider` (and `ApicurioTestResourceProvider` or mock schema fetcher). Programmatically put valid/invalid configs and schemas in Consul/Apicurio. Start engine context, check loaded config via injected `DynamicConfigurationManager` or events.
    * **Key Files:** `DynamicConfigurationManagerImpl.java`, `ConsulConfigFetcher.java`, `ConfigurationValidator.java` and its rules, `SchemaRegistryClient.java`.

**Phase 2: Module Registration & Interaction**

* **Task 2.1: Implement & Test `YappyModuleRegistrationService` RPCs**
    * **Objective:** Implement the `GetServiceRegistration` and `RegisterModule` gRPC methods as discussed (module provides `implementation_id` to get its default config; engine uses this default config for digest calculation and Consul registration).
    * **Actions:**
        * Modify `module_registration_service.proto` to ensure `GetServiceRegistrationRequest` takes `implementation_id`.
        * Implement the server-side logic in `YappyModuleRegistrationServiceImpl.java`.
            * `GetServiceRegistration`: Fetches `PipelineModuleConfiguration` based on `implementation_id`, returns its `customConfig` and calculated digest.
            * `RegisterModule`: Uses module-provided network details but relies on its internally derived digest (from the module type's default config) for Consul tagging.
        * **DEV Test:** Use gRPC client to test.
        * **TEST Test:** `@MicronautTest` calling these RPCs. Verify Consul state (registrations, tags).
    * **Key Files:** `YappyModuleRegistrationServiceImpl.java`, `module_registration_service.proto`.

* **Task 2.2: Develop/Adapt a Test "Echo" Module**
    * **Objective:** Create a simple Micronaut gRPC module (implementing `PipeStepProcessor`) that correctly uses the two-phase registration with the engine.
    * **Actions:**
        * Module on startup:
            1.  Knows its `implementationId` (e.g., from `micronaut.application.name`).
            2.  Calls engine's `GetServiceRegistration` with this ID.
            3.  Uses the received `custom_config_json` for its initial/default setup (e.g., to set a default echo prefix). Stores the `grpc_service_name` from metadata.
            4.  Starts its gRPC server.
            5.  Calls engine's `RegisterModule` with its network details and the `grpc_service_name`.
        * Module's `Process` method:
            1.  Receives `PipeStream`.
            2.  Checks `pipeStream.getCurrentStepConfig().getProcessorInfo().getCustomConfigJson()`.
            3.  If present, uses this step-specific JSON for its behavior for *this call*. Otherwise, uses its default initialized behavior.
            4.  Returns the processed `PipeStream`.
        * **DEV Test:** Run Yappy Engine. Run Echo module. Verify successful registration sequence and logs on both sides. Check Consul.
    * **Key Files (New Module):** New Micronaut project for the Echo module.

* **Task 2.3: Test `ServiceStatusAggregator`**
    * **Objective:** Confirm the engine monitors registered modules, checks health, and validates the `yappy-config-digest` tag against the *default module-type config digest*.
    * **Actions:**
        * Review `ServiceStatusAggregator.java`.
        * **DEV/TEST Test:** With engine and Echo module running, check status in Consul KV.
            * Modify the default `customConfig` in `PipelineModuleConfiguration` (in Consul) *after* the module has registered. Verify `ServiceStatusAggregator` flags a `CONFIGURATION_ERROR` (or similar) because the live module's registered digest (based on old default) no longer matches the expected digest of the new default.
    * **Key Files:** `ServiceStatusAggregator.java`.

**Phase 3: End-to-End Simple Pipeline Execution**

* **Task 3.1: Define and Run a Simple Pipeline**
    * **Objective:** Execute a pipeline, e.g., Kafka In -> Echo Module -> Log Output (or Kafka Out).
    * **Actions:**
        * **Configure `PipelineClusterConfig` (in Consul):**
            * `PipelineModuleMap`: Entry for your Echo module (with `implementationId`, `grpcServiceName`, default `customConfig`).
            * `PipelineConfig` (e.g., "my-echo-pipeline"):
                * Define `KafkaInputDefinition` (topic, group).
                * `PipelineStepConfig` for the Echo module:
                    * `stepId`, `processorInfo.grpcServiceName` (matching Echo module).
                    * `processorInfo.customConfig` (optional: a step-specific JSON to test override).
                    * `nextStepKey` (if chaining) or define an output (e.g., logging or `KafkaPublishTopic`).
        * **DEV Test:** Start Yappy Engine, Echo Module, Kafka, Consul. Send a message to the input Kafka topic. Verify logs in engine and module. Check output.
        * **TEST Test:** Full integration test using `KafkaTestResourceProvider`, `ConsulTestResourceProvider`.
            * Programmatically set up `PipelineClusterConfig`.
            * Start engine context.
            * Start embedded Echo module context (or a test gRPC server).
            * Produce to Kafka, consume from output Kafka topic or check for specific log entries/mock interactions.
    * **Key Files:** `PipeStreamEngineImpl.java`, `KafkaListenerManager.java`, pipeline configuration JSONs.

**General Confidence-Building Measures:**

* **Incremental Testing:** Test each component in isolation as much as possible before integrating.
* **Clear Logging:** Add detailed logging, especially around configuration loading, RPC calls, and decision points (e.g., which `customConfig` is being used).
* **Debug Mode:** Be prepared to run services in debug mode to step through the logic.
* **Review Existing Tests:** Your project has many tests. Review relevant ones (e.g., in `yappy-engine/src/test/java/com/krickert/search/pipeline/integration/` and `yappy-consul-config/src/test/java/com/krickert/search/config/consul/`) to understand how components are tested and reuse patterns. Your `AllTestResourcesIT.java` is a great starting point for the TEST environment setup.

By tackling these tasks systematically, you should gain confidence in the setup and the core mechanics of the Yappy engine. Remember that the design principles you've established are sound.