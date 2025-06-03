# YAPPY Containers

This project contains the containerization configurations for YAPPY Engine and its modules.

## Structure

- `engine-base/` - Base engine container configuration (optional, for shared configs)
- `engine-tika-parser/` - Engine + Tika Parser module
- `engine-chunker/` - Engine + Chunker module  
- `engine-embedder/` - Engine + Embedder module
- `test-connector/` - Standalone test connector service
- `engine-opensearch-sink/` - Engine + OpenSearch Sink module

## Prerequisites

1. Docker installed and running
2. Java 21
3. Gradle

## Setup

### Option 1: Using Local Docker Registry

1. Set up local Docker registry:
   ```bash
   ./setup-local-registry.sh
   ```

2. Build all containers:
   ```bash
   ./gradlew buildAllContainers
   ```

3. Push to local registry:
   ```bash
   ./gradlew pushAllContainers
   ```

### Option 2: Using External Registry (e.g., NAS)

1. Configure your registry settings:
   ```bash
   # Copy the example environment file
   cp .env.example .env
   
   # Edit .env with your registry details
   # For example, for a NAS registry:
   # DOCKER_REGISTRY_HOST=nas.local
   # DOCKER_REGISTRY_PORT=5000
   # START_LOCAL_REGISTRY=false
   ```

2. Set environment variables:
   ```bash
   export DOCKER_REGISTRY_HOST=nas.local
   export DOCKER_REGISTRY_PORT=5000
   export START_LOCAL_REGISTRY=false
   
   # Or source the .env file
   export $(cat .env | xargs)
   ```

3. Run the setup script (it will skip local registry creation):
   ```bash
   ./setup-local-registry.sh
   ```

4. Build and push containers:
   ```bash
   # Build with custom registry
   export DOCKER_REGISTRY=nas.local:5000
   ./gradlew buildAllContainers
   
   # Push to custom registry
   ./gradlew pushAllContainers
   ```

## Building Individual Containers

To build a specific container:
```bash
./gradlew :engine-tika-parser:dockerBuild
```

To push a specific container:
```bash
./gradlew :engine-tika-parser:dockerPush
```

## Native Images

To build native images (requires GraalVM):
```bash
./gradlew :engine-tika-parser:dockerBuildNative
```

## Configuration

Each container can be configured via environment variables:

### Common Variables
- `CONSUL_HOST` - Consul host (default: localhost)
- `CONSUL_PORT` - Consul port (default: 8500)
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka servers (default: localhost:9092)
- `APICURIO_REGISTRY_URL` - Schema registry URL (default: http://localhost:8080)
- `YAPPY_CLUSTER_NAME` - Cluster name (default: yappy-cluster)

### Module-Specific Variables
See each module's `application.yml` for specific configuration options.

## Development

To test containers locally:
```bash
docker run -it --rm \
  -e CONSUL_HOST=consul \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  --network yappy-network \
  localhost:5000/yappy/engine-tika-parser:latest
```

## CI/CD

Containers are automatically built and published via GitHub Actions on:
- Push to main/develop branches
- Creation of version tags (v*)