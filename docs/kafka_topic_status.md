# Kafka Topic Status Service

This document describes the Kafka Topic Status Service, which provides functionality for retrieving detailed status information about Kafka topics, including health, offsets, consumer lag, and listener status. The information is returned in a JSON-serializable Java Record that can be used for operational dashboards.

## Components

### KafkaTopicStatus

A Java Record that represents the status of a Kafka topic. It includes:

- Basic topic information (name, health status, last checked time)
- Partition information (count, replication factor)
- Offset information (per partition, largest offset)
- Consumer group information (ID, offsets, lag per partition, total lag)
- Listener status (RECEIVING, PAUSED, STOPPED, ERROR, UNKNOWN)
- Metrics information (map of metric names to values)

The record is designed to be serialized to JSON for use in operational dashboards.

### KafkaTopicStatusService

An interface that defines methods for retrieving status information about Kafka topics. It includes both asynchronous and synchronous methods for:

- Getting the status of a single topic
- Getting the status of a topic for a specific consumer group
- Getting the status of multiple topics
- Getting the status of a topic for multiple consumer groups

### MicronautKafkaTopicStatusService

An implementation of the KafkaTopicStatusService interface that uses the KafkaAdminService to retrieve information about Kafka topics and consumer groups. It also integrates with Micrometer for tracking timings and stats.

## Usage

### Getting the Status of a Topic

```java
// Inject the KafkaTopicStatusService
@Inject
private KafkaTopicStatusService kafkaTopicStatusService;

// Get the status of a topic
KafkaTopicStatus status = kafkaTopicStatusService.getTopicStatus("my-topic");

// Access the status information
System.out.println("Topic: " + status.topicName());
System.out.println("Health: " + status.healthStatus());
System.out.println("Partitions: " + status.partitionCount());
System.out.println("Largest Offset: " + status.largestOffset());
System.out.println("Total Lag: " + status.totalLag());
System.out.println("Listener Status: " + status.listenerStatus());
```

### Getting the Status of a Topic for a Consumer Group

```java
// Get the status of a topic for a specific consumer group
KafkaTopicStatus status = kafkaTopicStatusService.getTopicStatusForConsumerGroup("my-topic", "my-consumer-group");

// Access the consumer group information
System.out.println("Consumer Group: " + status.consumerGroupId());
System.out.println("Total Lag: " + status.totalLag());
System.out.println("Lag Per Partition: " + status.lagPerPartition());
```

### Asynchronous Usage

```java
// Get the status of a topic asynchronously
CompletableFuture<KafkaTopicStatus> future = kafkaTopicStatusService.getTopicStatusAsync("my-topic");

// Handle the result
future.thenAccept(status -> {
    System.out.println("Topic: " + status.topicName());
    System.out.println("Health: " + status.healthStatus());
    // ...
});
```

### Serializing to JSON

The KafkaTopicStatus record can be serialized to JSON using Jackson:

```java
// Create an ObjectMapper with JavaTimeModule for handling Instant
ObjectMapper objectMapper = new ObjectMapper();
objectMapper.registerModule(new JavaTimeModule());

// Serialize to JSON
String json = objectMapper.writeValueAsString(status);

// Deserialize from JSON
KafkaTopicStatus deserialized = objectMapper.readValue(json, KafkaTopicStatus.class);
```

## Metrics

The MicronautKafkaTopicStatusService integrates with Micrometer to track timings and stats. The following metrics are available:

- `kafka.topic.status.time`: Time taken to retrieve topic status
- `kafka.topic.status.error`: Count of errors retrieving topic status
- `kafka.topic.partition.count`: Number of partitions in a topic
- `kafka.topic.replication.factor`: Replication factor of a topic
- `kafka.topic.largest.offset`: Largest offset in a topic
- `kafka.topic.consumer.lag`: Consumer lag for a topic and consumer group