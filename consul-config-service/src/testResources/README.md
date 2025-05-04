# TestContainers Configuration

This directory contains TestResourceProvider implementations for various containers used in tests.

## Enabling/Disabling TestContainers

You can enable or disable TestContainers globally or individually using configuration properties in your `application-test.yml` file.

### Global Configuration

To enable or disable all TestContainers:

```yaml
testcontainers:
  enabled: true  # or false to disable all containers
```

### Individual Container Configuration

You can enable or disable specific containers:

```yaml
testcontainers:
  enabled: true  # Global setting
  kafka: true    # Enable Kafka container
  moto: false    # Disable Moto container
  consul: true   # Enable Consul container
  apicurio: true # Enable Apicurio container
```

You can also use a more detailed configuration:

```yaml
testcontainers:
  kafka:
    enabled: true
  moto:
    enabled: false
  consul:
    enabled: true
  apicurio:
    enabled: true
```

## Default Behavior

By default, all containers are enabled. You need to explicitly disable them in your configuration if you don't want them to start.

## Available Containers

1. **Kafka** - Apache Kafka message broker
   - Configuration property: `testcontainers.kafka`
   - Default image: `apache/kafka:latest`

2. **Consul** - Hashicorp Consul service discovery and configuration
   - Configuration property: `testcontainers.consul`
   - Default image: `hashicorp/consul`

3. **Moto** - AWS service mocking
   - Configuration property: `testcontainers.moto`
   - Default image: `motoserver/moto:latest`
   - Provides AWS Glue Schema Registry emulation

4. **Apicurio** - Apicurio Schema Registry
   - Configuration property: `testcontainers.apicurio`
   - Default image: `apicurio/apicurio-registry:latest`