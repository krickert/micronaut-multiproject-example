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

## Common Kafka Configuration Properties

The `AbstractKafkaTest` class provides a set of common Kafka configuration properties that are automatically applied to your tests:

### Producer Configuration

```properties
# Bootstrap servers configuration
kafka.producers.default.bootstrap.servers=<dynamically set to Kafka container address>

# Key and value serializers
kafka.producers.default.key.serializer=org.apache.kafka.common.serialization.UUIDSerializer
kafka.producers.default.value.serializer=<dynamically set based on schema registry>
```

### Consumer Configuration

```properties
# Bootstrap servers configuration
kafka.consumers.default.bootstrap.servers=<dynamically set to Kafka container address>

# Consumer group and offset reset
kafka.consumers.default.group.id=test-group
kafka.consumers.default.auto.offset.reset=earliest

# Key and value deserializers
kafka.consumers.default.key.deserializer=org.apache.kafka.common.serialization.UUIDDeserializer
kafka.consumers.default.value.deserializer=<dynamically set based on schema registry>
```

### Admin Client Configuration

```properties
# Admin client configuration
kafka.admin.client.id=test-admin-client
kafka.admin.request.timeout.ms=5000
kafka.admin.retries=3
kafka.admin.bootstrap.servers=<dynamically set to Kafka container address>
```

## Apicurio Registry Configuration

The Apicurio Registry is used for storing and retrieving Protobuf schemas. Here's how it's configured:

### Docker Container Setup

```java
// Docker image and configuration
new GenericContainer<>(DockerImageName.parse("apicurio/apicurio-registry:latest"))
    .withExposedPorts(8080)
    .withAccessToHost(true)
    .withEnv(Map.of(
        "QUARKUS_PROFILE", "prod",
        "REGISTRY_STORAGE_KIND", "in-memory",
        "REGISTRY_AUTH_ANONYMOUS_READ_ACCESS_ENABLED", "true",
        "REGISTRY_LOG_LEVEL", "DEBUG"
    ))
    .withStartupTimeout(Duration.ofSeconds(60))
    .withReuse(false);
```

### Protobuf Serialization Configuration

The Apicurio Registry is configured to work with Protobuf schemas:

```properties
# Producer configuration
kafka.producers.default.apicurio.registry.url=http://<container-host>:<container-port>/apis/registry/v3
kafka.producers.default.apicurio.registry.auto-register=true
kafka.producers.default.apicurio.registry.artifact-resolver-strategy=io.apicurio.registry.serde.strategy.TopicIdStrategy
kafka.producers.default.apicurio.registry.explicit-artifact-group-id=default
kafka.producers.default.key.serializer=org.apache.kafka.common.serialization.UUIDSerializer
kafka.producers.default.value.serializer=io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer

# Consumer configuration
kafka.consumers.default.apicurio.registry.url=http://<container-host>:<container-port>/apis/registry/v3
kafka.consumers.default.apicurio.registry.auto-register=true
kafka.consumers.default.apicurio.registry.artifact-resolver-strategy=io.apicurio.registry.serde.strategy.TopicIdStrategy
kafka.consumers.default.apicurio.registry.explicit-artifact-group-id=default
kafka.consumers.default.key.deserializer=org.apache.kafka.common.serialization.UUIDDeserializer
kafka.consumers.default.value.deserializer=io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
kafka.consumers.default.apicurio.registry.deserializer.specific.value.return.class=com.krickert.search.model.PipeStream
```

### Specific Class Property Configuration

The Apicurio Registry deserializer needs to know which class to return when deserializing Protobuf messages. This is configured using the `apicurio.registry.deserializer.specific.value.return.class` property:

```java
// Default return class
private static final String DEFAULT_RETURN_CLASS = "com.krickert.search.model.PipeStream";

// You can override this in your test
ApicurioSchemaRegistry registry = new ApicurioSchemaRegistry("com.example.MyCustomClass");
// OR
registry.setReturnClass("com.example.MyCustomClass");
```

