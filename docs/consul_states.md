Okay, I've reviewed the Java code, Protobuf definitions, and the markdown documents you provided. This has given me a good understanding of the Yappy engine's architecture, its bootstrapping process, and how modules are intended to be integrated.

Here's an outline of the startup states, a conceptual status enum, and how to handle these states with a focus on easily hooking up new modules.

## Yappy Engine Startup Sequence & States

The engine startup can be broadly divided into two main paths: **Setup Mode** (if initial configuration is missing) and **Normal Operational Startup**.

### 1. Initial Bootstrap & Consul Connection (`EngineBootstrapManager`, `BootstrapConfigServiceImpl`)

This phase ensures the engine can connect to Consul, which is critical for all subsequent configuration and service discovery.

* **State: `NEEDS_BOOTSTRAP_CONFIG`**
    * **Trigger:** Engine starts. `EngineBootstrapManager` checks for `~/.yappy/engine-bootstrap.properties`. File doesn't exist or lacks Consul host/port.
    * **Action:** Log "Engine starting in Setup Mode." Start minimal services:
        * `BootstrapConfigService` (gRPC)
        * `SetupController` (HTTP UI for setup)
    * **Next State:** `AWAITING_CONSUL_DETAILS`

* **State: `AWAITING_CONSUL_DETAILS`**
    * **Trigger:** Engine is in Setup Mode.
    * **Action:** Engine listens via `BootstrapConfigService` (`SetConsulConfiguration` RPC [cite: 9519]) and `SetupController` (`POST /setup/consul` [cite: 514]) for Consul connection details.
    * **Next State:** `VALIDATING_CONSUL_CONNECTION` (upon receiving details) or remains `AWAITING_CONSUL_DETAILS`.

* **State: `VALIDATING_CONSUL_CONNECTION`**
    * **Trigger:** Consul details submitted via API/UI.
    * **Action:** `BootstrapConfigServiceImpl` attempts to connect to Consul using the provided details. This might involve a ping or a basic operation via `ConsulBusinessOperationsService.isConsulAvailable()`[cite: 9519].
    * **Next State:**
        * `CONSUL_CONNECTION_SUCCESSFUL_NEEDS_RESTART` (if connection successful, details saved to bootstrap file [cite: 9519])
        * `CONSUL_CONNECTION_FAILED` (if connection fails)

* **State: `CONSUL_CONNECTION_FAILED`**
    * **Trigger:** Attempt to connect to Consul failed.
    * **Action:** `BootstrapConfigServiceImpl` returns an error status. UI/Client should allow user to correct details.
    * **Next State:** `AWAITING_CONSUL_DETAILS` (for user to retry).

* **State: `CONSUL_CONNECTION_SUCCESSFUL_NEEDS_RESTART`**
    * **Trigger:** Consul connection validated and details saved to `engine-bootstrap.properties`.
    * **Action:** Engine logs that a restart is recommended to apply the new bootstrap configuration fully[cite: 9519]. (Alternatively, the engine could attempt to dynamically re-initialize Consul-dependent components, but a restart is cleaner).
    * **Next State:** (After restart) `LOADING_BOOTSTRAP_CONFIG`.

* **State: `LOADING_BOOTSTRAP_CONFIG`**
    * **Trigger:** Engine starts. `EngineBootstrapManager` finds valid Consul details in `engine-bootstrap.properties`.
    * **Action:** `EngineBootstrapManager` sets system properties (`consul.client.host`, `consul.client.port`, etc.). Micronaut's Consul client initializes.
    * **Next State:** `CONNECTING_TO_CONSUL`.

