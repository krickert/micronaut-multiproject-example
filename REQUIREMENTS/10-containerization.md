# Containerization

## Overview

Containerization strategy for YAPPY modules packages each module with its own embedded engine instance, creating self-contained processing units that can be deployed and scaled independently.

## Architecture

### Container Structure

Each container includes:
1. **Module Implementation** - The business logic (gRPC service)
2. **YAPPY Engine** - Embedded orchestration engine
3. **Configuration** - Module and engine configuration files
4. **Process Manager** - Supervisord for managing both processes

### Design Principles

1. **One Module Per Container** - Single responsibility
2. **Co-located Engine** - Low latency, simplified deployment
3. **Local Communication** - Module and engine communicate via localhost
4. **External Dependencies** - Consul, Kafka accessed over network

## Module Selection for Containerization

### Core Modules
- **Echo Module** - Simple passthrough for testing
- **Chunker Module** - Text chunking and segmentation
- **Tika Parser Module** - Document parsing and extraction
- **Embedder Module** - Vector embedding generation
- **OpenSearch Sink Module** - Search indexing

### Future Modules
- **S3 Connector Module** - AWS S3 integration
- **Web Crawler Connector Module** - Web content ingestion
- **Wikipedia Crawler Connector Module** - Wikipedia data processing

## Container Build Process

### Directory Structure

```
yappy-containers/
├── engine-base/
│   ├── Dockerfile
│   ├── supervisord-base.conf
│   └── scripts/
│       ├── health-check.sh
│       └── startup.sh
├── engine-echo/
│   ├── Dockerfile
│   ├── module-application.yml
│   ├── engine-application.yml
│   └── supervisord.conf
├── engine-chunker/
│   ├── Dockerfile
│   ├── module-application.yml
│   ├── engine-application.yml
│   └── supervisord.conf
└── engine-tika-parser/
    ├── Dockerfile
    ├── module-application.yml
    ├── engine-application.yml
    └── supervisord.conf
```

### Base Dockerfile

```dockerfile
# engine-base/Dockerfile
FROM eclipse-temurin:21-jre-alpine

# Install required packages
RUN apk add --no-cache \
    supervisor \
    curl \
    bash \
    && rm -rf /var/cache/apk/*

# Create app user
RUN addgroup -g 1000 yappy && \
    adduser -D -u 1000 -G yappy yappy

# Create directories
RUN mkdir -p /app/engine /app/module /app/config /app/logs && \
    chown -R yappy:yappy /app

# Copy supervisord base config
COPY supervisord-base.conf /etc/supervisor/conf.d/

# Copy scripts
COPY scripts/* /app/scripts/
RUN chmod +x /app/scripts/*

# Set working directory
WORKDIR /app

# Switch to non-root user
USER yappy

# Default command
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### Java Module Dockerfile

```dockerfile
# engine-echo/Dockerfile
FROM yappy-engine-base:latest

# Copy engine JAR
COPY --chown=yappy:yappy build/libs/yappy-engine-*.jar /app/engine/engine.jar

# Copy module JAR
COPY --chown=yappy:yappy build/libs/echo-module-*.jar /app/module/module.jar

# Copy configuration files
COPY --chown=yappy:yappy module-application.yml /app/config/
COPY --chown=yappy:yappy engine-application.yml /app/config/

# Copy supervisord config
COPY --chown=yappy:yappy supervisord.conf /etc/supervisor/conf.d/

# Environment variables
ENV MICRONAUT_ENVIRONMENTS=docker
ENV MICRONAUT_CONFIG_FILES=/app/config/engine-application.yml
ENV MODULE_CONFIG_FILE=/app/config/module-application.yml

# Expose ports
EXPOSE 8080 50051

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD /app/scripts/health-check.sh || exit 1
```

### Python Module Dockerfile

```dockerfile
# engine-python-module/Dockerfile
FROM yappy-engine-base:latest

