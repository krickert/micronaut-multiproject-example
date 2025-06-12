# Commons Module Architecture Plan

## Vision: Event-Driven Decoupled Architecture

By creating a shared commons module with interfaces and events, we can achieve true decoupling between modules. This transforms the application from direct dependencies to contract-based integration through events and interfaces.

## Architecture Benefits

### Before (Direct Dependencies)
```
kafka-service → engine-core → modules
     ↓              ↓           ↓
  protobuf      protobuf    protobuf
```

### After (Event-Driven with Commons)
```
         commons (interfaces + events)
         ↗    ↑    ↑    ↑    ↖
kafka-service | engine-core | modules
              ↓     ↓      ↓
           protobuf models only
```

## Commons Module Structure

```
yappy-commons/
├── build.gradle.kts
└── src/main/java/com/krickert/search/commons/
    ├── events/
    │   ├── base/
    │   │   ├── BaseEvent.java                    # Base for all events
    │   │   ├── EventMetadata.java                # Common metadata
    │   │   └── EventPriority.java                # Event priority enum
    │   ├── processing/
    │   │   ├── PipeStreamProcessingEvent.java    # Main processing event
    │   │   ├── PipeStreamCompletedEvent.java     # Completion notification
    │   │   ├── PipeStreamErrorEvent.java         # Error notification
    │   │   └── PipeStreamRetryEvent.java         # Retry notification
    │   ├── lifecycle/
    │   │   ├── ModuleStartedEvent.java           # Module lifecycle
    │   │   ├── ModuleStoppedEvent.java
    │   │   └── ModuleHealthCheckEvent.java
    │   └── admin/
    │       ├── TopicCreatedEvent.java            # Kafka admin events
    │       ├── TopicDeletedEvent.java
    │       └── ConsumerGroupResetEvent.java
    ├── interfaces/
    │   ├── processing/
    │   │   ├── PipeStreamProcessor.java          # Core processing interface
    │   │   ├── PipeStreamRouter.java             # Routing interface
    │   │   └── PipeStreamValidator.java          # Validation interface
    │   ├── messaging/
    │   │   ├── MessageForwarder.java             # Generic forwarder
    │   │   ├── MessageConsumer.java              # Generic consumer
    │   │   └── MessageProducer.java              # Generic producer
    │   ├── admin/
    │   │   ├── TopicManager.java                 # Topic management
    │   │   └── ConsumerGroupManager.java         # Consumer management
    │   └── monitoring/
    │       ├── HealthCheckable.java              # Health check interface
    │       ├── Monitorable.java                  # Metrics interface
    │       └── SlotManageable.java               # Slot management
    ├── models/
    │   ├── ProcessingResult.java                 # Common result types
    │   ├── ErrorInfo.java                        # Error information
    │   └── RoutingDecision.java                  # Routing decisions
    └── exceptions/
        ├── ProcessingException.java              # Base exception
        ├── RoutingException.java
        └── ValidationException.java
```

## Event Design Details

### Base Event Structure
```java
@Introspected
public abstract class BaseEvent implements ApplicationEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final Instant timestamp = Instant.now();
    private final EventMetadata metadata;
    
    protected BaseEvent(EventMetadata metadata) {
        this.metadata = metadata;
    }
    
    // Common methods for all events
    public String getCorrelationId() {
        return metadata.correlationId();
    }
}

@Introspected
public record EventMetadata(
    String correlationId,
    String sourceModule,
    String sourceVersion,
    Map<String, String> headers,
    EventPriority priority
) {}
```

### PipeStream Processing Event
```java
@Introspected
public class PipeStreamProcessingEvent extends BaseEvent {
    private final PipeStream pipeStream;
    private final RoutingDecision routing;
    
    public PipeStreamProcessingEvent(
            PipeStream pipeStream,
            RoutingDecision routing,
            EventMetadata metadata) {
        super(metadata);
        this.pipeStream = pipeStream;
        this.routing = routing;
    }
    
    // Factory methods for different sources
    public static PipeStreamProcessingEvent fromKafka(
            PipeStream pipeStream, 
            KafkaContext context) {
        // Build event with Kafka-specific metadata
    }
    
    public static PipeStreamProcessingEvent fromGrpc(
            PipeStream pipeStream, 
            GrpcContext context) {
        // Build event with gRPC-specific metadata
    }
}
```

