# Module Integration Testing

This document describes how to test the integration between the Yappy engine and processing modules.

## Overview

The Yappy engine discovers and orchestrates processing modules via Consul service discovery. Modules are independent gRPC services that implement the `PipeStepProcessor` interface.

## Test Approaches

### 1. Concept Test (No Docker Required)

The `ModuleDiscoveryConceptTest` demonstrates the service discovery pattern without requiring actual module containers:

```bash
./gradlew :engine-core:test --tests ModuleDiscoveryConceptTest
```

This test:
- Registers mock module services in Consul
- Demonstrates service discovery
- Shows how the engine would execute pipelines

### 2. Full Integration Test (Requires Docker)

The `ModuleIntegrationTest` launches actual module containers and tests gRPC communication:

#### Prerequisites

1. Build the module Docker images:
```bash
cd engine-core
./build-module-images.sh
```

Or manually:
```bash
cd ../..
./gradlew :yappy-modules:chunker:dockerBuild
./gradlew :yappy-modules:tika-parser:dockerBuild
```

2. Run the integration test:
```bash
./gradlew :engine-core:test --tests ModuleIntegrationTest
```

This test:
- Launches chunker and tika-parser as Docker containers
- Creates gRPC clients to communicate with the modules
- Tests service registration and data processing
- Demonstrates end-to-end integration

## Module Registration Pattern

1. **Module Startup**: Each module starts and exposes:
   - gRPC service on port 50051
   - HTTP health check on port 8080
   - `GetServiceRegistration` RPC for metadata

2. **Service Registration**: Modules register themselves in Consul with:
   - Service name (e.g., "yappy-chunker")
   - gRPC address and port
   - Health check configuration
   - Metadata (module type, config schema, etc.)

3. **Service Discovery**: The engine queries Consul to:
   - Find healthy module instances
   - Load balance between multiple instances
   - Handle failover

4. **Pipeline Execution**: For each pipeline step:
   - Query Consul for the required module type
   - Create gRPC channel to selected instance
   - Call `ProcessData` with document and configuration
   - Handle response and route to next step

## Configuration

### Test Container Configuration

The `application-module-test.yml` file configures test containers using Micronaut's generic test container support:

```yaml
test-resources:
  containers:
    chunker:
      image-name: com.krickert.search/chunker:latest
      hostnames:
        - chunker.host
      exposed-ports:
        - chunker.grpc.port: 50051
      wait-strategy:
        log:
          regex: ".*Server Started.*"
```

### gRPC Client Configuration

gRPC clients are automatically configured with injected host/port values:

```yaml
grpc:
  client:
    chunker:
      address: "${chunker.host}:${chunker.grpc.port}"
      plaintext: true
```

## Next Steps

1. **Implement Automatic Registration**: Add code to automatically register modules in Consul when they're discovered
2. **Add Health Checks**: Implement proper gRPC health checks for modules
3. **Pipeline Execution**: Implement the actual pipeline execution logic using discovered modules
4. **Error Handling**: Add retry logic and circuit breakers for module communication
5. **Monitoring**: Add metrics and tracing for module interactions

## Troubleshooting

### Docker Images Not Found

If you get "image not found" errors, make sure to build the module images first:

```bash
docker images | grep chunker
docker images | grep tika-parser
```

### Port Conflicts

The test containers use random ports to avoid conflicts. If you still have issues:

1. Check for running containers: `docker ps`
2. Stop conflicting containers: `docker stop <container-id>`
3. Clean up: `docker system prune`

### Test Resources Not Starting

If test resources fail to start:

1. Check the test resources server logs
2. Increase the timeout in `micronaut.testResources.clientTimeout`
3. Ensure Docker is running and accessible