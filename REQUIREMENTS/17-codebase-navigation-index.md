# YAPPY Codebase Navigation Index for LLMs

This document provides a comprehensive index to help LLMs navigate and understand the YAPPY codebase structure.

## Project Overview

YAPPY Engine is a pure orchestration layer that coordinates simple gRPC services (modules) through configuration-driven pipelines. The engine handles all infrastructure complexity while modules focus on business logic.

## Key Architecture Documents

### Start Here
1. **REQUIREMENTS/01-overview-and-principles.md** - Architecture overview with diagrams
2. **REQUIREMENTS/16-engine-internals.md** - Engine processing model and internals
3. **REQUIREMENTS/13-module-registration-flow.md** - How modules are registered

### Configuration & Operations
- **REQUIREMENTS/09-configuration-management.md** - Configuration structure and updates
- **REQUIREMENTS/14-connector-configuration.md** - How data enters pipelines
- **REQUIREMENTS/08-kafka-integration.md** - Kafka topics and slot management

## Core Components Location

### 1. Protocol Buffers (API Contracts)
**Location:** `yappy-models/protobuf-models/src/main/proto/`

Key files:
- `yappy_core_types.proto` - Core data types (PipeDoc, PipeStream, etc.)
- `pipe_step_processor_service.proto` - Module interface definition
- `connector_service.proto` - Connector engine interface
- `module_registration.proto` - Registration service interface

### 2. Configuration Models
**Location:** `yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/`

Key classes:
- `PipelineClusterConfig` - Top-level cluster configuration
- `PipelineConfig` - Individual pipeline configuration
- `PipelineStepConfig` - Step-level configuration
- `PipelineModuleConfiguration` - Module registry entry

Test data: `yappy-models/pipeline-config-models-test-utils/src/main/resources/`

### 3. Engine Implementation
**Location:** `yappy-engine/src/main/java/com/krickert/search/`

Key packages:
- `engine/registration/` - Module registration logic
- `engine/service/` - Core orchestration services
- `pipeline/engine/` - Pipeline execution engine
- `engine/health/` - Health monitoring

Key classes:
- `ModuleRegistrationService` - Handles module registration
- `MessageRoutingService` - Routes messages between modules
- `DynamicConfigurationManager` - Watches Consul for config changes

### 4. Consul Integration
**Location:** `yappy-consul-config/src/main/java/com/krickert/search/config/consul/`

Key classes:
- `DynamicConfigurationManagerImpl` - Watches and updates configuration
- `ConsulBusinessOperationsService` - Consul KV operations
- Tests demonstrate dynamic updates without restart

### 5. Kafka Slot Manager
**Location:** `yappy-kafka-slot-manager/src/main/java/com/krickert/yappy/kafka/`

Key classes:
- `KafkaSlotManager` - Manages partition claims via Consul
- Prevents excessive Kafka consumers across engine instances

### 6. Module Registration CLI
**Location:** `yappy-module-registration/src/main/java/com/krickert/yappy/registration/`

Key files:
- `YappyRegistrationCli.java` - Main CLI entry point
- `commands/RegisterCommand.java` - Module registration command
- `RegistrationService.java` - Registration logic

### 7. Module Examples
**Location:** `yappy-modules/`

Example modules (all simple gRPC services):
- `tika-parser/` - Document parsing module
- `chunker/` - Text chunking module
- `embedder/` - Embedding generation module
- `opensearch-sink/` - OpenSearch output module

Module pattern:
- Implements `PipeStepProcessor` gRPC service
- Has no knowledge of Consul/Kafka
- Configured via ProcessRequest

## Testing Resources

### Integration Tests
**Location:** `yappy-engine/src/test/java/com/krickert/search/`

Key test areas:
- `engine/integration/` - End-to-end tests
- `engine/registration/` - Registration tests
- `pipeline/` - Pipeline execution tests

### Test Resources
**Location:** `yappy-test-resources/`

Test containers:
- `consul-test-resource/` - Consul test container
- `apache-kafka-test-resource/` - Kafka test container
- `apicurio-test-resource/` - Schema registry test container

## Configuration Paths in Consul

```
/yappy-clusters/<cluster-name>/
├── config.json                    # PipelineClusterConfig
├── pipelines/
│   └── <pipeline-name>.json      # PipelineConfig
├── connector-mappings/
│   └── <source-id>.json          # Connector routing
├── modules/
│   └── registry/
│       └── <module-name>.json    # Module configuration
└── status/
    └── services/
        └── <service-name>.json   # Service health status
```

## Key Interfaces and Patterns

### Module Interface
Every module implements:
1. `ProcessData(ProcessRequest) → ProcessResponse`
2. `GetServiceRegistration() → ServiceRegistrationData`
3. Standard gRPC health check

### Engine Patterns
1. **Immutable PipeStream** - Never modified, always cloned
2. **Configuration-driven routing** - No dynamic decisions
3. **Consul claims** - For Kafka partition management
4. **Connection pooling** - Per module type via Micronaut

### Registration Flow
1. CI/CD deploys module
2. CLI registers with engine: `--module-endpoint --engine-endpoint`
3. Engine validates health
4. Engine registers in Consul

## Common Tasks

### Find Pipeline Configuration
1. Check `REQUIREMENTS/09-configuration-management.md` for structure
2. Look in `yappy-models/pipeline-config-models/` for Java models
3. Test data in `pipeline-config-models-test-utils/src/main/resources/`

### Understand Message Flow
1. Start with `REQUIREMENTS/01-overview-and-principles.md` sequence diagram
2. Check `yappy_core_types.proto` for PipeStream structure
3. Look at `MessageRoutingServiceImpl` for routing logic

### Add New Module
1. Implement `pipe_step_processor_service.proto`
2. Add health check service
3. Deploy and register via CLI
4. Configure in pipeline

### Debug Configuration Issues
1. Check `DynamicConfigurationManagerImpl` for config loading
2. Verify Consul paths match expected structure
3. Look at validation in `pipeline-config-models`

## Important Notes

1. **No Bootstrap Mode** - Engine requires Consul configured at startup
2. **No Embedded Engines** - Modules are simple gRPC services
3. **Explicit Registration** - Modules registered via CLI, not self-registered
4. **Dynamic Configuration** - Engine updates routing without restart
5. **gRPC-First** - Initial implementation uses gRPC only, Kafka added later

## Future Components (Planned)

1. **Pipeline Designer UI** - Visual pipeline configuration
2. **Admin CLI** - Cluster and pipeline management
3. **MongoDB Integration** - Large document storage
4. **Monitoring Stack** - Prometheus/Grafana integration
5. **Security Layer** - mTLS and authentication