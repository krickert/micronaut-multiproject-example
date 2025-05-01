# Consul Test Container Management

## Overview
This package provides centralized management of Consul containers for tests. It ensures that all tests use a single shared Consul container instance, reducing test execution time and resource usage.

## Components

### ConsulTestContainer
A singleton class that manages a shared ConsulContainer for tests. This centralizes container management to avoid starting multiple containers across different test classes.

Key features:
- Static initialization of the container
- Singleton pattern to ensure only one instance exists
- Unmodifiable map of container properties
- Methods to access the container and its properties
- Helper methods for common container operations
- Centralized property management for test classes

### ConsulTestClientFactory
A factory class for creating Consul client beans for tests. This centralizes the creation of Consul clients using the shared ConsulTestContainer.

Key features:
- Provides named beans for different test classes
- Creates Consul and KeyValueClient beans
- Uses the shared container for all clients

## Benefits

1. **Reduced Resource Usage**: Only one Consul container is started for all tests, reducing memory and CPU usage.
2. **Faster Test Execution**: Tests don't need to wait for container startup, as the container is started once and shared.
3. **Simplified Test Code**: Test classes don't need to manage container lifecycle, reducing boilerplate code.
4. **Consistent Configuration**: All tests use the same container configuration, ensuring consistent test behavior.
5. **Easier Maintenance**: Container configuration is centralized, making it easier to update or modify.
6. **Centralized Property Management**: Common property combinations are centralized, reducing duplication and making tests more maintainable.

## Usage

### Basic Usage

To use the centralized container management in a test class:

1. Remove any existing ConsulContainer field and static initializer
2. Remove any TestBeanFactory inner class that creates Consul clients
3. Update the getProperties() method to use ConsulTestContainer.getInstance().getProperties()
4. Inject the appropriate named Consul client bean

Example:

```java
@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MyTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MyTest.class);

    @Inject
    @Named("myTest")
    private Consul consulClient;

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        return container.getProperties();
    }
}
```

### Centralized Property Management

The ConsulTestContainer class provides several methods for common property combinations:

1. **getProperties()** - Returns the base properties
2. **getPropertiesWithTestConfigPath()** - Returns properties with consul.client.config.path set to "config/test"
3. **getPropertiesWithDataSeeding()** - Returns properties with data seeding enabled and default settings
4. **getPropertiesWithCustomDataSeeding(String seedFile, boolean skipIfExists)** - Returns properties with data seeding enabled and custom settings
5. **getPropertiesWithoutDataSeeding()** - Returns properties with data seeding disabled
6. **getPropertiesWithTestConfigPathAndDataSeeding()** - Combines test config path and data seeding
7. **getPropertiesWithTestConfigPathWithoutDataSeeding()** - Combines test config path and disables data seeding

Example:

```java
@Override
public Map<String, String> getProperties() {
    ConsulTestContainer container = ConsulTestContainer.getInstance();
    LOG.info("Using shared Consul container");

    // Use centralized property management
    return container.getPropertiesWithTestConfigPathAndDataSeeding();
}
```

For custom properties that aren't covered by the centralized methods:

```java
@Override
public Map<String, String> getProperties() {
    ConsulTestContainer container = ConsulTestContainer.getInstance();
    LOG.info("Using shared Consul container");

    // Get properties with test config path
    Map<String, String> properties = container.getPropertiesWithTestConfigPath();

    // Add custom properties
    properties.put("my.custom.property", "value");

    return properties;
}
```

Or use the getPropertiesWith() method:

```java
@Override
public Map<String, String> getProperties() {
    ConsulTestContainer container = ConsulTestContainer.getInstance();
    LOG.info("Using shared Consul container");

    // Create custom properties
    Map<String, String> customProps = new HashMap<>();
    customProps.put("my.custom.property", "value");

    // Combine with base properties
    return container.getPropertiesWith(customProps);
}
```
