# YAPPY Container Build Status

## Container Images

| Container | Module | Module Port | Build Status | Notes |
|-----------|--------|-------------|--------------|-------|
| engine-tika-parser | Tika Parser | 50052 | ✅ Built & Tested | Document parsing |
| engine-chunker | Chunker | 50053 | ✅ Built | Text chunking |
| engine-embedder | Embedder | 50054 | ✅ Built | Embedding generation |
| engine-opensearch-sink | OpenSearch Sink | 50055 | 🔨 Ready to build | Search indexing |
| engine-test-connector | Test Connector | 50059 | 🔨 Ready to build | Testing/debugging |

## Build Commands

### Individual Builds
```bash
./build-engine-tika.sh          # ✅ Working
./build-engine-chunker.sh       # ✅ Working
./build-engine-embedder.sh      # ✅ Working
./build-engine-opensearch-sink.sh  # Ready
./build-engine-test-connector.sh   # Ready
```

### Build All
```bash
./build-all-containers.sh
```

## Running Containers

### Standalone Mode
```bash
# Engine + Tika Parser (port 50052)
docker run -d --name engine-tika \
  -e CONSUL_HOST=consul-dev \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -p 8081:8080 -p 50051:50051 -p 50052:50052 \
  localhost:5000/yappy/engine-tika-parser:latest

# Engine + Chunker (port 50053)
docker run -d --name engine-chunker \
  -e CONSUL_HOST=consul-dev \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -p 8082:8080 -p 50061:50051 -p 50053:50053 \
  localhost:5000/yappy/engine-chunker:latest

# Engine + Embedder (port 50054)
docker run -d --name engine-embedder \
  -e CONSUL_HOST=consul-dev \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -p 8083:8080 -p 50071:50051 -p 50054:50054 \
  localhost:5000/yappy/engine-embedder:latest
```

### Docker Compose Mode
See `docker-compose.yml` for full orchestration.

## Container Architecture

Each container includes:
1. **Supervisor** - Process manager (PID 1)
2. **YAPPY Engine** - Routing orchestrator (port 50051)
3. **Module** - Processing service (ports 50052+)

## Port Allocation

### Standard Ports
- **8080**: Engine HTTP API
- **50051**: Engine gRPC service

### Module Ports (Incremental)
- **50052**: Tika Parser
- **50053**: Chunker
- **50054**: Embedder
- **50055**: OpenSearch Sink
- **50056**: S3 Connector (future)
- **50057**: Web Crawler (future)
- **50058**: Wiki Crawler (future)
- **50059**: Test Connector

## Health Checks

All containers support:
- HTTP health: `http://localhost:8080/health`
- gRPC health: Engine and module ports
- Supervisor status: `docker exec <container> supervisorctl status`

## Next Steps

1. ✅ Build remaining containers (opensearch-sink, test-connector)
2. ⏳ Create multi-module containers (e.g., engine-tika-chunker)
3. ⏳ Production docker-compose.yml
4. ⏳ Kubernetes manifests
5. ⏳ CI/CD pipeline integration

## Troubleshooting

### View Logs
```bash
# All logs
docker logs <container-name>

# Engine logs
docker exec <container> tail -f /var/log/supervisor/engine.log

# Module logs
docker exec <container> tail -f /var/log/supervisor/<module>.log
```

### Check Processes
```bash
docker exec <container> ps aux
docker exec <container> supervisorctl status
```

### Test gRPC
```bash
# Using grpcurl
grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check
grpcurl -plaintext localhost:50052 grpc.health.v1.Health/Check
```