# YAPPY Engine with Tika Parser Module

This container packages the YAPPY Engine with the Tika Parser module, running as two separate JVMs managed by supervisord.

## Architecture

```
┌─────────────────────────────────────┐
│       Docker Container              │
│                                     │
│  ┌─────────────────┐  ┌──────────┐ │
│  │  Tika Parser    │  │  Engine  │ │
│  │  (JVM 1)        │  │  (JVM 2) │ │
│  │  Port: 50053    │  │  Ports:  │ │
│  │  gRPC Service   │  │  - 8080  │ │
│  └────────┬────────┘  │  - 50051 │ │
│           │           └─────┬────┘ │
│           │                 │      │
│           └─────────────────┘      │
│              supervisord            │
└─────────────────────────────────────┘
```

## Build Process

The build uses Micronaut's Docker plugin with custom extensions:

1. **Module JAR Fetching**: The build fetches the Tika Parser shadow JAR as a Gradle dependency
2. **Layered Build**: Micronaut creates optimized Docker layers for caching
3. **Supervisord Integration**: Manages both JVMs with proper restart policies

### Building

```bash
# From project root
./gradlew :yappy-containers:engine-tika-parser:dockerBuild
```

Or use the convenience script:
```bash
./build-and-test.sh
```

## Configuration

### Environment Variables

#### Engine Configuration
- `YAPPY_ENGINE_NAME`: Engine instance name (default: `yappy-engine-tika-parser`)
- `YAPPY_CLUSTER_NAME`: Cluster to join (default: `default-cluster`)
- `CONSUL_HOST`: Consul hostname (default: `localhost`)
- `CONSUL_PORT`: Consul port (default: `8500`)
- `CONSUL_ENABLED`: Enable Consul (default: `true`)
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka brokers (default: `localhost:9092`)
- `KAFKA_ENABLED`: Enable Kafka (default: `true`)
- `APICURIO_REGISTRY_URL`: Schema registry URL (default: `http://localhost:8080`)
- `SCHEMA_REGISTRY_TYPE`: Registry type (default: `apicurio`)

#### Tika Parser Configuration
- `TIKA_MAX_FILE_SIZE`: Maximum file size in bytes (default: `52428800` - 50MB)
- `TIKA_TIMEOUT_MS`: Processing timeout in milliseconds (default: `60000` - 60s)
- `TIKA_EXTRACT_METADATA`: Extract file metadata (default: `true`)
- `TIKA_EXTRACT_CONTENT`: Extract text content (default: `true`)
- `TIKA_DETECT_LANGUAGE`: Detect document language (default: `true`)

### Local Service Discovery

The engine is configured to find the Tika Parser module on localhost:

```yaml
local:
  services:
    ports:
      tika-parser: 50053
```

### Health Check Backoff

The engine implements a backoff strategy when health-checking the local module:

1. First 5 attempts: Every 5 seconds
2. Next 5 attempts: Every 10 seconds
3. Next 5 attempts: Every 30 seconds
4. Next 5 attempts: Every 60 seconds
5. Then: Every 5 minutes

## Running

### Standalone Docker

```bash
docker run -p 8080:8080 -p 50051:50051 -p 50053:50053 \
  -e YAPPY_CLUSTER_NAME=my-cluster \
  -e CONSUL_HOST=consul.example.com \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka.example.com:9092 \
  yappy/engine-tika-parser:latest
```

### Docker Compose

See `docker-compose.test.yml` for a complete test environment with all dependencies.

```bash
docker-compose -f docker-compose.test.yml up
```

## Ports

- `8080`: Engine HTTP API and health endpoint
- `50051`: Engine gRPC service
- `50053`: Tika Parser gRPC service

## Health Checks

- Engine health: `http://localhost:8080/health`
- Tika Parser: gRPC health check on port 50053

## Supervisord Management

To interact with the processes inside the container:

```bash
# Check process status
docker exec <container-id> supervisorctl status

# Restart a process
docker exec <container-id> supervisorctl restart tika-parser
docker exec <container-id> supervisorctl restart engine

# View logs
docker exec <container-id> tail -f /var/log/supervisor/tika-parser.log
docker exec <container-id> tail -f /var/log/supervisor/engine.log
```

## Extending for Other Languages

This pattern supports modules in any language:

- **Java**: Current implementation
- **Python**: Replace module JAR with Python script
- **Go**: Replace module JAR with compiled binary
- **Any gRPC-capable language**: As long as it implements the PipeStepProcessor interface

The supervisord configuration would be adjusted accordingly.

## Troubleshooting

### Module Not Starting
1. Check supervisord logs: `docker logs <container-id>`
2. Check module logs: `docker exec <container-id> cat /var/log/supervisor/tika-parser.err.log`
3. Verify port 50053 is available inside container

### Engine Can't Find Module
1. Verify local service configuration in application.yml
2. Check that module is listening on expected port
3. Review engine logs for connection attempts

### Out of Memory
Adjust JVM heap sizes in supervisord.conf:
- Module: `-Xmx512m` (default)
- Engine: Inherits from `JAVA_OPTS` environment variable