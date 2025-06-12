# Kafka Service Implementation Plan

## Overview
This document outlines the tasks required to complete the implementation of the kafka-service module. The code was imported from another project and needs several modifications to work properly in the current architecture. The main changes involve:

1. Replacing the PipeStreamEngine with a Micronaut event type
2. Integrating with the slot manager project service
3. Implementing Glue properties fetching
4. Converting CompletableFuture usage to Micronaut's reactive style (Project Reactor)

## 1. TODOs Analysis

### 1.1 Replace PipeStreamEngine with Micronaut Event Type

#### Context
The current implementation uses a `PipeStreamEngine` to process Kafka messages. This needs to be replaced with a Micronaut event type that will inform the engine to process the PipeStream request to the gRPC service.

#### Affected Files
- `KafkaListenerManager.java` (lines 41-42, 61-62)
- `DynamicKafkaListener.java` (line 47)
- `DefaultDynamicKafkaListenerFactory.java` (lines 18-22)
- `DefaultKafkaListenerPool.java` (line 38)
- `KafkaListenerPool.java` (lines 19-20)
- `DynamicKafkaListenerFactory.java` (lines 16-17)
- Test files that mock PipeStreamEngine

#### Required Changes
1. Create a new Micronaut event type class (e.g., `KafkaMessageReceivedEvent`)
2. Modify `DynamicKafkaListener.processRecord()` to publish the event instead of calling the engine directly
3. Update all references to PipeStreamEngine in the affected files
4. Update tests to verify event publishing instead of direct engine calls

#### Estimated Effort
**Medium** - Requires creating a new event type and modifying multiple files, but the pattern is straightforward.

### 1.2 Integrate with Slot Manager Project Service

#### Context
The code needs to integrate with the slot manager project service for slot management.

#### Affected Files
- `KafkaListenerManager.java` (lines 42, 62)
- `DefaultDynamicKafkaListenerFactory.java` (line 23)
- `KafkaListenerPool.java` (line 20)
- `DynamicKafkaListenerFactory.java` (line 17)
- `build.gradle.kts` (line 82)

#### Required Changes
1. Add the slot manager project as a dependency in `build.gradle.kts`
2. Inject the slot manager service into the affected classes
3. Modify the code to use the slot manager service for slot allocation and management

#### Estimated Effort
**Medium to High** - Requires understanding the slot manager API and integrating it properly.

### 1.3 Implement Glue Properties Fetching

#### Context
The code needs to implement AWS Glue Schema Registry properties fetching from ApplicationContext.

#### Affected Files
- `KafkaListenerManager.java` (line 330)
- `DynamicKafkaListener.java` (line 115)

#### Required Changes
1. Implement the `addGlueConsumerProperties()` method in `KafkaListenerManager.java`
2. Add checks for Glue deserializer in `DynamicKafkaListener.java`

#### Estimated Effort
**Low** - Similar to the existing Apicurio implementation, just needs to be adapted for Glue.

### 1.4 Other TODOs

#### Context
There are several other TODOs related to test improvements and configuration.

#### Affected Files
- `KafkaListenerManagerIntegrationTest.java` (lines 33, 39, 187)
- `KafkaAdminServiceConfig.java` (line 7)
- `KafkaForwarderIT.java` (line 49)
- `MicronautKafkaAdminServiceIT.java` (line 219)

#### Required Changes
1. Refactor `KafkaListenerManagerIntegrationTest` to use real components instead of mocks
2. Update configuration in `KafkaAdminServiceConfig.java`
3. Complete the TODO in `KafkaForwarderIT.java`
4. Add tests for consumer group operations in `MicronautKafkaAdminServiceIT.java`

#### Estimated Effort
**Medium** - Mostly test improvements that follow established patterns.

## 2. Converting CompletableFuture to Reactor Style

### 2.1 Context
The current implementation uses `CompletableFuture` for asynchronous operations. These need to be converted to Micronaut's reactive style using Project Reactor (`Mono`/`Flux`).

### 2.2 Affected Files
- `KafkaAdminService.java` (interface with many CompletableFuture methods)
- `MicronautKafkaAdminService.java` (implementation with CompletableFuture)
- `KafkaTopicStatusService.java` (interface with CompletableFuture methods)
- `MicronautKafkaTopicStatusService.java` (implementation)
- `PipelineKafkaTopicService.java` (interface with CompletableFuture methods)
- `MicronautPipelineKafkaTopicService.java` (implementation)
- `KafkaForwarder.java` (uses CompletableFuture)
- `KafkaForwarderClient.java` (interface with CompletableFuture methods)
- `KafkaListenerManager.java` (uses CompletableFuture for consumer operations)

