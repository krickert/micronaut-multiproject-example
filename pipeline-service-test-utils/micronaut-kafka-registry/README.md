# Micronaut Kafka Testing Utilities

This module provides utilities for testing Kafka integration with schema registries in Micronaut applications.

## AbstractKafkaTest

The base class for all Kafka tests. It sets up a Kafka container using TestContainers and provides configuration for tests.

## AbstractKafkaIntegrationTest

An abstract test class for testing Kafka integration with a schema registry using custom `MessageProducer` and `MessageConsumer` interfaces.

## AbstractMicronautKafkaTest

A new abstract test class for testing Kafka integration using Micronaut's built-in Kafka annotations (`@KafkaClient`, `@KafkaListener`, `@Topic`).

### How to Use AbstractMicronautKafkaTest

1. Create a test class that extends `AbstractMicronautKafkaTest<T>` where `T` is the type of message you want to test.
2. Implement the required abstract methods:
   - `getTopicName()`: Return the Kafka topic name to use for the test.
   - `createTestMessage()`: Create a test message of type `T`.
   - `sendMessage(T message)`: Send a message using a Micronaut `@KafkaClient`.
   - `getNextMessage(long timeoutSeconds)`: Get the next message received by a Micronaut `@KafkaListener`.
3. Define a Kafka client interface with `@KafkaClient` annotation.
4. Define a Kafka listener class with `@KafkaListener` annotation.

### Example Implementation

```java
@MicronautTest(environments = "test", transactional = false)
public class MicronautKafkaApicurioTest extends AbstractMicronautKafkaTest<PipeStream> {
    private static final String TOPIC = "test-micronaut-pipestream-apicurio";

    @Inject
    PipeStreamClient pipeStreamClient;

    @Inject
    PipeStreamListener pipeStreamListener;

    @Override
    protected String getTopicName() {
        return TOPIC;
    }

    @Override
    protected PipeStream createTestMessage() {
        return PipeDocExample.createFullPipeStream();
    }

    @Override
    protected void sendMessage(PipeStream message) throws Exception {
        pipeStreamClient.send(message).get(10, TimeUnit.SECONDS);
    }

    @Override
    protected PipeStream getNextMessage(long timeoutSeconds) throws Exception {
        return pipeStreamListener.getNextMessage(timeoutSeconds);
    }

    // Kafka client interface
    @KafkaClient
    public interface PipeStreamClient {
        @Topic(TOPIC)
        CompletableFuture<Void> send(PipeStream message);
    }

    // Kafka listener class
    @Singleton
    @KafkaListener(groupId = "test-micronaut-group")
    public static class PipeStreamListener {
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private CompletableFuture<PipeStream> nextMessage = new CompletableFuture<>();

        @Topic(TOPIC)
        public void receive(PipeStream message) {
            synchronized (receivedMessages) {
                receivedMessages.add(message);
                nextMessage.complete(message);
            }
        }

        public PipeStream getNextMessage(long timeoutSeconds) throws Exception {
            return nextMessage.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        // Additional methods for managing received messages...
    }
}
```

## Benefits of Using AbstractMicronautKafkaTest

1. **Direct Integration with Micronaut**: Uses Micronaut's built-in Kafka annotations for a more idiomatic approach.
2. **Simplified Testing**: Provides a clean framework for testing Kafka producers and consumers.
3. **Schema Registry Support**: Works with different schema registry implementations (Apicurio, AWS Glue, etc.).
4. **Testcontainers Integration**: Uses Testcontainers to spin up Kafka for testing.

## Note

As mentioned in the issue description, there might be complex problems with this approach that will need to be diagnosed separately. This implementation provides the foundation for further troubleshooting and refinement.