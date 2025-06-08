# Configuration Management

## Overview

Configuration management in YAPPY is centralized through Consul, providing dynamic configuration updates, version control, and validation. The system uses a hierarchical configuration structure that enables flexible pipeline definition and management.

## Configuration Hierarchy

### 1. PipelineClusterConfig

Top-level configuration for a cluster of pipelines:

```json
{
  "clusterName": "production-cluster",
  "pipelineGraphConfig": { ... },
  "pipelineModuleMap": { ... },
  "allowedKafkaTopics": [ ... ],
  "allowedGrpcServices": [ ... ],
  "whitelistConfig": { ... }
}
```

### 2. PipelineGraphConfig

Container for pipeline configurations:

```json
{
  "pipelines": {
    "search-pipeline": { ... },
    "analytics-pipeline": { ... }
  }
}
```

### 3. PipelineConfig

Configuration for a single pipeline:

```json
{
  "pipelineName": "search-pipeline",
  "description": "Document search processing pipeline",
  "pipelineSteps": {
    "document-ingest": { ... },
    "text-extraction": { ... },
    "chunking": { ... }
  }
}
```

### 4. PipelineStepConfig

Configuration for a single step:

```json
{
  "stepName": "chunking",
  "stepType": "PIPELINE",
  "processorInfo": {
    "moduleName": "chunker-module",
    "grpcServiceName": "chunker-service"
  },
  "customConfig": { ... },
  "outputs": {
    "chunks": {
      "transportType": "KAFKA",
      "kafkaTopic": "yappy.pipeline.search.step.embedding.input"
    }
  }
}
```

## Dynamic Configuration Manager

The `DynamicConfigurationManager` provides real-time configuration updates without engine restart:

### Key Features

1. **Configuration Loading**
   ```java
   Optional<PipelineClusterConfig> clusterConfig = 
       configManager.getClusterConfiguration();
   
   Optional<PipelineConfig> pipelineConfig = 
       configManager.getPipelineConfig("search-pipeline");
   ```

2. **Automatic Updates**
   - Watches Consul KV for configuration changes
   - Updates routing tables dynamically
   - No engine restart required
   - CAS operations prevent race conditions

3. **Version Tracking**
   - Tracks configuration versions
   - Maintains change history
   - Supports configuration rollback

### Work Distribution

Configuration updates are distributed via Kafka to ensure:
- Only one engine processes each update
- No duplicate work across instances
- Consistent state across cluster

### Implementation

```java
@Singleton
public class DynamicConfigurationManager {
    private final ConsulBusinessOperationsService consulOps;
    private volatile PipelineClusterConfig cachedConfig;
    
    @PostConstruct
    void init() {
        // Load initial configuration
        loadConfiguration();
        
        // Set up watch for changes
        setupConfigurationWatch();
    }
    
    public Optional<PipelineConfig> getPipelineConfig(String pipelineName) {
        return Optional.ofNullable(cachedConfig)
            .map(PipelineClusterConfig::pipelineGraphConfig)
            .map(PipelineGraphConfig::pipelines)
            .map(pipelines -> pipelines.get(pipelineName));
    }
}
```

## Configuration Storage in Consul

### Storage Paths

```
/yappy-clusters/{cluster-name}/
├── config                     # PipelineClusterConfig
├── version                    # Configuration version
├── schemas/                   # Custom configuration schemas
│   ├── chunker-config-v1
│   └── embedder-config-v1
└── status/                    # Runtime status
    ├── services/
    └── pipelines/
```

### Future Path Changes

Current: `pipeline-configs/clusters/{cluster-name}`
Future: `/yappy-clusters/{cluster-name}`

## Configuration Validation

### Validation Pipeline

All configurations are validated before being applied:

1. **Schema Validation** - JSON schema compliance
2. **Referential Integrity** - All references exist
3. **Loop Detection** - No circular dependencies
4. **Security Validation** - Whitelist compliance
5. **Type Validation** - Step types are consistent

### 1. CustomConfigSchemaValidator

Validates custom configurations against JSON schemas:

