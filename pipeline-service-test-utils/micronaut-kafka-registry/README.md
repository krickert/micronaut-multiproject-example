# Micronaut Kafka Registry

This module provides a unified solution for testing Kafka with different schema registries in Micronaut applications.

## Features

- Support for both Apicurio Registry and AWS Glue Schema Registry (via Moto)
- Environment variable-based configuration to choose between registry implementations
- Automatic container setup for both registry types
- Integration with Micronaut's test framework

## Usage

### Add the dependency

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("com.krickert.search:micronaut-kafka-registry")
}
```

### Choose a schema registry implementation

By default, the Apicurio Registry implementation is used. To switch to the AWS Glue Schema Registry (Moto) implementation, you can use either a system property or an environment variable:

#### Using System Property (recommended for tests)

```java
// Use Apicurio Registry (default)
System.setProperty("schema.registry.type", "apicurio");

// Use AWS Glue Schema Registry (Moto)
System.setProperty("schema.registry.type", "moto");
```

#### Using Environment Variable

```bash
# Use Apicurio Registry (default)
export SCHEMA_REGISTRY_TYPE=apicurio

# Use AWS Glue Schema Registry (Moto)
export SCHEMA_REGISTRY_TYPE=moto
```

Note: If both are set, the system property takes precedence over the environment variable.

### Write a Kafka integration test

```java
@MicronautTest(environments = "test", transactional = false)
public class MyKafkaIntegrationTest extends AbstractKafkaIntegrationTest<MyMessage> {

    @Inject
    MyMessageProducer producer;

    @Inject
    TestMyMessageConsumer consumer;

    @Override
    protected MyMessage createTestMessage() {
        return new MyMessage("test");
    }

    @Override
    protected MessageProducer<MyMessage> getProducer() {
        return producer::sendMessage;
    }

    @Override
    protected MessageConsumer<MyMessage> getConsumer() {
        return consumer;
    }

    // Define producer and consumer classes
    @KafkaClient
    public interface MyMessageProducer {
        @Topic("test-topic")
        CompletableFuture<Void> sendMessage(MyMessage message);
    }

    @KafkaListener(groupId = "test-group")
    public static class TestMyMessageConsumer implements MessageConsumer<MyMessage> {
        // Implementation details...
    }
}
```

## Configuration

The module can be configured using either system properties or environment variables:

### System Properties (recommended for tests)

- `schema.registry.type`: The type of schema registry to use (`apicurio` or `moto`). Defaults to `apicurio`.
- `schema.registry.enabled`: Whether to enable the schema registry. Set to `false` to disable. Defaults to `true`.

### Environment Variables

- `SCHEMA_REGISTRY_TYPE`: The type of schema registry to use (`apicurio` or `moto`). Defaults to `apicurio`.
- `SCHEMA_REGISTRY_ENABLED`: Whether to enable the schema registry. Set to `false` to disable. Defaults to `true`.

Note: System properties take precedence over environment variables if both are set.

## Implementation Details

This module combines the functionality of three separate modules:
- `micronaut-kafka-registry-core`: Core classes for Kafka testing
- `micronaut-kafka-registry-apicurio`: Apicurio Registry implementation
- `micronaut-kafka-registry-moto`: AWS Glue Schema Registry (Moto) implementation

The implementation to use is determined by the `schema.registry.type` system property or the `SCHEMA_REGISTRY_TYPE` environment variable, which are checked in the `SchemaRegistryFactory` class. If both are set, the system property takes precedence.