# Install Python
RUN apk add --no-cache python3 py3-pip && \
    rm -rf /var/cache/apk/*

# Install Python dependencies
COPY requirements.txt /tmp/
RUN pip3 install --no-cache-dir -r /tmp/requirements.txt && \
    rm /tmp/requirements.txt

# Copy engine JAR
COPY --chown=yappy:yappy build/libs/yappy-engine-*.jar /app/engine/engine.jar

# Copy Python module
COPY --chown=yappy:yappy src/main/python /app/module/

# Copy configuration files
COPY --chown=yappy:yappy module-config.yml /app/config/
COPY --chown=yappy:yappy engine-application.yml /app/config/

# Copy supervisord config
COPY --chown=yappy:yappy supervisord-python.conf /etc/supervisor/conf.d/

# Environment variables
ENV PYTHONUNBUFFERED=1
ENV MODULE_CONFIG_FILE=/app/config/module-config.yml

# Expose ports
EXPOSE 8080 50051
```

## Configuration Management

### Engine Configuration

```yaml
# engine-application.yml
micronaut:
  application:
    name: yappy-engine-echo
  server:
    port: 8080

# Local service discovery
local:
  services:
    ports:
      echo-service: 50051

# Consul configuration (from environment)
consul:
  client:
    host: ${CONSUL_HOST:consul}
    port: ${CONSUL_PORT:8500}

# Kafka configuration (from environment)
kafka:
  bootstrap:
    servers: ${KAFKA_BROKERS:kafka:9092}

# Engine configuration
engine:
  cluster:
    name: ${CLUSTER_NAME:default-cluster}
```

### Module Configuration

```yaml
# module-application.yml
grpc:
  server:
    port: 50051

module:
  name: echo-module
  version: 1.0.0
  health:
    endpoint: /health
```

### Environment Variables

```env
# Infrastructure
CONSUL_HOST=consul.example.com
CONSUL_PORT=8500
CONSUL_ACL_TOKEN=secret-token

KAFKA_BROKERS=kafka1:9092,kafka2:9092
SCHEMA_REGISTRY_URL=http://schema-registry:8081

# Cluster configuration
CLUSTER_NAME=production-cluster

# Module specific
MODULE_LOG_LEVEL=INFO
ENGINE_LOG_LEVEL=DEBUG
```

## Supervisord Configuration

### Base Configuration

```ini
[supervisord]
nodaemon=true
user=yappy
logfile=/app/logs/supervisord.log
pidfile=/app/supervisord.pid

[unix_http_server]
file=/app/supervisor.sock
chmod=0700

[rpcinterface:supervisor]
supervisor.rpcinterface_factory = supervisor.rpcinterface:make_main_rpcinterface

[supervisorctl]
serverurl=unix:///app/supervisor.sock
```

### Module Process Configuration

```ini
[program:module]
command=java -jar /app/module/module.jar
directory=/app/module
user=yappy
autostart=true
autorestart=true
priority=10
stdout_logfile=/app/logs/module.log
stderr_logfile=/app/logs/module.error.log
environment=MICRONAUT_CONFIG_FILES="/app/config/module-application.yml"

[program:engine]
command=java -jar /app/engine/engine.jar
directory=/app/engine
user=yappy
autostart=true
autorestart=true
priority=20
startsecs=10
startretries=3
stdout_logfile=/app/logs/engine.log
stderr_logfile=/app/logs/engine.error.log
environment=MICRONAUT_CONFIG_FILES="/app/config/engine-application.yml"
depends_on=module
```

## Health Checks

### Container Health Check Script

```bash
#!/bin/bash
# health-check.sh

# Check module health
MODULE_HEALTH=$(curl -s http://localhost:50051/health || echo "FAILED")
if [[ "$MODULE_HEALTH" != *"UP"* ]]; then
    echo "Module health check failed"
    exit 1
fi

# Check engine health
ENGINE_HEALTH=$(curl -s http://localhost:8080/health || echo "FAILED")
if [[ "$ENGINE_HEALTH" != *"UP"* ]]; then
    echo "Engine health check failed"
    exit 1
fi

echo "Health check passed"
exit 0
```

### Docker Health Check

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD /app/scripts/health-check.sh || exit 1
```

## Multi-Stage Builds

### Optimized Build Process

```dockerfile
# Build stage
FROM gradle:8.5-jdk21 AS builder

WORKDIR /build

# Copy build files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code
COPY src ./src

# Build application
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Copy JAR from builder
COPY --from=builder /build/build/libs/*.jar /app/
```

## Docker Compose

### Development Environment

```yaml
version: '3.8'

services:
  # Infrastructure
  consul:
    image: consul:1.17
    ports:
      - "8500:8500"
    environment:
      - CONSUL_BIND_INTERFACE=eth0

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      - KAFKA_ZOOKEEPER_CONNECT=zookeeper:2181
      - KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092

  schema-registry:
    image: apicurio/apicurio-registry-sql:2.5.0
    ports:
      - "8081:8080"
    environment:
      - REGISTRY_DATASOURCE_URL=jdbc:h2:mem:registry

  # Modules
  echo-module:
    build: ./yappy-containers/engine-echo
    environment:
      - CONSUL_HOST=consul
      - KAFKA_BROKERS=kafka:9092
      - SCHEMA_REGISTRY_URL=http://schema-registry:8080
    depends_on:
      - consul
      - kafka
      - schema-registry

  chunker-module:
    build: ./yappy-containers/engine-chunker
    environment:
      - CONSUL_HOST=consul
      - KAFKA_BROKERS=kafka:9092
      - SCHEMA_REGISTRY_URL=http://schema-registry:8080
    depends_on:
      - consul
      - kafka
      - schema-registry
```

## Build Automation

### CI/CD Pipeline

```yaml
# .github/workflows/build-containers.yml
name: Build Containers

on:
  push:
    branches: [main]
    paths:
      - 'yappy-containers/**'
      - 'yappy-modules/**'

jobs:
  build:
    strategy:
      matrix:
        module: [echo, chunker, tika-parser]
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Build module
        run: ./gradlew :yappy-modules:${{ matrix.module }}:shadowJar
      
      - name: Build container
        run: |
          docker build -t yappy-${{ matrix.module }}:${{ github.sha }} \
            ./yappy-containers/engine-${{ matrix.module }}
      
      - name: Push to registry
        run: |
          docker tag yappy-${{ matrix.module }}:${{ github.sha }} \
            ${{ secrets.REGISTRY }}/yappy-${{ matrix.module }}:latest
          docker push ${{ secrets.REGISTRY }}/yappy-${{ matrix.module }}:latest
```

## Production Deployment

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: echo-module
spec:
  replicas: 3
  selector:
    matchLabels:
      app: echo-module
  template:
    metadata:
      labels:
        app: echo-module
    spec:
      containers:
      - name: echo-module
        image: registry.example.com/yappy-echo:latest
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 50051
          name: grpc
        env:
        - name: CONSUL_HOST
          value: consul.consul.svc.cluster.local
        - name: KAFKA_BROKERS
          value: kafka-0.kafka:9092,kafka-1.kafka:9092
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 40
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
```

## Best Practices

### Container Optimization

1. **Image Size**
   - Use Alpine base images
   - Multi-stage builds
   - Minimize layers
   - Remove unnecessary files

2. **Security**
   - Run as non-root user
   - Minimal base images
   - Regular security updates
   - Scan for vulnerabilities

3. **Performance**
   - JVM tuning for containers
   - Appropriate resource limits
   - Fast startup times
   - Efficient health checks

### Logging and Monitoring

1. **Structured Logging**
   ```json
   {
     "timestamp": "2024-01-15T10:30:00Z",
     "level": "INFO",
     "module": "echo-module",
     "message": "Processing message",
     "correlation_id": "abc-123"
   }
   ```

2. **Metrics Export**
   - Prometheus metrics endpoint
   - Custom business metrics
   - JVM metrics
   - Container metrics

3. **Distributed Tracing**
   - Correlation ID propagation
   - Span creation
   - Trace context in logs