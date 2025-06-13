# gRPC Service Module Sketch

## Overview
A new module that handles gRPC requests and converts them to events, making the engine truly transport-agnostic.

## Architecture

### 1. gRPC Service Implementation
```java
@Singleton
public class PipeStreamGrpcService extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    
    private final ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher;
    private final GrpcResponseHandler responseHandler;
    
    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<ProcessResponse> responseObserver) {
        // Convert gRPC request to event
        PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromGrpc(
            request,
            "PipeStreamEngine",
            "processPipeAsync"
        );
        
        // Publish event - same as kafka-service does!
        eventPublisher.publishEvent(event);
        
        // Return acknowledgment
        responseObserver.onNext(ProcessResponse.newBuilder()
            .setStreamId(request.getStreamId())
            .setStatus(ProcessStatus.ACCEPTED)
            .build());
        responseObserver.onCompleted();
    }
}
```

### 2. Event Flow Comparison

#### Kafka Input (existing)
```java
// In DynamicKafkaListener
private void processRecord(ConsumerRecord<UUID, PipeStream> record) {
    PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromKafka(
        record.value(),
        record.topic(),
        record.partition(),
        record.offset(),
        groupId
    );
    eventPublisher.publishEvent(event);
}
```

#### gRPC Input (proposed)
```java
// In PipeStreamGrpcService
public void processPipeAsync(PipeStream request, StreamObserver<ProcessResponse> responseObserver) {
    PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromGrpc(
        request,
        "PipeStreamEngine",
        "processPipeAsync"
    );
    eventPublisher.publishEvent(event);
}
```

### 3. Engine Processing (unchanged!)
```java
// In PipeStreamProcessingEventListener
@Override
public void onApplicationEvent(PipeStreamProcessingEvent event) {
    // Don't care if event came from Kafka or gRPC!
    logger.info("Processing event from source: {}", event.getSourceModule());
    
    if (event.getSourceModule().contains("kafka")) {
        // Log Kafka-specific metadata
    } else if (event.getSourceModule().contains("grpc")) {
        // Log gRPC-specific metadata
    }
    
    // Process the same way regardless of source
    pipelineEngine.processMessage(event.getPipeStream());
}
```

## Benefits

1. **Unified Processing** - Single event type for all inputs
2. **Transport Agnostic** - Engine doesn't know or care about transport
3. **Easy Testing** - Test any input by publishing events
4. **Monitoring** - Single place to monitor all processing events
5. **Future Transports** - Easy to add REST, WebSocket, etc.

## Module Structure
```
yappy-orchestrator/
├── engine-core/       # Processes events (transport agnostic)
├── kafka-service/     # Kafka → Events
├── grpc-service/      # gRPC → Events (NEW)
├── http-service/      # REST → Events (FUTURE)
└── engine-integration-test/
```

## Testing Becomes Trivial
```java
@Test
void testUnifiedProcessing() {
    // Test Kafka input
    PipeStreamProcessingEvent kafkaEvent = PipeStreamProcessingEvent.fromKafka(...);
    eventPublisher.publishEvent(kafkaEvent);
    
    // Test gRPC input  
    PipeStreamProcessingEvent grpcEvent = PipeStreamProcessingEvent.fromGrpc(...);
    eventPublisher.publishEvent(grpcEvent);
    
    // Both processed identically by engine-core!
}
```

## Implementation Plan

1. **Create grpc-service module**
   - Implement gRPC server that publishes events
   - Include health checks and metrics

2. **Update engine-core** 
   - Remove direct gRPC server (if any)
   - Pure event-driven processing

3. **Update integration tests**
   - Test both Kafka and gRPC inputs via events
   - No need for complex container setups

This makes the architecture truly pluggable - any transport can be added by just publishing the right events!