```java
public class CustomConfigSchemaValidator implements ConfigurationValidator {
    public ValidationResult validate(PipelineClusterConfig config) {
        List<String> errors = new ArrayList<>();
        
        for (PipelineStepConfig step : getAllSteps(config)) {
            if (step.customConfig() != null) {
                String schemaId = step.customConfigSchemaId();
                if (schemaId == null) {
                    errors.add("Step has custom config but no schema");
                } else {
                    validateAgainstSchema(step.customConfig(), schemaId, errors);
                }
            }
        }
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

### 2. ReferentialIntegrityValidator

Ensures all references are valid:

- Pipeline names match map keys
- Step names match map keys
- Module references exist
- Target steps exist
- Schema references valid

### 3. Loop Detection Validators

**InterPipelineLoopValidator:**
- Detects loops between pipelines
- Uses graph algorithms
- Prevents infinite processing

**IntraPipelineLoopValidator:**
- Detects loops within pipelines
- Validates step connections
- Ensures DAG structure

### 4. StepTypeValidator

Validates step configuration by type:

- **INITIAL_PIPELINE**: No Kafka inputs, must have outputs
- **SINK**: Has inputs, no outputs
- **PIPELINE**: Has both inputs and outputs

### 5. WhitelistValidator

Enforces security policies:

```java
public boolean isKafkaTopicPermitted(String topic, String pipelineName) {
    // Check explicit whitelist
    if (config.allowedKafkaTopics().contains(topic)) {
        return true;
    }
    
    // Check naming convention
    String pattern = "yappy.pipeline." + pipelineName + ".step.*.output";
    return topic.matches(pattern);
}
```

## Configuration-Driven Routing

### Following Configuration for Routing

The engine reads routing directly from configuration without making routing decisions:

```java
public List<RouteData> readRoutesFromConfig(PipelineStepConfig stepConfig) {
    List<RouteData> routes = new ArrayList<>();
    
    // Simply read the configured outputs
    for (OutputTarget output : stepConfig.outputs().values()) {
        if (output.transportType() == TransportType.GRPC) {
            routes.add(RouteData.builder()
                .targetStepName(output.targetStepName())
                .transportType(TransportType.GRPC)
                .grpcServiceName(output.grpcTransport().serviceName())
                .build());
        } else if (output.transportType() == TransportType.KAFKA) {
            routes.add(RouteData.builder()
                .targetStepName(output.targetStepName())
                .transportType(TransportType.KAFKA)
                .kafkaTopic(output.kafkaTopic())
                .build());
        }
    }
    
    return routes;
}
```

### Variable Resolution in Routing

Configuration supports variables for dynamic routing:

```yaml
outputs:
  default:
    transportType: KAFKA
    kafkaTopic: "yappy.pipeline.${pipelineName}.step.${stepName}.output"
    targetStepName: "next-step"
```

## Pipeline Module Map

### Structure

Maps logical module names to implementations:

```json
{
  "chunker-module": {
    "moduleName": "chunker-module",
    "implementationClass": "com.krickert.yappy.chunker.ChunkerModule",
    "grpcServiceName": "chunker-service",
    "customConfigSchemaReference": {
      "schemaId": "chunker-config-v1",
      "schemaRegistry": "CONSUL"
    }
  }
}
```

### Usage

1. **Module Discovery** - Find available modules
2. **Validation** - Ensure modules exist
3. **Configuration** - Apply module-specific config
4. **Schema Reference** - Validate custom configs

## Configuration Updates

### Dynamic Engine Updates

The engine automatically applies configuration changes without restart:

1. **Routing Updates:**
   - Pipeline step connections
   - Output destinations
   - Transport types (gRPC/Kafka)

2. **Whitelist Updates:**
   - Allowed Kafka topics
   - Allowed gRPC services
   - Connector mappings

3. **Module Configuration:**
   - Note: Modules may need restart to pick up their custom config changes
   - Engine will route to available instances during module restarts

### Update Process

1. **Validation** - New config is validated
2. **Versioning** - Version number incremented
3. **Storage** - Written to Consul
4. **Notification** - Components notified
5. **Application** - Changes applied

## Schema Management

### JSON Schema Storage

Custom configuration schemas stored in Consul:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "chunkSize": {
      "type": "integer",
      "minimum": 100,
      "maximum": 10000
    },
    "overlap": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    }
  },
  "required": ["chunkSize"]
}
```

### Schema Evolution

1. **Backward Compatible Changes:**
   - Adding optional fields
   - Relaxing constraints
   - Adding defaults

2. **Breaking Changes:**
   - Removing fields
   - Changing types
   - Tightening constraints

## Best Practices

### Configuration Design

1. **Use Descriptive Names**
   - Clear pipeline and step names
   - Meaningful configuration keys
   - Consistent naming conventions

2. **Provide Defaults**
   - Sensible default values
   - Optional advanced settings
   - Clear documentation

3. **Version Everything**
   - Track all changes
   - Use semantic versioning
   - Document migrations

### Security Considerations

1. **Least Privilege**
   - Minimal whitelist entries
   - Explicit permissions
   - Regular audits

2. **Sensitive Data**
   - Use Consul ACLs
   - Encrypt at rest
   - Audit access

3. **Validation**
   - Validate all inputs
   - Reject invalid configs
   - Log violations

## Troubleshooting

### Common Issues

1. **Configuration Not Loading**
   - Check Consul connectivity
   - Verify ACL permissions
   - Check configuration path

2. **Validation Failures**
   - Review error messages
   - Check schema compliance
   - Verify references

3. **Hot Reload Not Working**
   - Check watch registration
   - Verify change type
   - Review logs

### Debugging Tools

1. **Configuration Dump**
   ```bash
   GET /api/admin/config/dump
   ```

2. **Validation Report**
   ```bash
   POST /api/admin/config/validate
   ```

3. **Change History**
   ```bash
   GET /api/admin/config/history
   ```