# YAPPY Container Port Mapping Guide

## Port Assignment Pattern

Each containerized engine uses a consistent port mapping pattern to avoid conflicts and provide clear separation between services.

### Standard Port Ranges

- **HTTP API**: 8080 (engine management API)
- **Engine gRPC**: 50051 (engine's gRPC service)
- **Module gRPC**: 50052+ (incrementing by 1 for each module)

### Module Port Assignments

| Module | gRPC Port | Description |
|--------|-----------|-------------|
| tika-parser | 50052 | Document parsing service |
| chunker | 50053 | Text chunking service |
| embedder | 50054 | Embedding generation service |
| opensearch-sink | 50055 | OpenSearch indexing service |
| s3-connector | 50056 | S3 storage connector |
| web-crawler | 50057 | Web crawling connector |
| wiki-crawler | 50058 | Wikipedia crawling connector |
| test-connector | 50059 | Test/echo connector |

### Container Examples

#### Engine + Tika Parser
```yaml
ports:
  - "8080:8080"   # Engine HTTP API
  - "50051:50051" # Engine gRPC
  - "50052:50052" # Tika module gRPC
```

#### Engine + Chunker
```yaml
ports:
  - "8080:8080"   # Engine HTTP API
  - "50051:50051" # Engine gRPC
  - "50053:50053" # Chunker module gRPC
```

#### Engine + Embedder
```yaml
ports:
  - "8080:8080"   # Engine HTTP API
  - "50051:50051" # Engine gRPC
  - "50054:50054" # Embedder module gRPC
```

### Multi-Module Containers

For containers with multiple modules, continue incrementing:

#### Engine + Tika + Chunker
```yaml
ports:
  - "8080:8080"   # Engine HTTP API
  - "50051:50051" # Engine gRPC
  - "50052:50052" # Tika module gRPC
  - "50053:50053" # Chunker module gRPC
```

### Docker Compose Example

When running multiple engine containers:

```yaml
services:
  engine-tika:
    image: yappy/engine-tika-parser
    ports:
      - "8081:8080"   # Offset HTTP port to avoid conflicts
      - "50051:50051"
      - "50052:50052"
      
  engine-chunker:
    image: yappy/engine-chunker
    ports:
      - "8082:8080"   # Different HTTP port
      - "50061:50051" # Offset engine gRPC to avoid conflicts
      - "50053:50053" # Module port stays the same
      
  engine-embedder:
    image: yappy/engine-embedder
    ports:
      - "8083:8080"
      - "50071:50051"
      - "50054:50054"
```

### Module Registration

Modules register themselves in Consul with their assigned ports:

```json
{
  "ID": "tika-parser-instance-1",
  "Name": "tika-parser",
  "Port": 50052,
  "Tags": ["yappy-module", "document-processor"],
  "Check": {
    "GRPC": "localhost:50052",
    "Interval": "10s"
  }
}
```

### Benefits

1. **Predictability**: Easy to remember which port belongs to which service
2. **No Conflicts**: Each module has its own dedicated port
3. **Scalability**: Clear pattern for adding new modules
4. **Debugging**: Port number immediately identifies the module
5. **Service Discovery**: Consul can reliably map services to ports

### Future Considerations

- Reserve 50060-50099 for custom/experimental modules
- Consider using 51000+ range for sink modules
- Use 52000+ range for connector modules