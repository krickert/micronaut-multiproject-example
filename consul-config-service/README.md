# Consul Configuration Service

A centralized configuration management service for distributed systems using Consul KV store.

## Overview

The Consul Configuration Service provides a RESTful API for managing configuration stored in Consul's Key/Value store. It enables centralized configuration management for distributed microservices, with features for reading, writing, and deleting configuration values, as well as dynamic configuration updates.

## Features

- **RESTful API** for managing configuration in Consul KV store
- **Dynamic configuration updates** with event-based notification
- **Support for complex nested structures** with JSON/YAML serialization
- **Initial data seeding** from properties files
- **Comprehensive test coverage** using Testcontainers

## Getting Started

### Prerequisites

- Java 21 or later
- Gradle 8.0 or later
- Consul server (or use Docker)

### Running the Service

```bash
./gradlew :consul-config-service:run
```

Or with Docker:

```bash
docker run -d -p 8500:8500 hashicorp/consul:latest
./gradlew :consul-config-service:run
```

## API Endpoints

### Get Configuration

```
GET /config/{keyPath}
```

Retrieves the configuration value for the specified key path.

### Update Configuration (Plain Text)

```
PUT /config/{keyPath}
Content-Type: text/plain

value
```

Updates the configuration value for the specified key path with a plain text value.

### Update Configuration (JSON)

```
PUT /config/{keyPath}
Content-Type: application/json

{
  "key1": "value1",
  "key2": "value2"
}
```

Updates the configuration value for the specified key path with a JSON value.

### Delete Configuration

```
DELETE /config/{keyPath}
```

Deletes the configuration value for the specified key path.

### Refresh Configuration

```
POST /config/refresh
```

Triggers a refresh of all @Refreshable beans.

## Configuration

The service can be configured using the following properties:

```yaml
consul:
  client:
    host: localhost
    port: 8500
    config:
      path: config/pipeline
  data:
    seeding:
      enabled: true
      file: seed-data.yaml
      skip-if-exists: true
```

## Initial Data Seeding

The service can seed initial configuration data from a YAML file. The default file is `seed-data.yaml` in the classpath. The format is:

```yaml
pipeline:
  configs:
    pipeline1:
      service:
        chunker:
          kafka-listen-topics:
            - input-documents
          kafka-publish-topics: chunker-results

    pipeline2:
      service:
        chunker:
          kafka-listen-topics: input-documents2
          kafka-publish-topics: chunker-results2
```

For backward compatibility, the service can also load configuration from properties files.

## Dynamic Configuration Updates

When configuration is updated via the API, the service publishes events to notify consumers of the changes. Consumers can listen for these events and refresh their configuration accordingly.

## Testing

The service includes comprehensive tests using Testcontainers to run Consul in a Docker container during tests.

```bash
./gradlew :consul-config-service:test
```

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.
