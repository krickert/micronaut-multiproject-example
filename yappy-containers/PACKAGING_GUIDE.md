# YAPPY Container Packaging Guide

## Overview

YAPPY containers package an engine instance with one or more co-located modules. This design allows:
- Modules in any language (Java, Python, Go, etc.)
- Process isolation between engine and modules
- Shared container resources
- Simplified deployment

## Architecture

Each container runs:
1. **YAPPY Engine** - The routing orchestrator
2. **Module(s)** - Processing services (Tika, Chunker, Embedder, etc.)
3. **Supervisor** - Process manager to handle both

```
Container
├── Supervisor (PID 1)
│   ├── Engine Process (Java)
│   └── Module Process (Any language)
```

## Container Structure

### Base Structure
```
yappy-containers/
├── engine-base/                # Base image with supervisor
├── engine-tika-parser/         # Engine + Tika Parser
├── engine-chunker/             # Engine + Chunker
├── engine-embedder/            # Engine + Embedder
└── engine-opensearch-sink/     # Engine + OpenSearch Sink
```

### Key Files per Container

1. **Dockerfile** - Multi-stage build
2. **supervisord.conf** - Process management
3. **application.yml** - Engine configuration
4. **module-application.yml** - Module configuration
5. **build.gradle.kts** - Docker build tasks

## Creating a New Engine+Module Container

### 1. Create the Container Directory
```bash
mkdir -p yappy-containers/engine-my-module/src/main/resources
```

### 2. Create Dockerfile
```dockerfile
# Multi-stage build for engine+module

# Stage 1: Build the engine
FROM gradle:8.10-jdk21 AS engine-build
WORKDIR /build
COPY yappy-engine /build/yappy-engine
COPY yappy-models /build/yappy-models
COPY yappy-consul-config /build/yappy-consul-config
COPY build.gradle.kts settings.gradle.kts gradle.properties /build/
COPY gradle /build/gradle
RUN gradle :yappy-engine:shadowJar --no-daemon

# Stage 2: Build the module (Java example)
FROM gradle:8.10-jdk21 AS module-build
WORKDIR /build
COPY yappy-modules/my-module /build/yappy-modules/my-module
COPY yappy-models /build/yappy-models
COPY build.gradle.kts settings.gradle.kts gradle.properties /build/
COPY gradle /build/gradle
RUN gradle :yappy-modules:my-module:shadowJar --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache supervisor bash curl wget
RUN adduser -D -s /bin/bash appuser
RUN mkdir -p /app/engine /app/modules /var/log/supervisor /var/run && \
    chown -R appuser:appuser /app /var/log/supervisor

COPY --from=engine-build /build/yappy-engine/build/libs/*-all.jar /app/engine/engine.jar
COPY --from=module-build /build/yappy-modules/my-module/build/libs/*-all.jar /app/modules/my-module.jar

COPY engine-my-module/src/main/resources/supervisord.conf /etc/supervisor/conf.d/
COPY engine-my-module/src/main/resources/application.yml /app/engine/
COPY engine-my-module/src/main/resources/module-application.yml /app/modules/

EXPOSE 8080 50051 50052

USER root
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### 3. Create supervisord.conf
```ini
[supervisord]
nodaemon=true
user=root
logfile=/var/log/supervisor/supervisord.log
pidfile=/var/run/supervisord.pid

[program:engine]
command=java -Dconfig.file=/app/engine/application.yml -jar /app/engine/engine.jar
directory=/app/engine
autostart=true
autorestart=true
startretries=3
user=appuser
environment=YAPPY_ENGINE_NAME="yappy-engine-my-module",CONSUL_HOST="%(ENV_CONSUL_HOST)s"
stdout_logfile=/var/log/supervisor/engine.log
stderr_logfile=/var/log/supervisor/engine.err.log
priority=10

[program:my-module]
command=java -Dconfig.file=/app/modules/module-application.yml -jar /app/modules/my-module.jar
directory=/app/modules
autostart=true
autorestart=true
startretries=3
user=appuser
environment=GRPC_SERVER_PORT="50052"
stdout_logfile=/var/log/supervisor/my-module.log
stderr_logfile=/var/log/supervisor/my-module.err.log
priority=20
```

### 4. Create build.gradle.kts
```kotlin
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

plugins {
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

val dockerRegistry: String by project
val dockerNamespace: String by project

tasks.register<DockerBuildImage>("dockerBuild") {
    group = "docker"
    description = "Build Docker image for engine-my-module"
    
    inputDir.set(rootProject.projectDir)
    dockerFile.set(file("${projectDir}/Dockerfile"))
    images.add("${dockerRegistry}/${dockerNamespace}/engine-my-module:${version}")
    images.add("${dockerRegistry}/${dockerNamespace}/engine-my-module:latest")
}
```

## Python Module Example

For Python modules, adjust the Dockerfile:

```dockerfile
# Stage 2: Python module
FROM python:3.11-slim AS module-build
WORKDIR /app
COPY yappy-modules/python-nlp/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY yappy-modules/python-nlp/ .

# Stage 3: Runtime with Python
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache supervisor bash curl wget python3 py3-pip
# ... rest of setup ...

COPY --from=module-build /app /app/modules/python-nlp
```

And supervisord.conf:
```ini
[program:python-nlp]
command=python3 /app/modules/python-nlp/main.py
directory=/app/modules/python-nlp
environment=GRPC_SERVER_PORT="50052"
```

## Building and Running

### Build Container
```bash
cd yappy-containers
./gradlew :engine-tika-parser:dockerBuild
```

### Run with Docker Compose
```bash
docker-compose -f docker-compose.test.yml up engine-tika
```

### Run Standalone
```bash
docker run -d \
  -e CONSUL_HOST=consul \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -p 8080:8080 \
  -p 50051:50051 \
  -p 50052:50052 \
  localhost:5000/yappy/engine-tika-parser:latest
```

## Configuration

### Engine Configuration (application.yml)
- Consul connection
- Kafka connection  
- Schema registry
- Which modules are co-located
- Cluster membership

### Module Configuration (module-application.yml)
- gRPC port
- Module-specific settings
- No infrastructure awareness

## Best Practices

1. **Module Independence**: Modules should not know about Consul, Kafka, or engine details
2. **Port Management**: Use consistent port assignments (50052+ for modules)
3. **Health Checks**: Engine monitors module health and reports to Consul
4. **Logging**: Separate log files for engine and module via supervisor
5. **Resource Limits**: Set memory/CPU limits appropriate for both processes

## Troubleshooting

### View Logs
```bash
docker exec <container> tail -f /var/log/supervisor/engine.log
docker exec <container> tail -f /var/log/supervisor/tika-parser.log
```

### Check Process Status
```bash
docker exec <container> supervisorctl status
```

### Restart a Process
```bash
docker exec <container> supervisorctl restart engine
docker exec <container> supervisorctl restart tika-parser
```