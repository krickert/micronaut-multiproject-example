# Engine Core Integration Tests

This directory contains integration tests for the Engine Core module that demonstrate the use of Micronaut Test Resources.

## Test Structure

### Integration Tests

1. **EngineIntegrationTest** - Basic integration test that verifies:
   - Application context starts successfully
   - Consul properties are injected by test resources
   - Kafka properties are injected by test resources
   - Core beans (ServiceDiscovery, PipelineEngine, MessageRouter) are available
   - Test environment is properly configured

2. **EngineTestResourcesTest** - Advanced integration test that demonstrates:
   - Direct Kafka connectivity testing using AdminClient
   - Kafka producer client usage
   - Consul endpoint configuration verification
   - Multiple test resources working together

### Unit Tests

1. **MockServiceDiscoveryTest** - Unit test for the MockServiceDiscovery implementation

## Running the Tests

### From Command Line

```bash
# Run all tests in the engine-core module
./gradlew :yappy-engine:engine-core:test

# Run only integration tests
./gradlew :yappy-engine:engine-core:test --tests "*IntegrationTest"

# Run a specific test class
./gradlew :yappy-engine:engine-core:test --tests "EngineIntegrationTest"

# Run with debug output
./gradlew :yappy-engine:engine-core:test --debug
```

### From IDE

Simply run the test classes as JUnit tests. The Micronaut test resources will automatically start the required containers.

## Test Resources

The following test resources are automatically started when running tests:

1. **Consul** - Provided by `consul-test-resource`
   - Provides service discovery and configuration management
   - Accessible via properties: `consul.client.host` and `consul.client.port`

2. **Kafka** - Provided by `apache-kafka-test-resource`
   - Provides message broker functionality
   - Accessible via property: `kafka.bootstrap.servers`

## Configuration

- **application-test.yml** - Test-specific configuration
- **logback-test.xml** - Test logging configuration

## Troubleshooting

### Tests Fail to Start

1. Ensure Docker is running (required for Testcontainers)
2. Check that ports are not already in use
3. Verify network connectivity to Docker Hub

### Slow Test Startup

The first run may be slow as Docker images are downloaded. Subsequent runs will be faster.

### Debug Test Resources

Enable debug logging in `logback-test.xml`:
```xml
<logger name="io.micronaut.testresources" level="DEBUG"/>
<logger name="org.testcontainers" level="DEBUG"/>
```

## Best Practices

1. Use `@MicronautTest` annotation for integration tests
2. Inject configuration properties using `@Property` annotation
3. Use `@Timeout` annotation for tests that interact with external resources
4. Clean up resources in `@AfterEach` methods when necessary
5. Use meaningful assertions with descriptive messages