### 2.3 Required Changes

#### 2.3.1 Interface Changes
1. Update all method signatures in interfaces to return `Mono<T>` instead of `CompletableFuture<T>`
2. For methods returning collections, consider using `Flux<T>` instead of `Mono<Collection<T>>`

Example:
```java
// Before
CompletableFuture<Void> createTopicAsync(TopicOpts topicOpts, String topicName);

// After
Mono<Void> createTopicAsync(TopicOpts topicOpts, String topicName);
```

#### 2.3.2 Implementation Changes
1. Replace the `toCompletableFuture` helper method with a `toMono` method:

```java
private <T> Mono<T> toMono(KafkaFuture<T> kafkaFuture) {
    return Mono.create(sink -> {
        kafkaFuture.whenComplete((result, error) -> {
            if (error != null) {
                sink.error(mapKafkaException(error));
            } else {
                sink.success(result);
            }
        });
    });
}
```

2. Update all method implementations to use Reactor operators:

```java
// Before
public CompletableFuture<Void> createTopicAsync(TopicOpts topicOpts, String topicName) {
    // ...
    CreateTopicsResult result = adminClient.createTopics(Collections.singleton(newTopic));
    return toCompletableFuture(result.all());
}

// After
public Mono<Void> createTopicAsync(TopicOpts topicOpts, String topicName) {
    // ...
    CreateTopicsResult result = adminClient.createTopics(Collections.singleton(newTopic));
    return toMono(result.all());
}
```

3. Replace CompletableFuture chaining with Reactor operators:

```java
// Before
return doesTopicExistAsync(topicName)
    .thenCompose(exists -> {
        if (exists) {
            return deleteTopicAsync(topicName)
                .thenCompose(v -> {
                    // Wait a bit
                    CompletableFuture<Void> delayFuture = new CompletableFuture<>();
                    taskScheduler.schedule(Duration.ofSeconds(2), () -> delayFuture.complete(null));
                    return delayFuture;
                })
                .thenCompose(v -> createTopicAsync(topicOpts, topicName));
        } else {
            return createTopicAsync(topicOpts, topicName);
        }
    });

// After
return doesTopicExistAsync(topicName)
    .flatMap(exists -> {
        if (exists) {
            return deleteTopicAsync(topicName)
                .then(Mono.delay(Duration.ofSeconds(2)).then())
                .then(createTopicAsync(topicOpts, topicName));
        } else {
            return createTopicAsync(topicOpts, topicName);
        }
    });
```

4. Update error handling:

```java
// Before
.exceptionally(ex -> {
    // Handle error
    throw new CompletionException(ex);
});

// After
.onErrorResume(ex -> {
    // Handle error
    return Mono.error(ex);
});
```

#### 2.3.3 Synchronous Wrapper Changes
1. Update the synchronous wrappers to use `block()` instead of `get()`:

```java
// Before
private <T> T waitFor(CompletableFuture<T> future, Duration timeout, String operationDescription) {
    try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        // Handle exception
    }
}

// After
private <T> T waitFor(Mono<T> mono, Duration timeout, String operationDescription) {
    try {
        return mono.block(timeout);
    } catch (RuntimeException e) {
        // Handle exception
    }
}
```

### 2.4 Estimated Effort
**High** - This is a significant refactoring that affects many files and requires careful testing to ensure all asynchronous operations work correctly.

## 3. Implementation Strategy

### 3.1 Recommended Order of Implementation
1. Create the Micronaut event type for Kafka messages
2. Replace PipeStreamEngine with the event type
3. Implement Glue properties fetching
4. Convert CompletableFuture to Reactor style
5. Integrate with the slot manager project service
6. Update tests and fix any remaining TODOs

### 3.2 Testing Strategy
1. Update unit tests to verify event publishing instead of direct engine calls
2. Ensure integration tests use real components where possible
3. Add tests for Glue Schema Registry integration
4. Add tests for Reactor-style asynchronous operations
5. Add tests for slot manager integration

## 4. Conclusion
Completing these tasks will require a significant effort, particularly the conversion from CompletableFuture to Reactor style. However, the result will be a more maintainable and better-integrated Kafka service that follows Micronaut's reactive programming model.

The most critical tasks are replacing the PipeStreamEngine with an event type and converting to Reactor style, as these affect the core functionality of the service. The Glue properties implementation and slot manager integration can be done afterward if needed.