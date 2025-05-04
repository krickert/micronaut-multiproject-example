# Pipeline Test Platform

A standalone testing framework for Micronaut and pipeline-core with Kafka and schema registry support.

## Overview

This project provides a testing framework for Micronaut applications that use Kafka and schema registries. It supports two types of schema registries:

1. **Apicurio Registry** - An open-source schema registry
2. **AWS Glue Schema Registry** - AWS's schema registry service (mimicked using Moto for testing)

The framework is designed to be easy to use and to provide a consistent interface for both registry types.

## Features

- Automatic container startup and configuration
- Property management before Micronaut starts
- Support for both Apicurio and AWS Glue Schema Registry
- Automatic topic creation and cleanup
- Easy integration with Micronaut tests

## Usage

### Basic Usage

To use the framework in your tests, you can use the `KafkaTestFactory` to create the appropriate implementation:

```java
@MicronautTest
public class MyTest implements TestPropertyProvider {
    private final KafkaTest kafkaTest = KafkaTestFactory.createKafkaTest();
    
    @Override
    public Map<String, String> getProperties() {
        return kafkaTest.getProperties();
    }
    
    @Test
    void testMyFeature() {
        // Your test code here
    }
}
```

### Specifying the Registry Type

You can specify the registry type using the `kafka.registry.type` property:

```properties
# In application.properties or as a system property
kafka.registry.type=apicurio  # or "glue"
```

If not specified, it defaults to "apicurio".

### Using a Specific Return Class

If you need to specify a custom return class for the deserializer:

```java
KafkaTest kafkaTest = KafkaTestFactory.createKafkaTest("com.example.MyCustomClass");
```

### Direct Usage

You can also use the implementations directly:

```java
// For Apicurio
KafkaTest kafkaTest = new KafkaApicurioTest();

// For AWS Glue
KafkaTest kafkaTest = new KafkaGlueTest();
```

## Configuration

### Apicurio Registry

The Apicurio Registry is configured with:

- In-memory storage
- Anonymous read access enabled
- Debug logging

### AWS Glue Schema Registry (Moto)

The AWS Glue Schema Registry is mimicked using Moto and configured with:

- Test credentials (access key: "test", secret key: "test")
- Region: "us-east-1"
- Registry name: "test-registry"

## Integration with Micronaut Tests

To use the framework with Micronaut tests, you can extend one of the implementations:

```java
@MicronautTest(environments = "apicurio")
public class MyApicurioTest extends KafkaApicurioTest {
    @Test
    void testWithApicurio() {
        // Your test code here
    }
}

@MicronautTest(environments = "glue")
public class MyGlueTest extends KafkaGlueTest {
    @Test
    void testWithGlue() {
        // Your test code here
    }
}
```

## Environment Profiles

The project includes two environment profiles:

1. `application-apicurio.properties` - For testing with Apicurio Registry
2. `application-glue.properties` - For testing with AWS Glue Schema Registry

You can select the profile using the `environments` parameter of the `@MicronautTest` annotation.