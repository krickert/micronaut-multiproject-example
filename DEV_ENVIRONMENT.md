# Yappy DEV Environment Setup

## Overview
This document describes how to run the Yappy Engine in development mode without test resources.

## Quick Start
```bash
# Start everything and run the engine
./dev.sh
```

## Components

### 1. Docker Services (`docker-dev/`)
- **Consul** (port 8500) - Configuration and service discovery
- **Kafka** (ports 9092, 9094) - Message broker
- **Apicurio** (port 8080) - Schema registry  
- **OpenSearch** (port 9200) - Search engine
- **Moto** (port 5001) - AWS Glue mock (alternative schema registry)

### 2. Configuration Files
- `application-dev-apicurio.yml` - DEV configuration with Apicurio schema registry
- `application-dev-moto.yml` - (TODO) DEV configuration with Moto/Glue schema registry

### 3. Scripts
- `dev.sh` - Main launcher (starts services if needed, runs engine)
- `docker-dev/start-dev-env.sh` - Start Docker services
- `docker-dev/stop-dev-env.sh` - Stop Docker services
- `docker-dev/status-dev-env.sh` - Check service status
- `docker-dev/bootstrap-consul.sh` - Initialize Consul with cluster config
- `test-dev-environment.sh` - Verify DEV environment setup

### 4. Build Configuration
The `yappy-engine/build.gradle.kts` disables test resources when:
- Running with `-PdisableTestResources=true` (Gradle property)
- Environment variable `DISABLE_TEST_RESOURCES=true` is set
- System property `-Ddisable.test.resources=true` is set

```kotlin
testResources {
    val shouldDisable = project.hasProperty("disableTestResources") ||
                       System.getenv("DISABLE_TEST_RESOURCES") == "true" ||
                       System.getProperty("disable.test.resources") == "true"
    
    enabled.set(!shouldDisable)
}
```

A custom `runDev` task is also available that automatically sets all required properties.

## Manual Steps

### Start Docker Services
```bash
cd docker-dev
./start-dev-env.sh
```

### Bootstrap Consul (first time only)
```bash
cd docker-dev
./bootstrap-consul.sh
```

### Run Engine
```bash
# Option 1: Use the main dev script (RECOMMENDED)
./dev.sh

# Option 2: Use Gradle runDev task
./gradlew :yappy-engine:runDev -PdisableTestResources=true

# Option 3: Use IntelliJ Run Configurations
# - "Yappy Engine DEV Apicurio" - Application run config
# - "Yappy Engine DEV (Gradle)" - Gradle run config with runDev task

# Option 4: Manual Gradle run with flags
DISABLE_TEST_RESOURCES=true ./gradlew :yappy-engine:run \
  -Dmicronaut.environments=dev-apicurio \
  -PdisableTestResources=true
```

## Troubleshooting

### Test Resources Still Starting?
- Ensure `MICRONAUT_ENVIRONMENTS` contains "dev"
- Check that build.gradle.kts has the test resources conditional logic
- Kill any existing test-resources-service processes

### Kafka Connection Issues?
- Verify Kafka is using port 9094 for external connections
- Check `KAFKA_BOOTSTRAP_SERVERS=localhost:9094` is set
- Ensure docker-compose Kafka container is running

### Consul Not Connecting?
- Verify Consul is running on port 8500
- Check if cluster configuration exists: `curl http://localhost:8500/v1/kv/yappy/pipeline-configs/clusters/yappy-cluster?raw`
- Re-run bootstrap script if needed

## Next Steps
1. Module registration implementation (gRPC-based discovery)
2. Create application-dev-moto.yml for Glue/Moto mode
3. Implement remaining admin APIs
4. Add module health checks and status aggregation