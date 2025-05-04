# Pipeline Service Core

Core library for pipeline service implementation with Consul configuration support.

## Overview

The Pipeline Service Core provides a framework for defining and managing pipeline configurations. It supports loading configuration from both Consul and property files, with automatic fallback to property files when Consul is not available.

## Features

- Load pipeline configuration from Consul
- Fall back to property files when Consul is not available
- Update pipeline configuration through a REST API
- Support for multiple pipelines with different service configurations
- Dynamic configuration updates

## Configuration

### Consul Configuration

To enable Consul integration, add the following properties to your `application.properties` file:

```properties
# Enable Consul client
consul.client.enabled=true
consul.client.defaultZone=${CONSUL_HOST:localhost}:${CONSUL_PORT:8500}

# Configure Consul for configuration
consul.client.config.enabled=true
consul.client.config.format=properties
consul.client.config.path=config/pipeline

# Enable Consul watch for dynamic updates
consul.client.watch.enabled=true
```

### Pipeline Configuration Format

Pipeline configurations are defined using a hierarchical property structure:

```properties
pipeline.configs.<pipeline-name>.service.<service-name>.<property>=<value>
```

For example:

```properties
# Importer service configuration
pipeline.configs.pipeline1.service.importer.kafka-publish-topics=input-documents

# Chunker service configuration
pipeline.configs.pipeline1.service.chunker.kafka-listen-topics=input-documents
pipeline.configs.pipeline1.service.chunker.kafka-publish-topics=chunker-results

# Embedder service configuration
pipeline.configs.pipeline1.service.embedder.kafka-listen-topics=chunker-results
pipeline.configs.pipeline1.service.embedder.kafka-publish-topics=enhanced-documents
```

For properties that have multiple values, you can use either comma-separated values or array notation:

```properties
# Comma-separated values
pipeline.configs.pipeline1.service.chunker.kafka-listen-topics=topic1,topic2,topic3

# Array notation
pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[0]=topic1
pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[1]=topic2
pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[2]=topic3
```

## API Endpoints

The Pipeline Service Core provides the following REST API endpoints for managing pipeline configurations:

### Get Current Configuration

```
GET /api/pipeline/config
```

Returns the current pipeline configuration.

### Reload Configuration from Consul

```
POST /api/pipeline/config/reload/consul
```

Reloads the pipeline configuration from Consul.

### Reload Configuration from File

```
POST /api/pipeline/config/reload/file
```

Reloads the pipeline configuration from the default property file.

### Update Service Configuration

```
PUT /api/pipeline/config/{pipelineName}/service
```

Updates a specific service configuration in the specified pipeline.

Request body:

```json
{
  "name": "service-name",
  "kafkaListenTopics": ["topic1", "topic2"],
  "kafkaPublishTopics": ["topic3"],
  "grpcForwardTo": ["service-name"]
}
```

## Usage Examples

### Loading Configuration from Consul

```java
@Inject
private PipelineConfigManager pipelineConfig;

public void loadConfig() {
    boolean success = pipelineConfig.loadPropertiesFromConsul();
    if (success) {
        // Configuration loaded successfully
    } else {
        // Failed to load configuration from Consul
    }
}
```

### Updating Service Configuration

```java
@Inject
private PipelineConfigManager pipelineConfig;

public void updateServiceConfig(String pipelineName, String serviceName) {
    // Get the pipeline configuration
    PipelineConfig pipeline = pipelineConfig.getPipelines().get(pipelineName);
    if (pipeline == null) {
        // Pipeline not found
        return;
    }
    
    // Get the service configuration
    ServiceConfiguration service = pipeline.getService().get(serviceName);
    if (service == null) {
        // Service not found
        return;
    }
    
    // Update the service configuration
    service.setKafkaListenTopics(Arrays.asList("new-topic1", "new-topic2"));
    service.setKafkaPublishTopics(Arrays.asList("new-result-topic"));
    
    // Update the service configuration in Consul
    boolean success = pipelineConfig.updateServiceConfigInConsul(pipelineName, service);
    if (success) {
        // Configuration updated successfully
    } else {
        // Failed to update configuration in Consul
    }
}
```

## Testing with Consul

The Pipeline Service Core includes a test utility for testing with Consul. To use it, add the following dependency to your build.gradle.kts file:

```kotlin
testImplementation(project(":pipeline-service-test-utils:micronaut-test-consul-container"))
```

Then you can use the ConsulContainer and ConsulTestHelper classes in your tests:

```java
@MicronautTest(environments = {"test"})
public class ConsulTest {

    @Inject
    private ConsulContainer consulContainer;

    @Inject
    private ConsulTestHelper consulTestHelper;

    @Test
    void testConsulIntegration() {
        // Load test configuration into Consul
        boolean loaded = consulTestHelper.loadPipelineConfig("test-pipeline.properties");
        assertTrue(loaded);

        // Enable Consul in the environment
        System.setProperty("consul.client.enabled", "true");

        try {
            // Test your code that uses Consul
        } finally {
            // Reset the system property
            System.clearProperty("consul.client.enabled");
        }
    }
}
```

## Dependencies

- Micronaut Discovery Client: For Consul integration
- Micronaut Validation: For validating configuration
- Micronaut HTTP Server: For exposing the REST API