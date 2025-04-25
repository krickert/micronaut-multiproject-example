# Micronaut Test Consul Container

A Micronaut module that provides a Consul container for testing and development purposes. This module simplifies the setup and configuration of Consul in your Micronaut applications.

## Overview

The `micronaut-test-consul-container` module provides:

- A pre-configured Consul container using Testcontainers (using the `hashicorp/consul:latest` Docker image)
- Automatic configuration for Micronaut applications
- Integration with Micronaut's test framework
- Support for service discovery and distributed configuration

## Setup

### Prerequisites

- Java 21 or higher
- Docker installed and running
- Gradle or Maven build system

### Including the Module

#### Gradle

Add the following to your `build.gradle` or `build.gradle.kts`:

```kotlin
dependencies {
    // Other dependencies...
    testImplementation("com.krickert.search.test:micronaut-test-consul-container:latest.version")
}
```

#### Maven

Add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>com.krickert.search.test</groupId>
    <artifactId>micronaut-test-consul-container</artifactId>
    <version>latest.version</version>
    <scope>test</scope>
</dependency>
```

## Usage

### Basic Usage

The Consul container is automatically started when your application or tests start. You don't need to manually configure or start the container.

The module uses Micronaut's auto-configuration mechanism to automatically detect and load the Consul container when the library is included in your project. This is done through the `ConsulContainerFactory` and `ConsulContainerAutoConfiguration` classes, which are annotated with `@Factory` and create a singleton `ConsulContainer` bean.

The Consul container exposes port 8500 (the default Consul port) and maps it to a random port on the host. You can get the mapped port using the `getHostAndPort()` method of the `ConsulContainer` class.

The container is configured with the following environment variables:
- `CONSUL_BIND_INTERFACE=eth0`: Specifies the network interface that Consul will bind to
- `CONSUL_CLIENT_INTERFACE=eth0`: Specifies the network interface that Consul clients will use

The container has a startup timeout of 60 seconds, which should be sufficient for most environments. If the container fails to start within this time, an exception will be thrown.

Container reuse is disabled, which means that a new container will be created for each test run. This ensures isolation between test runs but may increase test execution time.

The container is configured with access to the host, which means it can access services running on the host machine. This is useful for integration testing with other services.

### In Tests

```java
import com.krickert.search.test.consul.ConsulContainer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

@MicronautTest
public class MyTest {

    @Inject
    private ConsulContainer consulContainer;

    @Test
    public void testConsulIsRunning() {
        Assertions.assertTrue(consulContainer.isRunning());
        String endpoint = consulContainer.getEndpoint();
        // Use the endpoint to interact with Consul
    }
}
```

### In Application Code

The Consul container is automatically started when your application starts. You can access the Consul container through dependency injection:

```java
import com.krickert.search.test.consul.ConsulContainer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class MyService {

    private final ConsulContainer consulContainer;

    @Inject
    public MyService(ConsulContainer consulContainer) {
        this.consulContainer = consulContainer;
        // Use the consulContainer
    }
}
```

## Configuration

The Consul container provides the following default configuration:

```yaml
micronaut:
  application:
    name: my-app
  config-client:
    enabled: true
consul:
  client:
    defaultZone: <container-host>:<container-port>
    config:
      format: YAML
      path: /config
    watch:
      enabled: true
```

The `defaultZone` property is automatically set to the host and port of the running Consul container.

### Custom Configuration

You can override the default configuration by providing your own properties in your `application.yml` or `application.properties` file.

## API Reference

### ConsulContainer

The `ConsulContainer` class provides the following methods:

- `getEndpoint()`: Returns the HTTP endpoint URL for the Consul server
- `getHostAndPort()`: Returns the host and port of the Consul server in the format "host:port"
- `isRunning()`: Checks if the Consul container is running
- `getProperties()`: Returns the properties for Micronaut configuration

## Testing

The module includes several test classes that demonstrate how to use the Consul container:

- `ConsulContainerTest`: Tests the basic functionality of the Consul container
- `ConsulConfigurationTest`: Tests the configuration loading from Consul
- `ConsulServiceDiscoveryTest`: Tests the service discovery functionality

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.
