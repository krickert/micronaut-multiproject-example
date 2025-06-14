# YAPPY Development Environment

This guide helps you set up a local development environment for YAPPY Engine.

## Prerequisites

- Docker and Docker Compose
- Java 21
- Gradle

## Quick Start

1. **Start the infrastructure services:**
   ```bash
   ./start-dev-env.sh
   ```
   This starts:
   - Consul (service discovery) on port 8500
   - Kafka (messaging) on port 9092
   - Apicurio Registry (schema registry) on port 8080
   - LocalStack (AWS services) on port 4566
   - All module containers (Tika, Chunker, Embedder, Echo, Test Module)

2. **Verify services are running:**
   ```bash
   ./test-dev-env.sh
   ```

3. **Set up Consul with test data:**
   ```bash
   ./setup-consul-data.sh
   ```

4. **Run the engine locally:**
   ```bash
   ./run-engine-local.sh
   ```
   
   Or in debug mode:
   ```bash
   ./run-engine-local.sh --debug
   ```

## Service URLs

- **Consul UI**: http://localhost:8500
- **Apicurio Registry**: http://localhost:8080
- **LocalStack (AWS)**: http://localhost:4566
- **Engine HTTP**: http://localhost:8090
- **Engine gRPC**: localhost:50070

## Module Ports

### gRPC Ports
- **Tika Parser**: localhost:50051
- **Chunker**: localhost:50052
- **Embedder**: localhost:50053
- **Echo**: localhost:50054
- **Test Module**: localhost:50062

### HTTP Ports
- **Tika Parser**: http://localhost:8081
- **Chunker**: http://localhost:8082
- **Embedder**: http://localhost:8083
- **Echo**: http://localhost:8084
- **Test Module**: http://localhost:8085

### Debug Ports (JDWP)
- **Engine**: localhost:5000 (when run with `--debug`)
- **Tika Parser**: localhost:5005
- **Chunker**: localhost:5006
- **Embedder**: localhost:5007
- **Echo**: localhost:5008
- **Test Module**: localhost:5009

## Environment Variables

The engine requires these environment variables (set automatically by `run-engine-local.sh`):

```bash
MICRONAUT_ENVIRONMENTS=dev
CONSUL_CLIENT_HOST=localhost
CONSUL_CLIENT_PORT=8500
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
KAFKA_ENABLED=true
APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3
AWS_ENDPOINT=http://localhost:4566
APP_CONFIG_CLUSTER_NAME=dev-cluster
```

## Testing the Pipeline

1. Create a Kafka topic for input:
   ```bash
   docker exec -it yappy-kafka-1 kafka-topics \
     --create --topic input-documents \
     --bootstrap-server localhost:9092 \
     --partitions 3 --replication-factor 1
   ```

2. Send a test message (use a Kafka producer or the engine's API)

3. Monitor output topics to see the pipeline in action

## Stopping Services

To stop all services:
```bash
docker-compose -f docker-compose.dev.yml down
```

## Troubleshooting

### Engine won't start
- Check that all required services are running: `./test-dev-env.sh`
- Verify Consul has configuration data: http://localhost:8500/ui/dc1/kv
- Check logs: `docker-compose -f docker-compose.dev.yml logs -f`

### Module not found
- Ensure modules are registered in Consul: http://localhost:8500/ui/dc1/services
- Check module health endpoints (ports 8081-8085)

### Kafka connection issues
- Verify Kafka is running: `docker-compose -f docker-compose.dev.yml ps kafka`
- Check Kafka logs: `docker-compose -f docker-compose.dev.yml logs kafka`

## Development Workflow

1. Make changes to engine code
2. Run `./gradlew :yappy-engine:build` to compile
3. Start engine with `./run-engine-local.sh`
4. Test your changes
5. When ready, build Docker image: `./gradlew :yappy-engine:dockerBuild`

## Debugging

All services are configured to run with JDWP debug ports exposed:

### Debugging the Engine
```bash
# Start engine in debug mode
./run-engine-local.sh --debug

# Or use gradle directly with debug
./gradlew :yappy-engine:run -Ddebug=true
```

### Debugging Module Containers
Module containers automatically start with debug ports exposed:
- Connect your IDE debugger to the appropriate port (5005-5009)
- Set breakpoints in the module code
- The containers start with `suspend=n` so they run normally until a debugger connects

### IntelliJ IDEA Debug Configuration
1. Go to Run → Edit Configurations
2. Add a new "Remote JVM Debug" configuration
3. Set the host to `localhost`
4. Set the port to the service's debug port (5000-5009)
5. Click Debug to connect

### VS Code Debug Configuration
Add to `.vscode/launch.json`:
```json
{
  "type": "java",
  "name": "Debug Engine",
  "request": "attach",
  "hostName": "localhost",
  "port": 5000
}

## Using the Module Registration CLI

To register modules manually:
```bash
# Build the CLI
./gradlew :yappy-module-registration:build

# Register a module
java -jar yappy-module-registration/build/libs/yappy-module-registration-*.jar \
  register-module \
  --consul-host localhost \
  --consul-port 8500 \
  --module-id tika \
  --module-host localhost \
  --module-port 50051 \
  --health-endpoint http://localhost:8081/health
```