# YAPPY Engine Integration Tests

This project contains integration tests for YAPPY containerized engines using Micronaut Test Resources.

## Overview

The integration tests validate:
- Multi-process container startup and health
- Service registration with Consul
- Module discovery and port mapping
- Pipeline processing through containerized engines
- Cross-engine communication via Kafka
- Supervisor process management

## Test Structure

### EngineContainerTest
Tests a single containerized engine with its co-located module:
- Container startup validation
- Consul registration verification
- Direct module communication on dedicated ports
- Pipeline configuration and processing
- Process resilience (supervisor restart)

### MultiEngineIntegrationTest
Tests multiple containerized engines working together:
- Multi-container orchestration
- Port separation validation (50052, 50053, etc.)
- Full pipeline processing (Tika → Chunker)
- Kafka-based inter-engine communication
- End-to-end document processing

## Port Mapping

Following the incremental port pattern:
- **Engine HTTP**: 8080
- **Engine gRPC**: 50051
- **Tika gRPC**: 50052
- **Chunker gRPC**: 50053
- **Embedder gRPC**: 50054
- (See PORT_MAPPING.md for full details)

## Running Tests

### Prerequisites

1. Build the container images:
```bash
cd ../yappy-containers
./build-engine-tika.sh
./build-engine-chunker.sh
```

2. Ensure Docker is running and test resources are available.

### Execute Tests

Run all integration tests:
```bash
./gradlew test
```

Run specific test class:
```bash
./gradlew test --tests EngineContainerTest
./gradlew test --tests MultiEngineIntegrationTest
```

### Test Resources

The tests use Micronaut Test Resources to automatically provision:
- Consul for service discovery
- Kafka for message routing
- Apicurio for schema registry

## Configuration

Test configuration is in `src/test/resources/application-test.yml`:
- Container image locations
- Port mappings
- Service endpoints

## Debugging

Enable debug logging:
```bash
./gradlew test -Dlogback.debug=true
```

View container logs:
```bash
docker logs <container-id>
```

## Adding New Tests

1. Create test class extending appropriate base
2. Use `@MicronautTest` annotation
3. Inject required services (ConsulService, etc.)
4. Configure containers with proper port mappings
5. Follow the incremental port pattern for new modules

## CI/CD Integration

These tests can be integrated into CI/CD pipelines:
1. Build stage creates container images
2. Test stage runs integration tests
3. Deploy stage pushes validated images

## Troubleshooting

### Container Won't Start
- Check image exists: `docker images | grep engine`
- Verify port availability: `lsof -i :50051`
- Check container logs: `docker logs <container>`

### Service Not Registering
- Verify Consul is running: `curl localhost:8500/v1/status/leader`
- Check network connectivity between containers
- Review service registration logs

### gRPC Connection Failed
- Ensure correct port mapping in container definition
- Verify firewall/security group rules
- Check gRPC health endpoint: `grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check`