# Kafka Consumer Service with Slot Management

This package provides a Kafka consumer service that uses partition slot management for better control over partition assignments.

## Overview

The `KafkaConsumerService` integrates with `KafkaSlotManager` to:
- Manage partition assignments that survive restarts
- Create Kafka consumers that only consume from assigned partitions  
- Handle partition assignment changes dynamically
- Maintain heartbeats for assigned slots

## Components

### KafkaConsumerService
Main service that manages Kafka consumers based on slot assignments.

### KafkaConsumerConfiguration
Configuration properties for the consumer service.

### KafkaMessageProcessor
Interface for processing messages received from Kafka topics.

### PipelineKafkaMessageProcessor
Default implementation that routes messages to pipeline steps.

## Configuration

Enable slot management and configure the consumer in `application.yml`:

```yaml
app:
  kafka:
    slot-management:
      enabled: true
      consul:
        key-prefix: yappy/kafka/slots
        heartbeat-interval: 30s
        slot-expiration-time: 2m
      max-slots-per-engine: 100
    
    consumer:
      bootstrap-servers: localhost:9092
      max-poll-records: 500
      max-poll-interval: 5m
      session-timeout: 30s
      heartbeat-interval: 3s
      request-timeout: 30s
      fetch-min-bytes: 1
      fetch-max-wait: 500ms
      enable-auto-commit: false
      isolation-level: read_uncommitted
      
      # Error handling
      max-retries: 3
      retry-backoff: 1s
      pause-on-error: true
      error-pause-duration: 30s
```

## Usage

### Basic Usage

The service starts automatically when slot management is enabled:

```java
@Inject
KafkaConsumerService consumerService;

// Request slots for a topic
consumerService.requestSlots("my-topic", "my-group", 5)
    .subscribe();

// Release slots when done
consumerService.releaseSlots("my-topic")
    .subscribe();
```

### Custom Message Processing

Implement `KafkaMessageProcessor` for custom message handling:

```java
@Singleton
public class MyMessageProcessor implements KafkaMessageProcessor {
    
    @Override
    public Mono<Void> processRecords(List<ConsumerRecord<String, byte[]>> records,
                                    String topic,
                                    String groupId) {
        // Process records
        return Flux.fromIterable(records)
            .flatMap(this::processRecord)
            .then();
    }
    
    @Override
    public boolean handleError(Throwable error,
                              ConsumerRecord<String, byte[]> record,
                              String topic,
                              String groupId) {
        // Return false to pause consumer, true to continue
        return !isRetryableError(error);
    }
}
```

## How It Works

1. **Registration**: On startup, the service registers with the slot manager
2. **Slot Assignment**: The slot manager assigns partitions to this engine instance
3. **Consumer Creation**: For each topic/group combination, a dedicated consumer is created
4. **Partition Assignment**: Consumers are assigned only their allocated partitions
5. **Heartbeats**: Regular heartbeats maintain slot ownership
6. **Rebalancing**: Assignment changes trigger consumer recreation

## Benefits

- **Sticky Assignments**: Partitions stay with the same engine across restarts
- **Better Control**: Manual control over partition distribution
- **Visibility**: Clear understanding of which engine handles which partitions
- **Fault Tolerance**: Automatic reassignment when engines fail

## Integration with Pipeline

The default `PipelineKafkaMessageProcessor` integrates with the existing pipeline infrastructure:
- Deserializes messages based on configured schemas
- Routes to appropriate pipeline steps
- Handles errors according to configuration
- Supports backpressure and parallel processing

## TODO

- Implement ConsulKafkaSlotManager in kafka-slot-manager module
- Add topic-to-pipeline-step mapping configuration
- Implement metrics and monitoring
- Add support for manual partition reassignment
- Support for multiple topics per consumer group