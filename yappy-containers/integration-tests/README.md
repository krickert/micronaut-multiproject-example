# YAPPY Container Integration Tests

This directory contains integration tests for the containerized YAPPY engine+module architecture.

## Prerequisites

- Docker and Docker Compose installed
- All YAPPY container images built and available in your registry
- Port availability: 8500, 9092, 8081, 9200, 8082-8085, 50051-50055, 50061, 50071, 50081

## Test Suites

### 1. Full Integration Test (`run-integration-tests.sh`)

Comprehensive test that:
- Starts all infrastructure (Consul, Kafka, Apicurio, OpenSearch)
- Starts all engine containers (Tika, Chunker, Embedder, OpenSearch-Sink)
- Verifies service health and registration
- Checks inter-process communication
- Validates port separation

```bash
# Run tests and tear down
./run-integration-tests.sh

# Run tests and keep environment running
./run-integration-tests.sh --keep
```

### 2. Connectivity Test (`test-connectivity.sh`)

Quick connectivity verification:
- HTTP health endpoints
- gRPC port availability
- Consul service discovery
- Kafka topic operations
- Inter-service network communication

```bash
# Run connectivity tests (requires environment from test 1)
./test-connectivity.sh

# Install grpcurl for enhanced testing
./test-connectivity.sh --install-grpcurl
```

### 3. Document Pipeline Test (`test-document-pipeline.sh`)

End-to-end document processing test:
- Creates test document
- Submits to pipeline
- Verifies processing through all stages
- Checks final indexing in OpenSearch

```bash
# Run pipeline test (requires environment from test 1)
./test-document-pipeline.sh
```

## Test Environment

The `docker-compose.test.yml` defines the complete test environment:

### Infrastructure Services
- **Consul**: Service discovery and configuration (port 8500)
- **Kafka**: Message broker in KRaft mode (port 9092)
- **Apicurio**: Schema registry (port 8081)
- **OpenSearch**: Search backend (port 9200)

### Engine Containers
Each container runs both the YAPPY engine and its respective module:

| Container | Engine Port | Module Port | Module Type |
|-----------|------------|-------------|-------------|
| engine-tika | 8082 (HTTP), 50051 (gRPC) | 50052 | Tika Parser |
| engine-chunker | 8083 (HTTP), 50061 (gRPC) | 50053 | Text Chunker |
| engine-embedder | 8084 (HTTP), 50071 (gRPC) | 50054 | Embedder |
| engine-opensearch-sink | 8085 (HTTP), 50081 (gRPC) | 50055 | OpenSearch Sink |

## Debugging

### View Logs
```bash
# All services
docker-compose -f docker-compose.test.yml logs

# Specific service
docker-compose -f docker-compose.test.yml logs engine-tika

# Follow logs
docker-compose -f docker-compose.test.yml logs -f engine-chunker
```

### Check Process Status
```bash
# List running processes in a container
docker exec engine-tika-test ps aux

# Check supervisor status
docker exec engine-tika-test supervisorctl status
```

### Manual Service Inspection
```bash
# Consul UI
open http://localhost:8500

# Check registered services
curl http://localhost:8500/v1/catalog/services | jq

# OpenSearch health
curl http://localhost:9200/_cluster/health | jq
```

## Troubleshooting

### Container Won't Start
1. Check if ports are already in use: `lsof -i :8082`
2. Verify images exist: `docker images | grep yappy`
3. Check container logs: `docker logs engine-tika-test`

### Services Not Registering
1. Verify Consul is healthy: `curl http://localhost:8500/v1/status/leader`
2. Check container can reach Consul: `docker exec engine-tika-test curl http://consul:8500`
3. Review engine logs for registration errors

### Pipeline Not Processing
1. Verify Kafka topics: `docker exec kafka-test kafka-topics.sh --bootstrap-server localhost:9092 --list`
2. Check schema registry: `curl http://localhost:8081/subjects`
3. Monitor OpenSearch: `curl http://localhost:9200/_cat/indices`

## Next Steps

After successful integration tests:
1. Create production docker-compose configuration
2. Implement Kubernetes manifests
3. Set up monitoring and observability
4. Create performance benchmarks
5. Implement chaos testing