* **State: `CONNECTING_TO_CONSUL`**
    * **Trigger:** System properties for Consul are set.
    * **Action:** Micronaut's internal Consul client attempts to connect and register (if `consul.client.registration.enabled=true` which is set by `EngineBootstrapManager`).
    * **Next State:**
        * `CONSUL_CONNECTED_AWAITING_YAPPY_CLUSTER` (if Micronaut's Consul client connects successfully)
        * `CONSUL_INITIALIZATION_FAILED` (if Micronaut's Consul client fails after multiple retries; this might lead to application shutdown or a degraded mode not fully covered by "Setup Mode" as primary config is expected here).

* **State: `CONSUL_INITIALIZATION_FAILED`**
    * **Trigger:** Micronaut's Consul client fails to connect/register despite bootstrap config.
    * **Action:** Critical error. Log extensively. Engine might enter a severely degraded state or exit. This state implies a problem with the configured Consul server itself.
    * **Next State:** `STOPPED` or `DEGRADED_FATAL_ERROR`.

### 2. Yappy Cluster Association & Configuration Loading

Once connected to Consul, the engine needs to associate with a Yappy Cluster and load its `PipelineClusterConfig`.

* **State: `CONSUL_CONNECTED_AWAITING_YAPPY_CLUSTER`**
    * **Trigger:** Engine connected to Consul, but no Yappy cluster is selected/defined (e.g., `app.config.cluster-name` not set, or `yappy.bootstrap.cluster.selected_name` not in bootstrap file).
    * **Action:** `BootstrapConfigService` (`ListAvailableClusters`, `SelectExistingCluster`, `CreateNewCluster` RPCs [cite: 9519]) and `SetupController` (`/setup/cluster` endpoints [cite: 515, 517, 518]) are active to guide cluster setup.
    * **Next State:** `LOADING_CLUSTER_CONFIG` (once a cluster is selected or created, and its name is persisted/available for `DynamicConfigurationManager`).

* **State: `LOADING_CLUSTER_CONFIG`**
    * **Trigger:** A Yappy cluster name is identified (either from `app.config.cluster-name` via `DynamicConfigurationManagerImpl` constructor or after selection/creation via `BootstrapConfigService`).
    * **Action:** `DynamicConfigurationManagerImpl.initialize()` is called[cite: 10503]. It uses `ConsulConfigFetcher` to get `PipelineClusterConfig` for the `effectiveClusterName`[cite: 10503]. Referenced schemas are also fetched.
    * **Next State:**
        * `VALIDATING_CLUSTER_CONFIG` (if config and schemas are fetched).
        * `CLUSTER_CONFIG_FETCH_FAILED` (if fetch from Consul fails).
        * `CLUSTER_DEFINITION_EMPTY_BOOTSTRAP_SEED` (if the engine identifies the cluster key in Consul is missing and proceeds to seed a minimal config, as per "Bootstrap Cluster" Refinement in `current_instructions.md` [cite: 1]).

* **State: `CLUSTER_CONFIG_FETCH_FAILED`**
    * **Trigger:** `ConsulConfigFetcher` fails to retrieve the `PipelineClusterConfig` or its schemas.
    * **Action:** Log error. Engine marks current config as stale[cite: 10503]. It will rely on the Consul watch to pick up the configuration when it becomes available.
    * **Next State:** `AWAITING_VALID_CLUSTER_CONFIG_VIA_WATCH`.

* **State: `VALIDATING_CLUSTER_CONFIG`**
    * **Trigger:** `PipelineClusterConfig` and its referenced schemas are fetched.
    * **Action:** `DynamicConfigurationManagerImpl` uses `ConfigurationValidator` to validate the fetched config[cite: 10504].
    * **Next State:**
        * `CLUSTER_CONFIG_VALID_AND_APPLIED` (if validation passes).
        * `CLUSTER_CONFIG_INVALID` (if validation fails).

* **State: `CLUSTER_CONFIG_INVALID`**
    * **Trigger:** `ConfigurationValidator` returns errors.
    * **Action:** Log errors. `DynamicConfigurationManagerImpl` does not update the `CachedConfigHolder` or publish an event for a successful update[cite: 10504]. Config is marked stale.
    * **Next State:** `AWAITING_VALID_CLUSTER_CONFIG_VIA_WATCH`.

* **State: `AWAITING_VALID_CLUSTER_CONFIG_VIA_WATCH`**
    * **Trigger:** Initial config load failed (fetch or validation) or a subsequent watch update failed.
    * **Action:** Engine operates with no (or a previously valid but now stale) configuration. `ConsulConfigFetcher`'s watch on the cluster config key in Consul remains active[cite: 10503].
    * **Next State:** `LOADING_CLUSTER_CONFIG` (when watch detects a change) or `DEGRADED_NO_CONFIG`.

* **State: `CLUSTER_CONFIG_VALID_AND_APPLIED`**
    * **Trigger:** Configuration validated successfully.
    * **Action:**
        * `DynamicConfigurationManagerImpl` updates `CachedConfigHolder` and publishes `PipelineClusterConfigChangeEvent`[cite: 10504].
        * Config is marked as not stale, version identifier is updated[cite: 10504].
    * **Next State:** `INITIALIZING_CORE_SERVICES`.

### 3. Engine Core Services Initialization

With a valid cluster configuration, the engine initializes its main operational components.

* **State: `INITIALIZING_CORE_SERVICES`**
    * **Trigger:** A valid `PipelineClusterConfig` is applied.
    * **Action:**
        * `KafkaListenerManager` reacts to `PipelineClusterConfigChangeEvent` and synchronizes Kafka listeners based on `KafkaInputDefinition`s in the config. This involves creating/removing listener instances in its `DefaultKafkaListenerPool`.
        * `ServiceStatusAggregator` starts its scheduled task to monitor module health and update statuses in Consul KV.
        * Other engine services (e.g., gRPC endpoints like `PipeStreamEngineImpl`, `ConnectorEngineImpl`) become fully operational.
    * **Next State:** `ENGINE_OPERATIONAL` (if all core initializations are successful). May go to `ENGINE_DEGRADED_CORE_SERVICE_FAILURE` if, for example, `KafkaListenerManager` cannot connect to Kafka.

* **State: `ENGINE_DEGRADED_CORE_SERVICE_FAILURE`**
    * **Trigger:** A critical core service (e.g., Kafka connectivity for listeners) fails to initialize.
    * **Action:** Log error. Engine operates in a degraded mode. Some pipelines may not function.
    * **Next State:** May attempt re-initialization or remain degraded.

* **State: `ENGINE_OPERATIONAL`**
    * **Trigger:** All core engine services initialized successfully using the loaded cluster configuration.
    * **Action:** Engine is running, processing `PipeStream`s, managing Kafka listeners for configured steps, and monitoring module statuses. It continues to watch for Consul updates to the cluster configuration.
    * **Next State:** Remains `ENGINE_OPERATIONAL`. Transitions to `LOADING_CLUSTER_CONFIG` upon a new valid configuration update from Consul watch, or potentially `AWAITING_VALID_CLUSTER_CONFIG_VIA_WATCH` if a watched update is problematic.

### Conceptual Startup Status Enum

```java
public enum YappyEngineStatus {
    // Bootstrapping and Consul Connection
    NEEDS_BOOTSTRAP_CONFIG,         // Initial state, no local bootstrap file for Consul
    AWAITING_CONSUL_DETAILS,        // In setup mode, waiting for user to provide Consul config
    VALIDATING_CONSUL_CONNECTION,   // Attempting to connect to Consul with provided details
    CONSUL_CONNECTION_FAILED,       // Failed to connect to Consul with provided details
    CONSUL_CONNECTION_SUCCESSFUL_NEEDS_RESTART, // Consul details saved, restart pending
    LOADING_BOOTSTRAP_CONFIG,       // Loading Consul details from local bootstrap file
    CONNECTING_TO_CONSUL,           // Micronaut Consul client initializing
    CONSUL_INITIALIZATION_FAILED,   // Micronaut Consul client failed to connect (fatal)

    // Yappy Cluster Configuration
    CONSUL_CONNECTED_AWAITING_YAPPY_CLUSTER, // Connected to Consul, needs Yappy cluster selection/creation
    CLUSTER_DEFINITION_EMPTY_BOOTSTRAP_SEED, // Seeding a new minimal cluster config in Consul
    LOADING_CLUSTER_CONFIG,         // DynamicConfigurationManager fetching PipelineClusterConfig
    CLUSTER_CONFIG_FETCH_FAILED,    // Failed to fetch config/schemas from Consul
    VALIDATING_CLUSTER_CONFIG,      // Validating fetched PipelineClusterConfig
    CLUSTER_CONFIG_INVALID,         // Fetched PipelineClusterConfig is invalid
    AWAITING_VALID_CLUSTER_CONFIG_VIA_WATCH, // Waiting for a valid config from Consul watch
    CLUSTER_CONFIG_VALID_AND_APPLIED, // Valid config loaded and applied

    // Core Services Initialization
    INITIALIZING_CORE_SERVICES,     // KafkaListenerManager, ServiceStatusAggregator, etc.
    ENGINE_DEGRADED_CORE_SERVICE_FAILURE, // A core engine component (e.g., Kafka connection) failed

    // Fully Operational / Runtime States
    ENGINE_OPERATIONAL,             // All systems go
    ENGINE_SHUTTING_DOWN,           // Graceful shutdown initiated
    STOPPED,                        // Engine is stopped

    // General Degraded/Error States (can be entered from various points)
    DEGRADED_STALE_CONFIG,          // Operating on an old config because new one failed validation/load
    DEGRADED_NO_CONFIG,             // No valid configuration available at all
    DEGRADED_FATAL_ERROR            // Unrecoverable error state
}
```

## Handling New Module Integration

The goal is to make integrating a new module as simple as possible for the developer providing the module. The Yappy Engine handles the complexities.

**Developer Responsibilities for a New Module:**

1.  **Develop the Module:** Implement the `PipeStepProcessor` gRPC service (e.g., `MyNewModuleService.java`).
2.  **Package and Deploy:** Deploy this gRPC service as a standard microservice. It should register itself with Consul using its defined `grpcServiceName` (e.g., `my-new-module-v1`) through standard Micronaut Consul registration (`micronaut.application.name` or `consul.client.registration.name`). This makes it discoverable.
3.  **Provide Schema (if applicable):** If the module's `customConfigJson` requires a specific structure, provide a JSON schema for it. This schema should be registered in Consul (e.g., via `SchemaRegistryService` or manual upload) under a known subject and version.

**Engine Actions to Integrate the New Module:**

The integration is primarily configuration-driven via the `PipelineClusterConfig` in Consul.

1.  **Configuration Update (Admin/CI/CD):**
    * The `PipelineClusterConfig` in Consul is updated to include:
        * A `PipelineModuleConfiguration` entry for `my-new-module-v1` in the `pipelineModuleMap`. This entry specifies:
            * `implementationId`: "my-new-module-v1" (must match the `grpcServiceName` the module uses to register itself in Consul).
            * `implementationName`: A human-readable name.
            * `customConfigSchemaReference`: (Optional) A `SchemaReference` pointing to the JSON schema for this module's `customConfig` (e.g., subject "my-new-module-schema", version 1).
            * `customConfig`: (Optional) Default JSON configuration for instances of this module if not overridden at the step level.
        * One or more `PipelineStepConfig` entries in various `PipelineConfig`s that use this new module. The `ProcessorInfo` within these steps will reference `grpcServiceName: "my-new-module-v1"`.

2.  **Engine Detects Configuration Change:**
    * **State Transition:** (Assuming Engine is `ENGINE_OPERATIONAL`) -> `LOADING_CLUSTER_CONFIG`
    * The Yappy Engine's `DynamicConfigurationManagerImpl` detects the change to `PipelineClusterConfig` via its Consul watch[cite: 10503].
    * It fetches the updated `PipelineClusterConfig`.
    * It fetches any *new* `SchemaReference`s mentioned in the module configurations (e.g., for "my-new-module-schema").

3.  **Engine Validates and Applies New Configuration:**
    * **State Transition:** `LOADING_CLUSTER_CONFIG` -> `VALIDATING_CLUSTER_CONFIG`
    * The `ConfigurationValidator` (including `CustomConfigSchemaValidator`, `ReferentialIntegrityValidator`, etc.) validates the entire new `PipelineClusterConfig`.
        * This ensures the `grpcServiceName` ("my-new-module-v1") in `ProcessorInfo` maps to a defined module in `pipelineModuleMap`.
        * If `customConfigSchemaReference` is present for "my-new-module-v1", and a step using it has `customConfig`, that config is validated against the schema.
    * **If Invalid:**
        * **State Transition:** `VALIDATING_CLUSTER_CONFIG` -> `DEGRADED_STALE_CONFIG` (engine keeps old config)
        * Errors are logged. The engine continues operating with the previous valid configuration. No event for successful update is published.
    * **If Valid:**
        * **State Transition:** `VALIDATING_CLUSTER_CONFIG` -> `CLUSTER_CONFIG_VALID_AND_APPLIED`
        * `DynamicConfigurationManagerImpl` updates the `CachedConfigHolder` and publishes a `PipelineClusterConfigChangeEvent`[cite: 10504].

4.  **Engine Adapts to New Module's Role:**
    * **State Transition:** `CLUSTER_CONFIG_VALID_AND_APPLIED` -> `INITIALIZING_CORE_SERVICES` (or re-synchronizing them)
    * **Kafka Listener Management (`KafkaListenerManager`):**
        * If any `PipelineStepConfig` that uses the new module (or any other module) has `kafkaInputs`, the `KafkaListenerManager` receives the `PipelineClusterConfigChangeEvent`.
        * It synchronizes its listeners: creates new `DynamicKafkaListener` instances for new inputs, updates existing ones if their config (topic, group, properties) changed, and removes listeners for inputs that no longer exist.
        * The `KafkaListenerManager` uses the `app.config.cluster-name` to generate default consumer group IDs if not specified in the `KafkaInputDefinition`[cite: 13653].
    * **Service Status Aggregation (`ServiceStatusAggregator`):**
        * The `ServiceStatusAggregator` will now include "my-new-module-v1" in its monitoring cycle.
        * It will query Consul for instances of "my-new-module-v1" (which should have self-registered).
        * It checks their health and the `yappy-config-digest` tag.
        * The `yappy-config-digest` tag on the module's Consul registration is crucial. The module itself should calculate this digest from its *actual running configuration* and include it during its own Consul registration process. The `YappyModuleRegistrationService.RegisterModule` RPC ([snippet from `current_proto.txt`, source:54190-54222], `YappyModuleRegistrationServiceImpl.java`) is designed for the module to provide its `instanceCustomConfigJson`, from which the engine calculates and includes the digest in the tags when registering the module.
        * The `ServiceStatusAggregator` compares this reported digest with the expected digest (calculated from `PipelineModuleConfiguration.customConfig` and its schema). A mismatch results in `CONFIGURATION_ERROR` status.
    * **gRPC Invocation (`PipeStreamEngineImpl` -> `GrpcPipeStepExecutor` -> `PipelineStepGrpcProcessorImpl` -> `GrpcChannelManager`):**
        * When a `PipeStream` targets a step implemented by "my-new-module-v1", the `GrpcChannelManager` will discover "my-new-module-v1" via Consul (using Micronaut's `DiscoveryClient`) and establish a channel.

5.  **Module is Now Live:**
    * **State:** `ENGINE_OPERATIONAL` (with the new module integrated into its active configuration).
    * The Yappy Engine will route `PipeStream`s to the new module as defined in the pipelines.

This approach makes module integration "easy" because:
* Module developers focus on the `PipeStepProcessor` gRPC interface and standard service deployment/registration.
* The Yappy Engine handles the dynamic configuration, discovery, Kafka listener setup, and status aggregation centrally.
* The "engine wrapper" for a Docker deployment would simply be the Yappy Engine application itself, configured with the appropriate `app.config.cluster-name`. The modules would be separate Docker containers/services.

This process aligns well with the described "engine wrapper" concept for Docker deployment where the engine and modules can scale independently, using Consul for configuration and discovery. The system properties set by `EngineBootstrapManager` ensure that Micronaut's Consul clients (for config and discovery) are enabled and correctly pointed to the Consul instance.