## Interface Design Philosophy

### 1. Processing Interfaces
```java
public interface PipeStreamProcessor {
    /**
     * Process a PipeStream and return a result.
     * Implementations should be stateless.
     */
    Mono<ProcessingResult> process(PipeStream pipeStream, ProcessingContext context);
    
    /**
     * Validate if this processor can handle the given PipeStream.
     */
    boolean canProcess(PipeStream pipeStream);
    
    /**
     * Get the processor's capabilities for registration.
     */
    ProcessorCapabilities getCapabilities();
}
```

### 2. Messaging Interfaces
```java
public interface MessageForwarder<T> {
    /**
     * Forward a message to a destination.
     */
    Mono<Void> forward(T message, ForwardingContext context);
    
    /**
     * Check if the forwarder supports the given transport type.
     */
    boolean supportsTransport(TransportType type);
}

// Kafka implementation in kafka-service
@Singleton
public class KafkaMessageForwarder implements MessageForwarder<PipeStream> {
    // Implementation details
}

// gRPC implementation in engine-core
@Singleton  
public class GrpcMessageForwarder implements MessageForwarder<PipeStream> {
    // Implementation details
}
```

## Decoupling Strategy

### 1. Remove Direct Dependencies
Instead of:
```java
// kafka-service depending on engine
@Inject PipeStreamEngine engine;
```

Use:
```java
// kafka-service publishes events
@Inject ApplicationEventPublisher<PipeStreamProcessingEvent> publisher;
```

### 2. Contract-Based Integration
Modules communicate through:
- **Events** - Asynchronous notifications
- **Interfaces** - Synchronous contracts
- **Shared Models** - Data transfer objects

### 3. Discovery Pattern
```java
// Modules register their capabilities
@EventListener
public void onModuleStarted(ModuleStartedEvent event) {
    if (event.getCapabilities().contains("chunker")) {
        registerChunkerModule(event.getModuleId());
    }
}
```

## Implementation Phases

### Phase 1: Foundation (Week 1)
1. Create commons module structure
2. Define base events and interfaces
3. Create event hierarchy for PipeStream processing
4. Add to all module dependencies

### Phase 2: Kafka Refactoring (Week 2)
1. Replace PipeStreamEngine with event publishing
2. Update tests to verify events
3. Ensure kafka-service compiles and tests pass

### Phase 3: Engine Integration (Week 3)
1. Create event listeners in engine-core
2. Implement interface-based routing
3. Update module registration to use events

### Phase 4: Full Decoupling (Week 4+)
1. Extract remaining interfaces to commons
2. Remove direct dependencies between modules
3. Implement service discovery pattern

## Testing Strategy

### 1. Event Testing
```java
@Test
void testEventPublishing() {
    // Use Micronaut's event testing support
    TestEventListener listener = new TestEventListener();
    
    kafkaListener.processMessage(pipeStream);
    
    await().atMost(5, SECONDS).until(() -> 
        listener.receivedEvent(PipeStreamProcessingEvent.class)
    );
}
```

### 2. Interface Contract Testing
```java
// Test all implementations against interface contract
abstract class MessageForwarderContractTest<T> {
    abstract MessageForwarder<T> createForwarder();
    
    @Test
    void testForwardingContract() {
        // Common tests for all forwarders
    }
}
```

## Benefits Realized

### 1. True Microservice Architecture
- Modules can be deployed independently
- No compile-time dependencies between services
- Easy to add new modules without changing core

### 2. Enhanced Testability
- Mock events instead of complex objects
- Test modules in isolation
- Contract-based testing ensures compatibility

### 3. Operational Flexibility
- Route events to multiple consumers
- Add monitoring/logging transparently
- Implement circuit breakers at event level

### 4. Future Extensibility
- Add new event types without changing existing code
- Implement new transport mechanisms easily
- Support multi-tenant scenarios through event metadata

## Next Steps

1. **Create commons module** with basic structure
2. **Define PipeStreamProcessingEvent** and related events
3. **Update kafka-service** to use events instead of PipeStreamEngine
4. **Create event listener** in engine-core
5. **Gradually migrate** other direct dependencies to interfaces

This architecture sets the foundation for a truly scalable, maintainable, and extensible system!