# YAPPY Container Integration Tests

This project provides integration tests for YAPPY containerized engines and modules.

## Overview

The integration tests validate:
- Container startup and health
- Service registration with Consul
- Pipeline configuration management
- Document processing through gRPC
- Kafka message routing
- Module discovery and communication

## Prerequisites

1. Docker and Docker Compose installed
2. Built container images in local registry
3. JDK 21+

## Project Structure

```
yappy-container-integration-tests/
├── build.gradle.kts              # Gradle build configuration
├── docker-compose.test.yml       # Test environment setup
├── src/test/java/
│   ├── BaseContainerIntegrationTest.java    # Base test class
│   ├── EngineRegistrationTest.java          # Registration tests
│   └── PipelineProcessingTest.java          # Processing tests
└── src/test/resources/
    └── logback-test.xml         # Test logging configuration
```

## Running Tests

### Build Required Containers

Before running tests, ensure containers are built:

```bash
cd ../yappy-containers
./build-engine-tika.sh
# Build other containers as needed
```

### Run All Tests

```bash
./gradlew test
```

### Run Specific Test Class

```bash
./gradlew test --tests "EngineRegistrationTest"
./gradlew test --tests "PipelineProcessingTest"
```

### Debug Mode

For more verbose output:

```bash
./gradlew test --info --stacktrace
```

## Test Environment

The test environment includes:
- **Consul**: Service discovery and configuration
- **Kafka**: Message broker
- **Apicurio**: Schema registry
- **Engine+Tika**: Containerized engine with Tika module

All services are managed by Testcontainers and start automatically.

## Writing New Tests

### 1. Extend BaseContainerIntegrationTest

```java
public class MyNewTest extends BaseContainerIntegrationTest {
    // Your test methods
}
```

### 2. Use Helper Methods

The base class provides helper methods for service endpoints:

```java
// Get service URLs
String consulUrl = getConsulUrl();
String kafkaBootstrap = getKafkaBootstrapServers();

// Get specific ports
Integer engineGrpcPort = getEngineGrpcPort();
Integer tikaGrpcPort = getTikaGrpcPort();
```

### 3. Test Pattern

```java
@Test
@DisplayName("Should do something specific")
void testFeature() {
    // Setup
    
    // Execute
    
    // Verify with Awaitility for async operations
    await().atMost(30, SECONDS)
        .untilAsserted(() -> {
            // Assertions
        });
}
```

## Test Categories

### 1. Registration Tests
- Engine registers with Consul
- Module registration by engine
- Health check endpoints
- Default configuration seeding

### 2. Processing Tests
- gRPC document processing
- Kafka message routing
- Custom topic names
- Error handling

### 3. Configuration Tests (TODO)
- Dynamic configuration updates
- Pipeline modifications
- Module enable/disable

### 4. Multi-Container Tests (TODO)
- Multiple engines
- Cross-engine communication
- Load balancing

## Troubleshooting

### Container Startup Issues

Check container logs:
```bash
docker-compose -f docker-compose.test.yml logs engine-tika
```

### Test Timeout Issues

Increase timeout in BaseContainerIntegrationTest:
```java
.withStartupTimeout(Duration.ofMinutes(10))
```

### Network Issues

Ensure Docker network exists:
```bash
docker network ls | grep test-network
```

## Adding New Containers

1. Build the container in `yappy-containers/`
2. Add to `docker-compose.test.yml`
3. Update `BaseContainerIntegrationTest` with new service constants
4. Create specific test classes

## CI/CD Integration

For CI environments:

```bash
# Use specific registry
./gradlew test -Ddocker.registry=myregistry.com:5000

# Disable interactive mode
./gradlew test --no-daemon --console=plain
```

## Future Enhancements

1. **Performance Tests**: Throughput and latency measurements
2. **Chaos Tests**: Failure injection and recovery
3. **Scale Tests**: Multiple container instances
4. **Security Tests**: Authentication and authorization