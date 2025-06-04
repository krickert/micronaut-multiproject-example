# YAPPY Docker Compose Orchestration

This directory contains the Docker Compose configuration for running the complete YAPPY system with all engines and modules in a containerized environment.

## Quick Start

```bash
# Start everything
./yappy-compose.sh up

# Start only infrastructure
./yappy-compose.sh infra

# Check status
./yappy-compose.sh status

# View logs
./yappy-compose.sh logs -f
```

## Architecture

The docker-compose.yml orchestrates the following services:

### Infrastructure Services
- **Consul**: Service discovery and configuration (port 8500)
- **Kafka**: Message broker in KRaft mode (port 9092)
- **Apicurio Registry**: Schema registry (port 8081)
- **OpenSearch**: Search and analytics engine (port 9200)
- **OpenSearch Dashboards**: Visualization UI (port 5601)

### Engine + Module Containers
Each container runs both a YAPPY engine instance and its associated module:

| Container | Engine Port | Module Port | Module Type |
|-----------|------------|-------------|-------------|
| engine-tika | 8082 (HTTP), 50051 (gRPC) | 50052 | Document Parser |
| engine-chunker | 8083 (HTTP), 50061 (gRPC) | 50053 | Text Chunker |
| engine-embedder | 8084 (HTTP), 50071 (gRPC) | 50054 | Embedding Generator |
| engine-opensearch-sink | 8085 (HTTP), 50081 (gRPC) | 50055 | Search Indexer |
| engine-test-connector | 8089 (HTTP), 50091 (gRPC) | 50059 | Test Data Generator |

## Control Script Commands

The `yappy-compose.sh` script provides the following commands:

- `up` - Start all services (infrastructure + engines)
- `down` - Stop and remove all services
- `start` - Start stopped services
- `stop` - Stop running services
- `restart` - Restart all services
- `status` - Show service status and URLs
- `logs` - Show logs (use -f to follow)
- `infra` - Start only infrastructure services
- `engines` - Start only engine services (requires infra)
- `test` - Start with test connector enabled
- `clean` - Stop services and remove volumes (CAUTION: deletes data)

## Service URLs

Once running, access the services at:

- **Consul UI**: http://localhost:8500
- **Apicurio Registry**: http://localhost:8081
- **OpenSearch**: http://localhost:9200
- **OpenSearch Dashboards**: http://localhost:5601

Engine REST APIs:
- **Tika Parser**: http://localhost:8082
- **Chunker**: http://localhost:8083
- **Embedder**: http://localhost:8084
- **OpenSearch Sink**: http://localhost:8085

## Configuration

### Environment Variables

Set these before running:
```bash
export DOCKER_REGISTRY=nas:5000  # Your registry
export DOCKER_NAMESPACE=yappy    # Your namespace
export YAPPY_CLUSTER_NAME=prod   # Cluster name
```

### Memory Requirements

The default configuration allocates:
- OpenSearch: 1GB heap
- Embedder: 1-2GB heap (for ML models)
- Other engines: 512MB-1GB heap each

Total recommended system memory: 8GB minimum

### Volumes

Persistent data is stored in Docker volumes:
- `kafka-data` - Kafka message logs
- `consul-data` - Consul KV store and service data
- `opensearch-data` - Indexed documents and search data

## Development Workflow

### 1. Start Infrastructure Only
```bash
# Start infrastructure services
./yappy-compose.sh infra

# Verify services are healthy
./yappy-compose.sh status
```

### 2. Start Engines
```bash
# Start all engines
./yappy-compose.sh engines

# Or start specific engines
docker-compose up -d engine-tika engine-chunker
```

### 3. Test Document Processing
```bash
# Run integration tests
cd integration-tests
./test-connectivity.sh
./test-document-pipeline.sh
```

### 4. Monitor Services
```bash
# Follow all logs
./yappy-compose.sh logs -f

# Follow specific service
./yappy-compose.sh logs -f engine-tika

# Check Consul for registered services
curl http://localhost:8500/v1/catalog/services | jq
```

## Troubleshooting

### Services Not Starting
1. Check ports are not in use: `lsof -i :8500`
2. Verify images exist: `docker images | grep yappy`
3. Check logs: `./yappy-compose.sh logs [service-name]`

### Out of Memory
1. Increase Docker memory allocation
2. Reduce Java heap sizes in environment variables
3. Run fewer services simultaneously

### Network Issues
1. Ensure yappy-network exists: `docker network ls`
2. Check DNS resolution: `docker exec engine-tika nslookup consul`
3. Verify connectivity: `docker exec engine-tika ping consul`

### Clean Start
```bash
# Stop everything and remove volumes
./yappy-compose.sh clean

# Start fresh
./yappy-compose.sh up
```

## Production Considerations

For production deployment:

1. **Security**: Enable authentication for all services
2. **Persistence**: Use external volumes or bind mounts
3. **Scaling**: Use Docker Swarm or Kubernetes
4. **Monitoring**: Add Prometheus and Grafana
5. **Backup**: Implement backup strategies for data volumes

## Next Steps

1. Configure pipelines through Consul
2. Submit documents for processing
3. Query results in OpenSearch
4. Scale horizontally by running multiple engine instances
5. Deploy to Kubernetes for production