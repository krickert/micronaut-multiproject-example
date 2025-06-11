# Container Networking Problem: gRPC Service Discovery with Consul in Test Environment

## Problem Summary

We have a Micronaut-based data processing pipeline that uses gRPC for inter-service communication. During integration testing, services running in Docker containers cannot communicate with each other because Consul service discovery returns "localhost" as the service address, which fails for container-to-container communication.

## Architecture Overview

### Pipeline Flow
```
Document → Chunker (gRPC) → Test-Module (gRPC) → Kafka Output
```

### Test Environment Components
1. **Consul** - Service discovery and configuration management
2. **Kafka + Schema Registry** - Message streaming and schema management  
3. **Chunker Service** - Processes documents into chunks (Docker container)
4. **Test-Module Service** - Receives chunks and outputs to Kafka (Docker container)
5. **Engine** - Orchestrates the pipeline (runs in test JVM)

## The Problem in Detail

### What's Happening

1. **Service Registration**: When services start, they register with Consul:
   - Chunker registers as `scenario1-chunker` at `localhost:50051`
   - Test-Module registers as `scenario1-test-module` at `localhost:50052`

2. **Service Discovery**: When Chunker needs to forward data to Test-Module:
   - Engine queries Consul for `scenario1-test-module` service
   - Consul returns address: `localhost:50052`
   - Chunker (in container) tries to connect to `localhost:50052`
   - Connection fails with: `UNAVAILABLE: Network closed for unknown reason`

3. **Root Cause**: `localhost` inside a Docker container refers to that container's loopback interface, not the host or other containers.

### Test Setup

The test uses Micronaut Test Resources to manage containers:

```yaml
# application-module-test.yml
test:
  containers:
    chunker:
      image-name: chunker:latest
      hostnames:
        - chunker.host
      exposed-ports:
        - chunker.grpc.port: 50051
        - chunker.http.port: 8080
      env:
        GRPC_SERVER_PORT: "50051"
        GRPC_SERVER_HOST: "0.0.0.0"
        CONSUL_HOST: "${consul.host}"
        CONSUL_PORT: "${consul.port}"

    test-module-after-chunker:
      image-name: test-module:latest
      hostnames:
        - test-module-after-chunker.host
      exposed-ports:
        - test-module-after-chunker.grpc.port: 50052
        - test-module-after-chunker.http.port: 8081
      env:
        GRPC_SERVER_PORT: "50052"
        GRPC_SERVER_HOST: "0.0.0.0"
        CONSUL_HOST: "${consul.host}"
        CONSUL_PORT: "${consul.port}"
```

### Service Registration Code

```java
// Current registration approach (using localhost)
Registration chunkerReg = ImmutableRegistration.builder()
    .id(clusterName + "-chunker-test")
    .name(clusterName + "-chunker")
    .address("localhost")  // <-- Problem: won't work for container-to-container
    .port(chunkerPort)
    .build();
```

### Attempted Fix

We tried using Docker bridge IP instead:

```java
String dockerHostIp = "172.17.0.1";  // Docker bridge IP

Registration chunkerReg = ImmutableRegistration.builder()
    .id(clusterName + "-chunker-test")
    .name(clusterName + "-chunker")
    .address(dockerHostIp)  // Use bridge IP instead
    .port(chunkerPort)
    .build();
```

However, this still failed because:
1. The port mapping might not be accessible via the bridge network
2. The configuration wasn't loading properly after the change

## Evidence of the Problem

### Chunker Logs (Success until forwarding)
```
Processing document: us_constitution.txt
Successfully chunked document into 3 chunks
Attempting to forward to next step: scenario1-test-module
ERROR: Failed to forward - UNAVAILABLE: Network closed for unknown reason
```

### Test-Module Logs (No incoming requests)
```
GRPC started on port 50052
Waiting for requests...
[No further activity - never receives data]
```

### Key Observation
The test-module container shows no signs of receiving any data:
- No Kafka configuration loading attempts
- No processing logs
- No errors about malformed requests
- Just silence after startup

## Questions for Container Expert

1. **Service Discovery**: When services register with Consul from within containers, what address should they use?
   - Container's hostname within Docker network?
   - Docker bridge IP?
   - Some other approach?

2. **Port Mapping**: How should we handle the port mappings when:
   - Containers expose ports to the host (e.g., 50051 → random host port)
   - Other containers need to connect to these services
   - Should we use internal Docker network ports instead?

3. **Test Resources Integration**: Micronaut Test Resources manages these containers. Is there a standard pattern for:
   - Getting the correct container hostname/IP for service registration?
   - Ensuring container-to-container connectivity?

4. **Network Mode**: Should we be using a custom Docker network instead of the default bridge?

## Current Test Environment Details

- **Docker Version**: (included in test environment)
- **Micronaut Version**: 4.x
- **Test Resources**: Manages Consul, Kafka, and custom service containers
- **Container Runtime**: Docker with default bridge network
- **Service Discovery**: Consul 1.x

## What We Need

A solution that allows:
1. Services in containers to register with Consul using addresses that other containers can reach
2. Container-to-container gRPC communication to work reliably
3. Integration with Micronaut Test Resources framework
4. Maintainable and repeatable test setup

## Additional Context

- This is for integration testing, not production
- We cannot modify the service code (only test configuration)
- The services themselves work fine when run outside containers
- We need to preserve the ability to run tests both locally and in CI/CD