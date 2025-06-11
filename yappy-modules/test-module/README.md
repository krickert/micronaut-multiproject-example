# Test Module

A debugging and testing module for the Yappy pipeline that provides flexible output options for inspecting pipeline data flow.

## Overview

The Test Module is a pipeline step processor that can be inserted at any point in a pipeline to observe and debug the data flowing through. It supports three output modes:

1. **CONSOLE** - Outputs data to stdout/logs
2. **KAFKA** - Publishes data to a Kafka topic  
3. **FILE** - Writes data to protobuf binary files

## Features

- gRPC-based PipeStepProcessor implementation
- Configurable output modes via JSON configuration
- Preserves document metadata and content
- Supports streaming of multiple documents
- Useful for debugging pipeline issues
- Can be used in integration tests to validate data flow

## Configuration

### JSON Schema

```json
{
  "type": "object",
  "properties": {
    "output_type": {
      "type": "string",
      "enum": ["CONSOLE", "KAFKA", "FILE"],
      "description": "Where to output the received documents"
    },
    "kafka_topic": {
      "type": "string",
      "description": "Kafka topic name (required if output_type is KAFKA)"
    },
    "output_directory": {
      "type": "string", 
      "description": "Directory for output files (required if output_type is FILE)"
    },
    "file_prefix": {
      "type": "string",
      "description": "Prefix for output filenames (optional for FILE mode)"
    }
  },
  "required": ["output_type"]
}
```

### Example Configurations

**Console Output (for debugging):**
```json
{
  "output_type": "CONSOLE"
}
```

**Kafka Output:**
```json
{
  "output_type": "KAFKA",
  "kafka_topic": "debug-output"
}
```

**File Output:**
```json
{
  "output_type": "FILE",
  "output_directory": "/tmp/pipeline-debug",
  "file_prefix": "debug"
}
```

## Building

```bash
./gradlew :yappy-modules:test-module:build
```

## Docker Image

```bash
./gradlew :yappy-modules:test-module:dockerBuild
```

## Running

### Standalone
```bash
./gradlew :yappy-modules:test-module:run
```

### Environment Variables
- `GRPC_SERVER_PORT` - gRPC server port (default: 50051)
- `MICRONAUT_SERVER_PORT` - HTTP server port (default: 8080)
- `KAFKA_ENABLED` - Enable Kafka support (default: false)
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka bootstrap servers (default: localhost:9092)

## Usage in Pipelines

The test-module can be inserted at any point in a pipeline to inspect data:

```yaml
# Example pipeline configuration
pipeline:
  steps:
    - name: tika-parser
      type: PIPELINE
    - name: test-module-after-tika
      type: PIPELINE
      config:
        output_type: CONSOLE
    - name: chunker
      type: PIPELINE
    - name: test-module-after-chunker
      type: PIPELINE
      config:
        output_type: FILE
        output_directory: /tmp/chunks
```

## Testing

Run unit tests:
```bash
./gradlew :yappy-modules:test-module:test
```

## Integration with Test Resources

When used in integration tests, the test-module can be configured in the test resources YAML:

```yaml
test-resources:
  containers:
    test-module-debug:
      image-name: test-module:latest
      hostnames:
        - test-module.host
      exposed-ports:
        - test-module.grpc.port: 50051
      wait-strategy:
        log:
          regex: ".*GRPC started on port.*"
```

## Troubleshooting

1. **Container startup timeout**: Increase `startup-timeout` in test-resources configuration
2. **Kafka connection issues**: Ensure `KAFKA_ENABLED=true` and proper bootstrap servers are configured
3. **File permissions**: Ensure the container has write permissions to the output directory when using FILE mode