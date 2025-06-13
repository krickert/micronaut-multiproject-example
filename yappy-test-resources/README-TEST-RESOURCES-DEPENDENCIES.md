# Test Resources Dependencies Documentation

## Problem: Container Start Order

When using Micronaut Test Resources with containers that depend on other containers, you may encounter issues where a container tries to start before its dependencies are ready.

### Example Scenario
The Yappy Engine container depends on:
- Consul (for configuration)
- Kafka (for messaging)
- Apicurio Registry (for schema registry)
- Moto/LocalStack (for AWS services)

Without proper dependency declaration, the engine container would try to start immediately when `engine.grpc.host` is requested, leading to connection failures.

## Solution: Implement getRequiredProperties()

Test resource providers must implement the `getRequiredProperties(String expression)` method to declare their dependencies.

### Implementation Example

```java
@Override
public List<String> getRequiredProperties(String expression) {
    // The engine container depends on these services being available
    // By declaring these as required properties, the test resources framework
    // will ensure these containers are started BEFORE attempting to create
    // the engine container. This prevents connection failures during startup.
    if (expression.startsWith("engine.")) {
        logger.info("Engine property {} requested - declaring required dependencies", expression);
        return Arrays.asList(
            "consul.client.host",        // Consul must be running first
            "consul.client.port",    
            "kafka.bootstrap.servers",   // Kafka must be running first
            "apicurio.registry.url",     // Apicurio registry must be running
            "aws.endpoint"               // Moto/LocalStack must be running
        );
    }
    return Collections.emptyList();
}
```

## How It Works

1. When a test requests a property (e.g., `engine.grpc.host`), the test resources framework calls `getRequiredProperties()` on the provider
2. The provider returns a list of properties it needs before it can start
3. The framework recursively resolves those properties first, starting their containers
4. Only after all dependencies are satisfied does the framework call `createContainer()` on the original provider

## Important Notes

1. **Shared Server Mode**: When using `sharedServer = true`, ensure the first test that starts the server has all necessary providers on the classpath
2. **Container Networking**: Containers should use Docker network aliases (e.g., `kafka:9092`) not localhost addresses
3. **Disable Test Resources Client**: Containers that include Micronaut should set `MICRONAUT_TEST_RESOURCES_ENABLED=false` to prevent recursive property resolution

## Debugging Tips

1. Check available properties: `curl -H "Access-Token: <token>" http://localhost:<port>/list`
2. Check running containers: `docker ps`
3. Look for connection attempts to localhost in container logs - this indicates the container is not using the correct network aliases
4. Ensure all required test resources are in `testResourcesImplementation` in build.gradle.kts
5. Integration tests should NOT directly inject module properties - the engine should handle all internal routing

## Known Issues

1. Containers that include Micronaut may still try to resolve properties from test resources even with `MICRONAUT_TEST_RESOURCES_ENABLED=false`
2. Environment variable overrides may not always take precedence over test resources property resolution
3. The engine container needs comprehensive environment variable configuration for all service endpoints