## Moto Server Configuration (AWS Glue Schema Registry)

The Moto server is used to emulate the AWS Glue Schema Registry for testing:

### Docker Container Setup

```java
// Docker image and configuration
new GenericContainer<>(DockerImageName.parse("motoserver/moto:latest"))
    .withExposedPorts(5000)
    .withAccessToHost(true)
    .withCommand("-H0.0.0.0")
    .withEnv(Map.of(
        "MOTO_SERVICE", "glue",
        "TEST_SERVER_MODE", "true"
    ))
    .withStartupTimeout(Duration.ofSeconds(30))
    .withReuse(false);
```

### AWS Credentials Configuration

The Moto server requires AWS credentials, which are set up with test values:

```properties
# AWS credentials
aws.accessKeyId=test
aws.secretAccessKey=test
aws.sessionToken=test-session
aws.region=us-east-1
```

### AWS Glue Schema Registry Configuration

```properties
# Producer configuration
kafka.producers.default.aws.region=us-east-1
kafka.producers.default.aws.endpoint=http://<container-host>:<container-port>
kafka.producers.default.aws.schemaRegistry.name=default
kafka.producers.default.aws.schemaRegistry.dataFormat=PROTOBUF
kafka.producers.default.aws.schemaRegistry.protobuf.messageType=POJO
kafka.producers.default.aws.schemaRegistry.compatibility=FULL
kafka.producers.default.aws.schemaRegistry.autoRegistration=true

# Consumer configuration
kafka.consumers.default.aws.region=us-east-1
kafka.consumers.default.aws.endpoint=http://<container-host>:<container-port>
kafka.consumers.default.aws.schemaRegistry.name=default
kafka.consumers.default.aws.schemaRegistry.dataFormat=PROTOBUF
kafka.consumers.default.aws.schemaRegistry.protobuf.messageType=POJO
kafka.consumers.default.aws.schemaRegistry.autoRegistration=true
kafka.consumers.default.aws.schemaRegistry.compatibility=FULL
```

### Additional AWS Configuration Properties

The Moto server requires several additional AWS configuration properties to work correctly:

```properties
software.amazon.awssdk.regions.region=us-east-1
software.amazon.awssdk.endpoints.endpoint-url=http://<container-host>:<container-port>
software.amazon.awssdk.glue.endpoint=http://<container-host>:<container-port>
software.amazon.awssdk.glue.endpoint-url=http://<container-host>:<container-port>
aws.glue.endpoint=http://<container-host>:<container-port>
aws.serviceEndpoint=http://<container-host>:<container-port>
aws.endpointUrl=http://<container-host>:<container-port>
aws.endpointDiscoveryEnabled=false
```

## Implementation Details

This module combines the functionality of three separate modules:
- `micronaut-kafka-registry-core`: Core classes for Kafka testing
- `micronaut-kafka-registry-apicurio`: Apicurio Registry implementation
- `micronaut-kafka-registry-moto`: AWS Glue Schema Registry (Moto) implementation

The implementation to use is determined by the `schema.registry.type` system property or the `SCHEMA_REGISTRY_TYPE` environment variable, which are checked in the `SchemaRegistryFactory` class. If both are set, the system property takes precedence.

## Dependencies

### Required Dependencies for Apicurio Registry

```kotlin
// Apicurio Registry dependencies
implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")
```
*Note: The `apicurio-registry-protobuf-serde-kafka` dependency is only required for Protobuf schemas, and a google search usually leads 
you to try 2.x first!!.*

### Required Dependencies for AWS Glue Schema Registry (Moto)

```kotlin
// AWS Glue Schema Registry dependencies
api("software.amazon.glue:schema-registry-serde:1.1.23")
api("software.amazon.msk:aws-msk-iam-auth:2.2.0")
api("software.amazon.awssdk:url-connection-client:2.30.31")
```

