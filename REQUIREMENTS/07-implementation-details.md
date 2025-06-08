# Implementation Details

This document outlines the detailed implementation plan for core components of the YAPPY Engine system.

## Overview of Components

### Core Components to Implement

1. **PipeStreamEngineImpl** - Main implementation of the PipeStreamEngine gRPC service (DONE)
2. **PipeStreamGrpcForwarder** - Handles forwarding to gRPC services (DONE)
3. **PipelineStepGrpcProcessor** - Processes pipeline steps via gRPC (DONE)
4. **PipeStepExecutor** - Interface and factory for executing pipeline steps (DONE)
5. **PipeStreamStateBuilder** - Builder for managing pipeline stream state (IN PROGRESS)

## Project Architecture

### Distributed Microservice Architecture

1. **Service Instance Model**
   - Each service instance runs exactly one module (e.g., Echo, Chunker)
   - Each service contains both module implementation and embedded PipeStreamEngine
   - Services communicate through gRPC and/or Kafka

2. **Central Configuration**
   - Configuration managed through Consul
   - Dynamic updates without service restarts
   - Version tracking and rollback support

3. **Schema Management**
   - Schema validation via Apicurio or AWS Glue Schema Registry
   - Protobuf schemas stored and versioned
   - Automatic schema evolution support

## Component Implementations

### 1. PipeStepExecutor Interface

Provides abstraction for executing pipeline steps with different strategies.

**Interface Definition:**
```java
public interface PipeStepExecutor {
    /**
     * Execute a pipeline step with the given PipeStream.
     * 
     * @param pipeStream The input PipeStream to process
     * @return The processed PipeStream
     * @throws PipeStepExecutionException If an error occurs during execution
     */
    PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException;

    /**
     * Get the name of the step this executor handles.
     * @return The step name
     */
    String getStepName();

    /**
     * Get the type of step this executor handles.
     * @return The step type
     */
    StepType getStepType();
}
```

**GrpcPipeStepExecutor Implementation:**
```java
@Singleton
public class GrpcPipeStepExecutor implements PipeStepExecutor {
    private final PipelineStepGrpcProcessor grpcProcessor;
    private final String stepName;
    private final StepType stepType;

    @Override
    public PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException {
        try {
            ProcessResponse response = grpcProcessor.processStep(pipeStream, stepName);
            return transformResponseToPipeStream(pipeStream, response);
        } catch (Exception e) {
            throw new PipeStepExecutionException("Error executing gRPC step: " + stepName, e);
        }
    }

    private PipeStream transformResponseToPipeStream(PipeStream original, ProcessResponse response) {
        return original.toBuilder()
            .setDocument(response.getOutputDoc())
            .build();
    }
}
```

### 2. PipeStepExecutorFactory

Factory for creating appropriate executors based on configuration.

**Interface Definition:**
```java
public interface PipeStepExecutorFactory {
    /**
     * Get an executor for the specified pipeline and step.
     * 
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The appropriate executor for the step
     * @throws PipeStepExecutorNotFoundException If no executor can be found
     */
    PipeStepExecutor getExecutor(String pipelineName, String stepName) 
        throws PipeStepExecutorNotFoundException;
}
```

**Implementation Strategy:**
1. Query DynamicConfigurationManager for pipeline config
2. Locate step configuration
3. Create appropriate executor based on step type
4. Cache executors for performance

### 3. PipelineStepGrpcProcessor

Handles gRPC communication with module processors.

**Key Responsibilities:**
- Service discovery via Consul
- gRPC channel management
- Request/response transformation
- Error handling and retries

**Implementation Details:**
```java
@Singleton
public class PipelineStepGrpcProcessorImpl implements PipelineStepGrpcProcessor {
    private final DiscoveryClient discoveryClient;
    private final GrpcChannelManager channelManager;
    
    public ProcessResponse processStep(PipeStream pipeStream, String stepName) {
        // 1. Discover service instance
        ServiceInstance instance = discoverService(stepName);
        
        // 2. Get or create gRPC channel
        ManagedChannel channel = channelManager.getChannel(instance);
        
        // 3. Create stub and make call
        PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
            PipeStepProcessorGrpc.newBlockingStub(channel);
            
        // 4. Transform and send request
        return stub.processData(pipeStream);
    }
}
```

