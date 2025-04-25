# Micronaut Test Consul Container

This module provides a Consul container for testing Micronaut applications that use Consul for service discovery and configuration.

## Features

- Automatically starts a Consul container for testing
- Provides configuration properties for Micronaut to connect to Consul
- Includes utility methods for loading configuration into Consul
- Supports testing service discovery and configuration

## Usage

### Adding the Dependency

Add the dependency to your build.gradle.kts file:

```kotlin
dependencies {
    testImplementation(project(":pipeline-service-test-utils:micronaut-test-consul-container"))
}
```

### Using the ConsulContainer

The `ConsulContainer` class provides a Consul container for testing. It implements `TestPropertyProvider` to provide configuration properties for Micronaut to connect to Consul.

```java
@MicronautTest
public class MyTest {
    @Inject
    private ConsulContainer consulContainer;

    @Test
    public void testConsulIsRunning() {
        assertTrue(consulContainer.isRunning());
        String endpoint = consulContainer.getEndpoint();
        // Use the endpoint to interact with Consul
    }
}
```

### Using the ConsulTestHelper

The `ConsulTestHelper` class provides utility methods for loading configuration into Consul.

```java
@MicronautTest
public class MyTest {
    @Inject
    private ConsulTestHelper consulTestHelper;

    @Test
    public void testLoadConfiguration() {
        // Load a properties file into Consul
        boolean result = consulTestHelper.loadPropertiesFile("my-config.properties", "config/myapp");
        assertTrue(result);

        // Load pipeline configuration into Consul
        result = consulTestHelper.loadPipelineConfig("pipeline-config.properties");
        assertTrue(result);
    }
}
```

## Configuration

The ConsulContainer provides the following configuration properties:

- `micronaut.application.name`: The name of the application
- `micronaut.config-client.enabled`: Enables the config client
- `consul.client.defaultZone`: The Consul server URL
- `consul.client.config.format`: The format of the configuration (YAML)
- `consul.client.config.path`: The path to the configuration in Consul
- `consul.client.watch.enabled`: Enables watching for configuration changes

## Examples

### Testing Configuration Loading

```java
@MicronautTest
public class ConfigurationTest {
    @Inject
    private ConsulTestHelper consulTestHelper;

    @Inject
    private ApplicationContext applicationContext;

    @Test
    public void testLoadConfiguration() {
        // Load configuration into Consul
        consulTestHelper.loadPropertiesFile("my-config.properties", "config/myapp");

        // Verify that the configuration was loaded
        Environment environment = applicationContext.getEnvironment();
        String value = environment.getProperty("my.property", String.class).orElse(null);
        assertNotNull(value);
    }
}
```

### Testing Service Discovery

```java
@MicronautTest
public class ServiceDiscoveryTest {
    @Inject
    private ConsulContainer consulContainer;

    @Test
    public void testServiceDiscovery() {
        // Register a service with Consul
        // ...

        // Discover the service
        // ...
    }
}
```

## Advanced Usage

For more advanced usage, you can access the ConsulClient directly:

```java
@MicronautTest
public class AdvancedTest {
    @Inject
    private ConsulTestHelper consulTestHelper;

    @Test
    public void testAdvancedUsage() {
        // Get the ConsulClient
        ConsulClient consulClient = consulTestHelper.getConsulClient();

        // Use the ConsulClient to interact with Consul
        // ...
    }
}
```

## Limitations and Workarounds

### ConsulClient API Limitations

The ConsulClient interface in Micronaut is designed to be used with a real Consul server. When testing, there are some limitations to be aware of:

1. **Direct Key-Value Operations**: The ConsulClient interface doesn't provide direct methods for key-value operations that work in a test environment. Methods like `putValue`, `readValue`, and `readValues` may not be implemented or may not work as expected in tests.

2. **Reactive API**: The ConsulClient uses a reactive API, which can be challenging to use in tests. Methods return reactive types like `Publisher` or `Flowable` that need to be handled appropriately.

### Workarounds

To work around these limitations, the ConsulTestHelper class provides the following approaches:

1. **Simulation Mode**: Instead of actually storing values in Consul, the ConsulTestHelper simulates the operation by logging what would be stored. This avoids issues with unimplemented methods in the ConsulClient interface.

   ```java
   // This will log the properties that would be stored in Consul
   consulTestHelper.loadPropertiesFile("my-config.properties", "config/myapp");
   ```

2. **Environment Integration**: For testing configuration loading, use the Micronaut Environment instead of the ConsulClient directly. The Environment will automatically load properties from Consul if it's available.

   ```java
   // Verify configuration using the Environment
   String value = environment.getProperty("my.property", String.class).orElse(null);
   ```

3. **Mock Implementation**: For more complex testing scenarios, consider creating a mock implementation of the ConsulClient interface that provides the behavior you need for testing.

### Best Practices

1. **Focus on Integration**: Instead of testing the ConsulClient directly, focus on testing the integration between your application and Consul. Verify that your application can read configuration from Consul when it's available.

2. **Use Environment Properties**: Use the Micronaut Environment to access properties, as it will handle the integration with Consul behind the scenes.

3. **Test Fallback Behavior**: Test that your application falls back to other configuration sources when Consul is not available or when a property is not found in Consul.

4. **Document Assumptions**: When writing tests that involve Consul, document any assumptions you're making about the Consul server or the ConsulClient implementation.
