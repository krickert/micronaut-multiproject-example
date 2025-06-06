# YAPPY Engine with Tika Parser Module

This container packages the YAPPY Engine with the Tika Parser module, running as two separate JVMs managed by `supervisord`. This architecture allows for independent resource management and process isolation while packaging a core service and its tightly coupled dependency into a single, deployable unit.

## Architecture

The container uses `supervisord` to launch and manage two independent Java processes.

```
┌──────────────────────────────────────┐
│       Docker Container               │
│                                      │
│  ┌───────────────────┐  ┌──────────┐ │
│  │  Tika Parser      │  │  Engine  │ │
│  │  (JVM 1)          │  │  (JVM 2) │ │
│  │  gRPC Port: 50053 │  │  Ports:  │ │
│  └────────┬──────────┘  │  - 8080  │ │
│           │             │  - 50051 │ │
│           │             └─────┬────┘ │
│           └───────────────────┘      │
│              supervisord             │
└──────────────────────────────────────┘
```

## Build Process

The build is orchestrated by Gradle and uses the Micronaut Application plugin to create an optimized Docker image.

1.  **Dependency Assembly**: A custom Gradle task (`prepareDockerContext`) builds and collects the fat JARs for both the `:yappy-engine` and `:yappy-modules:tika-parser` projects.
2.  **Configuration Assembly**: The same task copies the necessary configuration (`supervisord.conf`, `start.sh`, `application.yml`) from their single source of truth locations (`src/main/docker` and `src/main/resources`) into the Docker build context.
3.  **Layered Image Build**: Micronaut creates an efficient Docker image with optimized layers for caching, using a custom `Dockerfile` that sets up `supervisord` and the application JARs.

### Building

Because the build script correctly defines task dependencies, you can build the entire image from the project root with a single command:

```bash
# From the project root directory
./gradlew :yappy-containers:engine-tika-parser:dockerBuild
```
*(See the "Simpler Build Command" section below for how to make this even easier.)*


## Configuration

### Environment Variables

The container's behavior is controlled via environment variables passed in the `docker run` command or from Testcontainers.

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

## Running

### Standalone Docker

```bash
docker run -p 8080:8080 -p 50051:50051 -p 50053:50053 \
  -e YAPPY_CLUSTER_NAME=my-cluster \
  -e CONSUL_HOST=host.docker.internal \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  engine-tika-parser:latest
```

### Docker Compose

See `docker-compose.test.yml` for an example of running a complete test environment with all dependencies.

```bash
docker-compose -f docker-compose.test.yml up
```

## Ports

- **8080**: Engine HTTP API and health endpoint
- **50051**: Engine gRPC service
- **50053**: Tika Parser gRPC service

## Health Checks & Logging

- **Engine health:** `http://localhost:8080/health`
- **Tika Parser health:** Monitored internally via gRPC health check from the Engine.

The `supervisord` configuration has been updated to stream all application logs directly to the container's standard output. This makes debugging much easier.

```bash
# View the combined, real-time logs from both JVMs
docker logs -f <container-id>
```

## Supervisord Management

You can still interact with the `supervisorctl` command to manage the processes inside a running container.

```bash
# Check process status
docker exec <container-id> supervisorctl status

# Restart a specific process
docker exec <container-id> supervisorctl restart tika-parser
docker exec <container-id> supervisorctl restart engine
```

## Troubleshooting

- **Container Exits Immediately:** Use `docker logs <container-id>` to see the full output from both the engine and the module. The error message or stack trace should be visible there.
- **Engine Can't Find Module:** Verify the module is listening on port 50053 by checking the logs from the `tika-parser` process. Review engine logs for connection attempts.
- **Out of Memory:** Adjust the `-Xmx` heap sizes in your `supervisord.conf` file and rebuild the image.

## Shortcuts

From the project's root directory, simply run:

```bash
./gradlew dockerBuild
```

Gradle will automatically figure out the entire dependency chain, build the `tika-parser` JAR, build the `engine` JAR, and then build the final `engine-tika-parser` Docker image, all from that one command. This is the idiomatic Gradle way to handle complex, multi-project builds. 