### 4. RouteData Record

Encapsulates routing decisions for pipeline steps.

```java
public record RouteData(
    String targetStepName,
    StepType stepType,
    String grpcServiceName,
    String kafkaTopic,
    Map<String, String> metadata
) {
    public boolean isGrpcRoute() {
        return stepType == StepType.GRPC && grpcServiceName != null;
    }
    
    public boolean isKafkaRoute() {
        return stepType == StepType.KAFKA && kafkaTopic != null;
    }
}
```

### 5. PipeStreamStateBuilder

Manages state transitions and pipeline execution flow.

**Key Features:**
- Builds new PipeStream states
- Tracks execution history
- Manages error states
- Handles conditional routing

```java
public class PipeStreamStateBuilder {
    private final PipeStream original;
    private final List<ProcessingLog> logs = new ArrayList<>();
    
    public PipeStreamStateBuilder withDocument(Document doc) {
        // Update document
        return this;
    }
    
    public PipeStreamStateBuilder addLog(String message, LogLevel level) {
        logs.add(new ProcessingLog(message, level, Instant.now()));
        return this;
    }
    
    public PipeStreamStateBuilder moveToStep(String stepName) {
        // Update current step
        return this;
    }
    
    public PipeStream build() {
        // Build final PipeStream with all updates
    }
}
```

## Integration Points

### Consul Integration

1. **Configuration Loading**
   - Pipeline configurations
   - Module configurations
   - Service discovery data

2. **Dynamic Updates**
   - Watch for configuration changes
   - Hot reload without restart
   - Version tracking

### Kafka Integration

1. **Producer Configuration**
   - Schema registry integration
   - Serialization setup
   - Error handling

2. **Consumer Configuration**
   - Consumer group management
   - Partition assignment
   - Offset management

### Schema Registry Integration

1. **Protobuf Schema Management**
   - Schema registration on startup
   - Version compatibility checks
   - Schema evolution support

2. **Validation**
   - Message validation before send
   - Deserialization validation
   - Error reporting

## Error Handling Strategy

### Retry Policies

1. **gRPC Retries**
   - Exponential backoff
   - Circuit breaker pattern
   - Fallback to alternative instances

2. **Kafka Retries**
   - Producer retries with backoff
   - Consumer retry topics
   - Dead letter queues

### Error Propagation

1. **Structured Errors**
   - Error codes and messages
   - Correlation IDs
   - Stack trace preservation

2. **Error Recovery**
   - Automatic recovery attempts
   - Manual intervention options
   - State restoration

## Performance Optimizations

### Connection Pooling

1. **gRPC Channel Pooling**
   - Reuse channels across requests
   - Lazy initialization
   - Periodic health checks

2. **Kafka Producer Pooling**
   - Share producers across threads
   - Batch message sending
   - Compression configuration

### Caching Strategies

1. **Configuration Caching**
   - Local cache with TTL
   - Event-based invalidation
   - Lazy refresh

2. **Service Discovery Caching**
   - Cache discovered instances
   - Background refresh
   - Failover lists

## Testing Approach

### Unit Testing

1. **Component Isolation**
   - Mock external dependencies
   - Test business logic
   - Edge case coverage

2. **Contract Testing**
   - Verify interface contracts
   - Schema compatibility
   - API versioning

### Integration Testing

1. **End-to-End Tests**
   - Full pipeline execution
   - Multi-module coordination
   - Error scenario testing

2. **Performance Testing**
   - Load testing
   - Latency measurements
   - Resource utilization

## Monitoring and Observability

### Metrics Collection

1. **Business Metrics**
   - Messages processed
   - Processing latency
   - Error rates

2. **Technical Metrics**
   - JVM metrics
   - gRPC metrics
   - Kafka metrics

### Distributed Tracing

1. **Trace Propagation**
   - Correlation ID generation
   - Context propagation
   - Span creation

2. **Trace Storage**
   - Integration with tracing backends
   - Sampling strategies
   - Retention policies