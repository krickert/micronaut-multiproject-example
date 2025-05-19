
# Detailed Implementation Plan for PipeStreamEngineImpl and Related Components

Based on the project exploration, I'll outline a comprehensive plan for implementing the PipeStreamEngineImpl.java and related components. This plan focuses on creating proper Java interfaces with their implementations and utilizing the consul-config structure for routing.

## Overview of Components to Implement

1. **PipeStreamEngineImpl** - Main implementation of the PipeStreamEngine gRPC service
2. **PipeStreamGrpcForwarder** - Already exists, handles forwarding to gRPC services
3. **PipelineStepGrpcProcessor** - New component for processing pipeline steps via gRPC
4. **PipeStepExecutor** - Interface and factory for executing pipeline steps
5. **PipeStreamStateBuilder** - Builder for managing pipeline stream state

## Component Details and Implementation Plan

### 1. PipeStepExecutor Interface and Implementation

This component provides an abstraction for executing pipeline steps, allowing for different execution strategies.

**Location**: 
- `com.krickert.search.pipeline.step.PipeStepExecutor` (Interface)
- `com.krickert.search.pipeline.step.impl.GrpcPipeStepExecutor` (Implementation)

**Interface Definition**:
```java
/**
 * Interface for executing pipeline steps.
 * This provides an abstraction over different execution strategies (gRPC, internal, etc.)
 */
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
     * 
     * @return The step name
     */
    String getStepName();

    /**
     * Get the type of step this executor handles.
     * 
     * @return The step type
     */
    StepType getStepType();
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStepExecutor that executes steps via gRPC.
 */
@Singleton
public class GrpcPipeStepExecutor implements PipeStepExecutor {
    private final PipelineStepGrpcProcessor grpcProcessor;
    private final String stepName;
    private final StepType stepType;

    @Inject
    public GrpcPipeStepExecutor(PipelineStepGrpcProcessor grpcProcessor, 
                               String stepName, 
                               StepType stepType) {
        this.grpcProcessor = grpcProcessor;
        this.stepName = stepName;
        this.stepType = stepType;
    }

    @Override
    public PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException {
        try {
            ProcessResponse response = grpcProcessor.processStep(pipeStream, stepName);
            // Transform response back to PipeStream
            return transformResponseToPipeStream(pipeStream, response);
        } catch (Exception e) {
            throw new PipeStepExecutionException("Error executing gRPC step: " + stepName, e);
        }
    }

    @Override
    public String getStepName() {
        return stepName;
    }

    @Override
    public StepType getStepType() {
        return stepType;
    }

    private PipeStream transformResponseToPipeStream(PipeStream original, ProcessResponse response) {
        // Implementation to transform ProcessResponse back to PipeStream
        // This would update the document, add logs, etc.
        return original.toBuilder()
            .setDocument(response.getOutputDoc())
            // Add other transformations as needed
            .build();
    }
}
```

### 2. PipeStepExecutorFactory Interface and Implementation

This factory creates the appropriate executor for a given step.

**Location**: 
- `com.krickert.search.pipeline.step.PipeStepExecutorFactory` (Interface)
- `com.krickert.search.pipeline.step.impl.PipeStepExecutorFactoryImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Factory for creating PipeStepExecutor instances.
 */
public interface PipeStepExecutorFactory {
    /**
     * Get an executor for the specified pipeline and step.
     * 
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The appropriate executor for the step
     * @throws PipeStepExecutorNotFoundException If no executor can be found for the step
     */
    PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException;
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStepExecutorFactory that creates executors based on pipeline configuration.
 */
@Singleton
public class PipeStepExecutorFactoryImpl implements PipeStepExecutorFactory {
    private final DynamicConfigurationManager configManager;
    private final PipelineStepGrpcProcessor grpcProcessor;

    @Inject
    public PipeStepExecutorFactoryImpl(DynamicConfigurationManager configManager,
                                      PipelineStepGrpcProcessor grpcProcessor) {
        this.configManager = configManager;
        this.grpcProcessor = grpcProcessor;
    }

    @Override
    public PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException {
        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
        if (pipelineConfig.isEmpty()) {
            throw new PipeStepExecutorNotFoundException("Pipeline not found: " + pipelineName);
        }

        // Get the step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
        if (stepConfig == null) {
            throw new PipeStepExecutorNotFoundException("Step not found: " + stepName + " in pipeline: " + pipelineName);
        }

        // Create the appropriate executor based on step type
        if (stepConfig.processorInfo().grpcServiceName() != null) {
            return new GrpcPipeStepExecutor(grpcProcessor, stepName, stepConfig.stepType());
        } else if (stepConfig.processorInfo().internalProcessorBeanName() != null) {
            // For internal processors, we would use a different executor implementation
            // This would be implemented in a future task
            throw new PipeStepExecutorNotFoundException("Internal processors not yet implemented");
        } else {
            throw new PipeStepExecutorNotFoundException("No processor info found for step: " + stepName);
        }
    }
}
```

### 3. PipelineStepGrpcProcessor Interface and Implementation

This component handles the processing of pipeline steps via gRPC.

**Location**: 
- `com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor` (Interface)
- `com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessorImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Interface for processing pipeline steps via gRPC.
 */
public interface PipelineStepGrpcProcessor {
    /**
     * Process a step via gRPC.
     * 
     * @param pipeStream The input PipeStream to process
     * @param stepName The name of the step to process
     * @return The response from the gRPC service
     * @throws PipeStepProcessingException If an error occurs during processing
     */
    ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException;
}
```

**Implementation**:
```java
/**
 * Implementation of PipelineStepGrpcProcessor that connects to gRPC services.
 */
@Singleton
public class PipelineStepGrpcProcessorImpl implements PipelineStepGrpcProcessor {
    private final DiscoveryClient discoveryClient;
    private final DynamicConfigurationManager configManager;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    @Inject
    public PipelineStepGrpcProcessorImpl(DiscoveryClient discoveryClient,
                                        DynamicConfigurationManager configManager) {
        this.discoveryClient = discoveryClient;
        this.configManager = configManager;
    }

    @Override
    public ProcessResponse processStep(PipeStream pipeStream, String stepName) throws PipeStepProcessingException {
        try {
            // Get the pipeline configuration
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipeStream.getCurrentPipelineName());
            if (pipelineConfig.isEmpty()) {
                throw new PipeStepProcessingException("Pipeline not found: " + pipeStream.getCurrentPipelineName());
            }

            // Get the step configuration
            PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(stepName);
            if (stepConfig == null) {
                throw new PipeStepProcessingException("Step not found: " + stepName);
            }

            // Get the gRPC service name
            String grpcServiceName = stepConfig.processorInfo().grpcServiceName();
            if (grpcServiceName == null) {
                throw new PipeStepProcessingException("No gRPC service name found for step: " + stepName);
            }

            // Get or create the channel
            ManagedChannel channel = getOrCreateChannel(grpcServiceName);

            // Create the stub
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);

            // Create the request
            ProcessRequest request = createProcessRequest(pipeStream, stepConfig);

            // Call the service
            return stub.processData(request);
        } catch (Exception e) {
            throw new PipeStepProcessingException("Error processing step: " + stepName, e);
        }
    }

    private ManagedChannel getOrCreateChannel(String serviceName) {
        return channelCache.computeIfAbsent(serviceName, name -> {
            // Use discovery client to find the service
            List<ServiceInstance> instances = Mono.from(discoveryClient.getInstances(name))
                .block(Duration.ofSeconds(10));

            if (instances == null || instances.isEmpty()) {
                throw new PipeStepProcessingException("Service not found: " + name);
            }

            ServiceInstance instance = instances.get(0);
            return ManagedChannelBuilder.forAddress(instance.getHost(), instance.getPort())
                .usePlaintext()
                .build();
        });
    }

    private ProcessRequest createProcessRequest(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        // Create service metadata
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
            .setPipelineName(pipeStream.getCurrentPipelineName())
            .setPipeStepName(stepConfig.stepName())
            .setStreamId(pipeStream.getStreamId())
            .setCurrentHopNumber(pipeStream.getCurrentHopNumber())
            .addAllHistory(pipeStream.getHistoryList())
            .putAllContextParams(pipeStream.getContextParamsMap())
            .build();

        // Create process configuration
        ProcessConfiguration config = ProcessConfiguration.newBuilder()
            .putAllConfigParams(stepConfig.customConfig().configParams())
            .build();

        // Create the request
        return ProcessRequest.newBuilder()
            .setDocument(pipeStream.getDocument())
            .setConfig(config)
            .setMetadata(metadata)
            .build();
    }
}
```

### 4. RouteData Record

This record is used to represent routing information for both gRPC and Kafka transports.

**Location**: `com.krickert.search.pipeline.engine.common.RouteData`

**Definition**:
```java
/**
 * Route data for forwarding PipeStream to the next step.
 * This record is used by both gRPC and Kafka forwarders.
 */
@Builder
public record RouteData(
        String targetPipeline,
        String nextTargetStep,
        String destination,
        String streamId,
        TransportType transportType,
        @Value("${grpc.client.plaintext:true}") boolean usePlainText) {
}
```

### 5. PipeStreamStateBuilder Interface and Implementation

This component manages the three states (request, present, response) during processing and handles routing.

**Location**: 
- `com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder` (Interface)
- `com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl` (Implementation)

**Interface Definition**:
```java
/**
 * Builder for managing pipeline stream state during processing.
 */
public interface PipeStreamStateBuilder {
    /**
     * Get the original request state.
     * 
     * @return The immutable request state
     */
    PipeStream getRequestState();

    /**
     * Get the current present state.
     * 
     * @return The mutable present state
     */
    PipeStream.Builder getPresentState();

    /**
     * Update the hop number in the present state.
     * 
     * @param hopNumber The new hop number
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withHopNumber(int hopNumber);

    /**
     * Set the target step in the present state.
     * 
     * @param stepName The target step name
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withTargetStep(String stepName);

    /**
     * Add a log entry to the present state.
     * 
     * @param log The log entry to add
     * @return This builder for chaining
     */
    PipeStreamStateBuilder addLogEntry(String log);

    /**
     * Calculate the next routes for the current state.
     * 
     * @return A list of route data for the next steps
     */
    List<RouteData> calculateNextRoutes();

    /**
     * Build the final response state.
     * 
     * @return The immutable response state
     */
    PipeStream build();
}
```

**Implementation**:
```java
/**
 * Implementation of PipeStreamStateBuilder that manages pipeline stream state and routing.
 */
@Singleton
/**
 * Route data for forwarding PipeStream to the next step.
 * This record is used by both gRPC and Kafka forwarders.
 */
@Builder
public record RouteData(
        String targetPipeline,
        String nextTargetStep,
        String destination,
        String streamId,
        TransportType transportType,
        @Value("${grpc.client.plaintext:true}") boolean usePlainText) {
}

public class PipeStreamStateBuilderImpl implements PipeStreamStateBuilder {
    private final PipeStream requestState;
    private PipeStream.Builder presentState;
    private PipeStream responseState;
    private final DynamicConfigurationManager configManager;
    private final long startTime;
    private long endTime;

    @Inject
    public PipeStreamStateBuilderImpl(PipeStream request, 
                                     DynamicConfigurationManager configManager) {
        this.requestState = request;
        this.presentState = request.toBuilder();
        this.configManager = configManager;
        this.startTime = System.currentTimeMillis();
    }

    @Override
    public PipeStream getRequestState() {
        return requestState;
    }

    @Override
    public PipeStream.Builder getPresentState() {
        return presentState;
    }

    @Override
    public PipeStreamStateBuilder withHopNumber(int hopNumber) {
        presentState.setCurrentHopNumber(hopNumber);
        return this;
    }

    @Override
    public PipeStreamStateBuilder withTargetStep(String stepName) {
        presentState.setTargetStepName(stepName);
        return this;
    }

    @Override
    public PipeStreamStateBuilder addLogEntry(String log) {
        // Add log entry to history
        StepExecutionRecord record = StepExecutionRecord.newBuilder()
            .setHopNumber(presentState.getCurrentHopNumber())
            .setStepName(presentState.getTargetStepName())
            .setStatus("SUCCESS")
            .addProcessorLogs(log)
            .build();
        presentState.addHistory(record);
        return this;
    }

    @Override
    public List<RouteData> calculateNextRoutes() {
        List<RouteData> routes = new ArrayList<>();

        // Get the pipeline configuration
        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(presentState.getCurrentPipelineName());
        if (pipelineConfig.isEmpty()) {
            return routes; // Empty list if pipeline not found
        }

        // Get the current step configuration
        PipelineStepConfig stepConfig = pipelineConfig.get().pipelineSteps().get(presentState.getTargetStepName());
        if (stepConfig == null) {
            return routes; // Empty list if step not found
        }

        // Calculate routes based on outputs
        for (Map.Entry<String, OutputTarget> entry : stepConfig.outputs().entrySet()) {
            String outputKey = entry.getKey();
            OutputTarget target = entry.getValue();

            if (target.transportType() == TransportType.GRPC) {
                // Create gRPC route
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.grpcTransport().serviceName())
                    .streamId(presentState.getStreamId())
                    .build());
            } else if (target.transportType() == TransportType.KAFKA) {
                // Create Kafka route - handled similarly to gRPC since both take PipeStream requests
                // The KafkaForwarder is already asynchronous by default in Micronaut
                routes.add(RouteData.builder()
                    .targetPipeline(presentState.getCurrentPipelineName())
                    .nextTargetStep(target.targetStepName())
                    .destination(target.kafkaTransport().topicName())
                    .streamId(presentState.getStreamId())
                    .transportType(TransportType.KAFKA)
                    .build());
            }
        }

        return routes;
    }

    @Override
    public PipeStream build() {
        endTime = System.currentTimeMillis();
        // Add any final metadata or processing
        // For example, we could add a final history entry with timing information

        // Build the final response
        responseState = presentState.build();
        return responseState;
    }
}
```

### 5. PipeStreamEngineImpl Implementation

This is the central component that implements the PipeStreamEngine gRPC service.

**Location**: `com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl`

**Implementation**:
```java
/**
 * Implementation of the PipeStreamEngine gRPC service.
 */
@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private final PipeStepExecutorFactory executorFactory;
    private final PipeStreamGrpcForwarder grpcForwarder;
    private final KafkaForwarder kafkaForwarder;
    private final DynamicConfigurationManager configManager;

    @Inject
    public PipeStreamEngineImpl(PipeStepExecutorFactory executorFactory,
                               PipeStreamGrpcForwarder grpcForwarder,
                               KafkaForwarder kafkaForwarder,
                               DynamicConfigurationManager configManager) {
        this.executorFactory = executorFactory;
        this.grpcForwarder = grpcForwarder;
        this.kafkaForwarder = kafkaForwarder;
        this.configManager = configManager;
    }

    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        try {
            // First, validate that the target step is set in the request
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                throw new IllegalArgumentException("Target step name must be set in the request");
            }

            // Create state builder
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, configManager);

            // Increment hop number
            stateBuilder.withHopNumber(request.getCurrentHopNumber() + 1);

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                request.getCurrentPipelineName(), 
                request.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(stateBuilder.getPresentState().build());

            // Update the state with the processed stream
            stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Build the final response
            PipeStream response = stateBuilder.build();

            // Add route information to the response
            // This is for testing purposes only - we don't actually forward the data
            PipeStream.Builder responseWithRoutes = response.toBuilder();

            // Add route information to context params
            for (int i = 0; i < routes.size(); i++) {
                RouteData route = routes.get(i);
                responseWithRoutes.putContextParams("route_" + i + "_target_pipeline", route.targetPipeline());
                responseWithRoutes.putContextParams("route_" + i + "_next_step", route.nextTargetStep());
                responseWithRoutes.putContextParams("route_" + i + "_destination", route.destination());
                responseWithRoutes.putContextParams("route_" + i + "_transport_type", route.transportType().toString());
            }

            // Send response with route information
            responseObserver.onNext(responseWithRoutes.build());
            responseObserver.onCompleted();

            // Note: We do NOT forward to next steps in testPipeStream
            // This method is for testing only and returns where it would have forwarded
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    @Override
    public void processPipe(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            // First, validate that the target step is set in the request
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                throw new IllegalArgumentException("Target step name must be set in the request");
            }

            // Send empty response immediately after validation
            // This is the default for pipeline-to-pipeline communication
            // We don't have to wait for it to process to let the caller know we're good to go
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

            // Create state builder
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, configManager);

            // Increment hop number
            stateBuilder.withHopNumber(request.getCurrentHopNumber() + 1);

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                request.getCurrentPipelineName(), 
                request.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(stateBuilder.getPresentState().build());

            // Update the state with the processed stream
            stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Build the final response
            PipeStream response = stateBuilder.build();

            // Forward to next steps - both gRPC and Kafka are handled similarly
            // Both take PipeStream requests and both run asynchronously
            for (RouteData route : routes) {
                // Create a new PipeStream for each destination with the correct target step
                PipeStream.Builder destinationPipeBuilder = response.toBuilder()
                    .setTargetStepName(route.nextTargetStep());

                if (route.transportType() == TransportType.KAFKA) {
                    // Forward to Kafka - this is asynchronous by default in Micronaut
                    // We need to set the target step for each Kafka destination
                    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
                } else {
                    // Forward to gRPC
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
                }
            }
        } catch (Exception e) {
            responseObserver.onError(e);
        }
    }

    @Override
    public void processConnectorDoc(ConnectorRequest request, StreamObserver<ConnectorResponse> responseObserver) {
        try {
            // Get the pipeline configuration for the source identifier
            // This would involve looking up the appropriate pipeline based on the source identifier
            // For now, we'll use a simple approach

            Optional<PipelineClusterConfig> clusterConfig = configManager.getCurrentPipelineClusterConfig();
            if (clusterConfig.isEmpty()) {
                throw new RuntimeException("No cluster configuration found");
            }

            // Use the default pipeline if available, otherwise use the first one
            String pipelineName = clusterConfig.get().defaultPipelineName();
            if (pipelineName == null && !clusterConfig.get().pipelineGraphConfig().pipelines().isEmpty()) {
                pipelineName = clusterConfig.get().pipelineGraphConfig().pipelines().keySet().iterator().next();
            }

            if (pipelineName == null) {
                throw new RuntimeException("No pipeline found for connector");
            }

            // Create a new PipeStream
            String streamId = request.getSuggestedStreamId().isEmpty() ? 
                UUID.randomUUID().toString() : request.getSuggestedStreamId();

            PipeStream.Builder pipeStreamBuilder = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(request.getDocument())
                .setCurrentPipelineName(pipelineName)
                .setCurrentHopNumber(0)
                .putAllContextParams(request.getInitialContextParamsMap());

            // Get the first step in the pipeline
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
            if (pipelineConfig.isEmpty()) {
                throw new RuntimeException("Pipeline not found: " + pipelineName);
            }

            // Find the first step in the pipeline
            // In a real implementation, we would have a way to determine the entry point
            // For now, we'll just use the first step in the pipeline
            if (pipelineConfig.get().pipelineSteps().isEmpty()) {
                throw new RuntimeException("Pipeline has no steps: " + pipelineName);
            }

            String firstStepName = pipelineConfig.get().pipelineSteps().keySet().iterator().next();
            pipeStreamBuilder.setTargetStepName(firstStepName);

            // Create the response
            ConnectorResponse response = ConnectorResponse.newBuilder()
                .setStreamId(streamId)
                .setAccepted(true)
                .setMessage("Ingestion accepted for stream ID " + streamId + ", targeting pipeline " + pipelineName)
                .build();

            // Send the response
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            // Process the document asynchronously
            PipeStream pipeStream = pipeStreamBuilder.build();

            // Get executor for the target step
            PipeStepExecutor executor = executorFactory.getExecutor(
                pipeStream.getCurrentPipelineName(), 
                pipeStream.getTargetStepName());

            // Execute the step
            PipeStream processedStream = executor.execute(pipeStream);

            // Create state builder for routing
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            // Calculate next routes
            List<RouteData> routes = stateBuilder.calculateNextRoutes();

            // Forward to next steps - both gRPC and Kafka are handled similarly
            // Both take PipeStream requests and both run asynchronously
            for (RouteData route : routes) {
                // Create a new PipeStream for each destination with the correct target step
                PipeStream.Builder destinationPipeBuilder = processedStream.toBuilder()
                    .setTargetStepName(route.nextTargetStep());

                if (route.transportType() == TransportType.KAFKA) {
                    // Forward to Kafka - this is asynchronous by default in Micronaut
                    // We need to set the target step for each Kafka destination
                    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
                } else {
                    // Forward to gRPC
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
                }
            }
        } catch (Exception e) {
            // Create error response
            ConnectorResponse response = ConnectorResponse.newBuilder()
                .setAccepted(false)
                .setMessage("Error processing connector document: " + e.getMessage())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
```

## Implementation Tasks and Next Steps

### Task 1: Create Interface Definitions

1. Create the `PipeStepExecutor` interface in `com.krickert.search.pipeline.step`
2. Create the `PipeStepExecutorFactory` interface in `com.krickert.search.pipeline.step`
3. Create the `PipelineStepGrpcProcessor` interface in `com.krickert.search.pipeline.step.grpc`
4. Create the `PipeStreamStateBuilder` interface in `com.krickert.search.pipeline.engine.state`
5. Create necessary exception classes:
   - `PipeStepExecutionException`
   - `PipeStepExecutorNotFoundException`
   - `PipeStepProcessingException`

### Task 2: Implement Interface Implementations

1. Create the `GrpcPipeStepExecutor` implementation in `com.krickert.search.pipeline.step.impl`
2. Create the `PipeStepExecutorFactoryImpl` implementation in `com.krickert.search.pipeline.step.impl`
3. Create the `PipelineStepGrpcProcessorImpl` implementation in `com.krickert.search.pipeline.step.grpc`
4. Create the `PipeStreamStateBuilderImpl` implementation in `com.krickert.search.pipeline.engine.state`

### Task 3: Implement PipeStreamEngineImpl

1. Create the `PipeStreamEngineImpl` class in `com.krickert.search.pipeline.engine.grpc`
2. Implement the three methods:
   - `testPipeStream`
   - `processPipe`
   - `processConnectorDoc`

### Task 4: Create Unit Tests

1. Create unit tests for each interface implementation
2. Create integration tests that use the chunker and echo implementations
3. Test the routing logic with different pipeline configurations

### Task 5: Future Enhancements

1. Implement Kafka routing in the `PipeStreamStateBuilder`
2. Add support for internal processors in the `PipeStepExecutorFactory`
3. Implement error handling and retry logic
4. Add metrics and monitoring
5. Implement configuration-driven setup for pipeline steps

## Using Consul Configuration for Routing

The implementation uses the DynamicConfigurationManager to access pipeline configurations from Consul. This manager provides access to:

1. **PipelineClusterConfig** - The top-level configuration for a cluster of pipelines
2. **PipelineGraphConfig** - A container for pipeline configurations
3. **PipelineConfig** - Configuration for a single pipeline
4. **PipelineStepConfig** - Configuration for a single step in a pipeline

The routing logic in the PipeStreamStateBuilder uses these configurations to determine where to route the processed data:

1. Get the pipeline configuration using `configManager.getPipelineConfig(pipelineName)`
2. Get the step configuration using `pipelineConfig.pipelineSteps().get(stepName)`
3. Examine the step's outputs to determine the next steps:
   ```java
   for (Map.Entry<String, OutputTarget> entry : stepConfig.outputs().entrySet()) {
       String outputKey = entry.getKey();
       OutputTarget target = entry.getValue();

       if (target.transportType() == TransportType.GRPC) {
           // Create gRPC route
           routes.add(RouteData.builder()
               .targetPipeline(presentState.getCurrentPipelineName())
               .nextTargetStep(target.targetStepName())
               .destination(target.grpcTransport().serviceName())
               .streamId(presentState.getStreamId())
               .build());
       } else if (target.transportType() == TransportType.KAFKA) {
           // Handle Kafka routing
       }
   }
   ```

This approach ensures that routing is driven by configuration, making it flexible and adaptable to changes without code modifications.

It's important to note that both gRPC and Kafka transports are treated similarly in the routing logic. They both take PipeStream requests and both run asynchronously. The only difference is the transport mechanism (gRPC vs Kafka). The KafkaForwarder is, by default, a future task in Micronaut that runs asynchronously, just like the gRPC calls. This consistent treatment simplifies the code and makes it easier to understand and maintain.

A critical aspect of the implementation is ensuring that each destination receives a PipeStream with its specific target step. This is essential for fan-out scenarios where a document might be processed in multiple ways (e.g., chunked in 3 different ways and embedded with 2 different embedders, resulting in 6 new fields). Each service needs to know which specific implementation it should run, which is determined by the target_step_name field in the PipeStream. 

For this reason, we create a new PipeStream for each destination with the correct target step before forwarding:
```java
// Create a new PipeStream for each destination with the correct target step
PipeStream.Builder destinationPipeBuilder = response.toBuilder()
    .setTargetStepName(route.nextTargetStep());

// Then forward to the appropriate transport
if (route.transportType() == TransportType.KAFKA) {
    kafkaForwarder.forwardToKafka(destinationPipeBuilder.build(), route.destination());
} else {
    grpcForwarder.forwardToGrpc(destinationPipeBuilder, route);
}
```

This ensures that each service receives a PipeStream with its specific target step, allowing it to execute the correct implementation.

This plan provides a structured approach to implementing the required components while focusing on creating a clean, maintainable architecture that follows the project's design principles.

## Using PipelineModuleMap for Module Management

The PipelineModuleMap is a critical component in the system that serves as a catalog of available pipeline modules. It is defined in the PipelineClusterConfig and contains information about all the modules that can be used in the pipeline.

### Structure of PipelineModuleMap

The PipelineModuleMap contains a map of available modules, where each entry maps a module's implementationId to its definition:

```java
public record PipelineModuleMap(
        @JsonProperty("availableModules") Map<String, PipelineModuleConfiguration> availableModules
) {
    // Canonical constructor making map unmodifiable and handling nulls
    public PipelineModuleMap {
        availableModules = (availableModules == null) ? Collections.emptyMap() : Map.copyOf(availableModules);
    }
}
```

Each PipelineModuleConfiguration contains:
- implementationName: A user-friendly display name (e.g., "Document Ingest Service")
- implementationId: A unique identifier (e.g., "document-ingest-service")
- customConfigSchemaReference: A reference to the schema for the module's configuration

### How to Utilize PipelineModuleMap

The PipelineModuleMap serves several important purposes in the system:

1. **Module Registration**: It registers all available modules in the system, making them discoverable and usable in pipeline configurations.

2. **Configuration Validation**: When a pipeline step references a module via its processorInfo.grpcServiceName, the system can validate that the module exists in the PipelineModuleMap.

3. **Schema Validation**: The customConfigSchemaReference in each module configuration points to the schema that defines the structure for the module's custom configuration. This allows the system to validate that the custom configuration provided in a pipeline step conforms to the expected schema.

4. **Service Discovery**: The implementationId in the module configuration typically matches the service name used for discovery in Consul, allowing the system to locate and connect to the appropriate service.

Example usage in code:

```java
// Get the module configuration for a step
Optional<PipelineClusterConfig> clusterConfig = configManager.getCurrentPipelineClusterConfig();
if (clusterConfig.isPresent()) {
    PipelineModuleMap moduleMap = clusterConfig.get().pipelineModuleMap();
    String serviceId = stepConfig.processorInfo().grpcServiceName();
    PipelineModuleConfiguration moduleConfig = moduleMap.availableModules().get(serviceId);

    if (moduleConfig != null) {
        // Use the module configuration
        String moduleName = moduleConfig.implementationName();
        SchemaReference schemaRef = moduleConfig.customConfigSchemaReference();

        // Validate custom configuration against schema
        if (schemaRef != null) {
            Optional<String> schemaContent = configManager.getSchemaContent(schemaRef);
            if (schemaContent.isPresent()) {
                // Validate custom configuration against schema
                boolean valid = validateConfig(stepConfig.customConfig(), schemaContent.get());
                if (!valid) {
                    throw new ConfigurationException("Invalid custom configuration for module: " + moduleName);
                }
            }
        }
    }
}
```

## Admin Features Left to Implement

### 1. Validating and Registering New Modules

To validate and register new modules, the following features need to be implemented:

1. **Module Validation Service**:
   - Verify that the module implements the required gRPC interface (PipeStepProcessor)
   - Validate that the module's custom configuration schema is valid
   - Check that the module's implementation ID is unique
   - Ensure that the module's service name is registered in Consul

2. **Module Registration API**:
   - Create a REST API for registering new modules
   - Add endpoints for:
     - Registering a new module
     - Updating an existing module
     - Removing a module
     - Listing all registered modules
   - Implement authentication and authorization for these endpoints

3. **Schema Registry Integration**:
   - Integrate with the schema registry to store and retrieve module configuration schemas
   - Implement versioning for schemas to support backward compatibility
   - Add validation of custom configurations against schemas

### 2. Creating Containers that Package Modules with the Engine

To create containers that package modules with the engine, the following features need to be implemented:

1. **Container Build System**:
   - Create a build system that packages a module with the engine in a single container
   - Support both Java and Python modules
   - Include necessary dependencies and configuration

2. **Java Module Container Example**:
   ```dockerfile
   # Base image with Java and Micronaut
   FROM openjdk:21-slim

   # Install dependencies
   RUN apt-get update && apt-get install -y curl

   # Set up environment
   ENV MICRONAUT_ENVIRONMENTS=prod

   # Copy the engine and module JAR files
   COPY build/libs/yappy-engine-*.jar /app/engine.jar
   COPY build/libs/module-*.jar /app/module.jar

   # Copy configuration
   COPY src/main/resources/application.yml /app/

   # Expose ports
   EXPOSE 8080 50051

   # Start the application
   CMD ["java", "-jar", "/app/engine.jar"]
   ```

3. **Python Module Container Example**:
   ```dockerfile
   # Base image with Python
   FROM python:3.9-slim

   # Install dependencies
   RUN pip install grpcio grpcio-tools protobuf

   # Copy the engine JAR file
   COPY build/libs/yappy-engine-*.jar /app/engine.jar

   # Copy the Python module
   COPY src/main/python /app/module

   # Copy configuration
   COPY src/main/resources/application.yml /app/

   # Install Java for running the engine
   RUN apt-get update && apt-get install -y openjdk-21-jre-headless

   # Expose ports
   EXPOSE 8080 50051

   # Start both the engine and the Python module
   CMD ["sh", "-c", "java -jar /app/engine.jar & python /app/module/main.py"]
   ```

4. **Module Project Generator**:
   - Enhance the existing project generator to create module projects with Docker support
   - Add templates for both Java and Python modules
   - Include CI/CD pipeline configurations for building and deploying containers

### 3. Keeping Module Information in Consul Configuration

To keep module information in Consul configuration, the following features need to be implemented:

1. **Module Configuration Management**:
   - Implement a service for managing module configurations in Consul
   - Support CRUD operations for module configurations
   - Implement versioning for module configurations
   - Add validation of module configurations against schemas

2. **Live Configuration Updates**:
   - Implement a mechanism for updating module configurations in real-time
   - Add support for rolling updates to minimize downtime
   - Implement a rollback mechanism for failed updates

3. **Module Health Monitoring**:
   - Add health checks for modules
   - Implement automatic failover for unhealthy modules
   - Add metrics collection for module performance

4. **Module Discovery**:
   - Enhance the service discovery mechanism to support module discovery
   - Add support for load balancing across multiple instances of the same module
   - Implement circuit breakers for handling module failures

These admin features will provide a comprehensive system for managing pipeline modules, from validation and registration to deployment and monitoring.

## Kafka Topic Naming Conventions and Consumer Groups

### Kafka Topic Permission Validation

The system enforces strict validation of Kafka topic names to ensure security and consistency. Topic validation happens through the `isKafkaTopicPermitted` method in the `WhitelistValidator` class, which checks if a topic is permitted in two ways:

1. **Explicit Whitelist**: Topics can be explicitly allowed by adding them to the `allowedKafkaTopics` list in the `PipelineClusterConfig`. This is useful for topics that don't follow the naming convention or for cross-pipeline communication.

2. **Naming Convention**: If a topic is not explicitly whitelisted, it must follow a specific naming convention pattern:
   ```
   yappy.pipeline.[pipelineName].step.[stepName].(input|output|error|dead-letter)
   ```

   For example: `yappy.pipeline.search-pipeline.step.document-ingest.output`

   > **Note**: In future implementations, the "yappy." prefix will be removed from the naming convention, making it:
   > ```
   > pipeline.[pipelineName].step.[stepName].(input|output|error|dead-letter)
   > ```
   > This change will be implemented in the "next steps" phase of development.

   There is no restriction on non-standard topics as long as they don't begin with "pipeline." and are included in the `allowedKafkaTopics` list in the PipelineClusterConfig. Any Kafka topic name can be used for cross-pipeline communication or other purposes as long as it's properly authorized.

#### Variable Resolution in Topic Names

The system supports variable substitution in topic names, allowing for dynamic topic naming based on the current context. Variables are specified using the `${variableName}` syntax. The following variables are supported:

- `${clusterName}` - Replaced with the current cluster name
- `${pipelineName}` - Replaced with the current pipeline name
- `${stepName}` - Replaced with the current step name

For example, a topic name defined as `yappy.pipeline.${pipelineName}.step.${stepName}.output` would be resolved to `yappy.pipeline.search-pipeline.step.document-ingest.output` when used in the "document-ingest" step of the "search-pipeline" pipeline.

#### Topic Validation Process

When validating a Kafka topic, the system follows these steps:

1. **Variable Resolution**: Replace any variables in the topic name with their actual values
2. **Explicit Whitelist Check**: Check if either the original or resolved topic name is in the explicit whitelist
3. **Naming Convention Check**: If not explicitly whitelisted, check if the resolved topic matches the naming convention
4. **Context Validation**: Ensure that the topic's pipeline and step names match the current context

This validation process ensures that only authorized topics are used in the pipeline, preventing unauthorized data access or routing.

### Consumer Groups

Consumer groups are organized by pipeline name to ensure that each pipeline instance processes messages independently. The consumer group naming convention follows this pattern:

```
[pipeline-name]-consumer-group
```

For example, if the pipeline name is `search-pipeline`, the consumer group would be `search-pipeline-consumer-group`.

This ensures that if multiple instances of the same pipeline are running, they will all be part of the same consumer group and will not process the same messages multiple times.

### Cross-Pipeline Communication

Cross-pipeline communication is achieved through Kafka topics. When a step in one pipeline needs to send data to a step in another pipeline, it uses a Kafka topic that has been added to the `allowedKafkaTopics` list in the PipelineClusterConfig.

To enable cross-pipeline communication, the topic must be added to the `allowedKafkaTopics` list in the PipelineClusterConfig. This ensures that only authorized topics can be used for cross-pipeline communication.

The workflow for adding a new cross-pipeline communication channel is as follows:

1. Identify the source pipeline and step that will send the data
2. Identify the target pipeline and step that will receive the data (note that non-application targets like external apps are also supported)
3. Request the admin to create a custom topic
4. Update the source step's outputs to include the new topic
5. Update the target step's inputs to include the new topic

A pipeline step CAN listen to more than one topic. The default topic is always listened to, but additional topics can be added to the step's inputs as needed.

### Topic Creation Process

When a pipeline step is created, two Kafka topics are automatically created:
1. The main topic for the step
2. The error topic for the step

The topic creation process is as follows:

1. When a new pipeline step is added to the configuration, the system checks if the required topics exist
2. If the topics do not exist, they are automatically created with the default settings
3. If the topics already exist, the system verifies that they have the correct configuration
4. If the configuration is incorrect, the system logs a warning but does not modify the topics

### Admin Automation for Pausing Consumers

The system includes admin automation for pausing consumers, which is useful during upgrades or emergencies when processing needs to stop. This can be done at two levels:

1. **Consumer Group Level**: Pause all consumers in a specific consumer group
   ```
   POST /api/admin/kafka/consumer-groups/{consumer-group-name}/pause
   ```

2. **Pipeline Level**: Pause all consumers for a specific pipeline
   ```
   POST /api/admin/pipelines/{pipeline-name}/pause
   ```

When consumers are paused, they stop consuming messages from Kafka topics but do not lose their current offsets. This ensures that when they are resumed, they will continue from where they left off.

## Pipeline Configuration Validation

The system includes several validators that ensure the pipeline configuration is valid and consistent. These validators are run when the configuration is loaded and when it is updated.

### 1. CustomConfigSchemaValidator

This validator ensures that custom configurations for pipeline steps conform to their defined JSON schemas:

- Validates custom configurations against schemas defined in the step's `customConfigSchemaId`
- Validates custom configurations against schemas defined in the module's `customConfigSchemaReference`
- Reports errors if a schema is missing or if the configuration doesn't conform to the schema
- Handles cases where a step has a custom configuration but no schema is defined

### 2. InterPipelineLoopValidator

This validator detects loops between different pipelines in the Kafka data flow:

- Builds a directed graph where vertices are pipeline names and edges represent Kafka topic connections
- Detects cycles in the graph using the JohnsonSimpleCycles algorithm
- Reports detailed error messages showing the cycle paths
- Prevents infinite processing loops between pipelines

### 3. IntraPipelineLoopValidator

This validator detects loops within a single pipeline in the Kafka data flow:

- Builds a directed graph for each pipeline where vertices are step names and edges represent Kafka topic connections
- Detects cycles in the graph using the JohnsonSimpleCycles algorithm
- Reports detailed error messages showing the cycle paths
- Prevents infinite processing loops within a pipeline

### 4. ReferentialIntegrityValidator

This validator ensures that all references within the pipeline configuration are valid:

- Validates that pipeline names match their keys in the pipelines map
- Validates that step names match their keys in the steps map
- Ensures there are no duplicate pipeline or step names
- Validates that ProcessorInfo references valid modules in the availableModules map
- Ensures custom configuration schemas are properly referenced
- Validates that Kafka input definitions have valid properties
- Ensures output targets have valid properties
- Validates that target step names in outputs refer to existing steps within the same pipeline
- Handles cross-pipeline references by detecting when a targetStepName contains a dot (indicating a "pipelineName.stepName" format)

### 5. StepTypeValidator

This validator ensures that steps are configured correctly based on their type:

- INITIAL_PIPELINE: Must not have Kafka inputs, should have outputs
- SINK: Should have Kafka inputs or be an internal processor, must not have outputs
- PIPELINE: Logs a warning if it has no Kafka inputs and no outputs (might be orphaned)
- Reports detailed error messages for each validation failure

### 6. WhitelistValidator

This validator ensures that only authorized Kafka topics and gRPC services are used in the pipeline:

- Validates that Kafka topics used in outputs are either in the explicit whitelist or match the naming convention
- Validates that gRPC services referenced in processorInfo and outputs are in the allowedGrpcServices list
- Handles variable substitution in topic names
- Ensures that topics used in a step's outputs are either in the whitelist or match the naming convention

## Future Plans

### Observability

The system will include comprehensive observability features to monitor the health and performance of the pipeline system:

- **Metrics Collection**: Collect metrics on message throughput, processing time, error rates, etc.
- **Distributed Tracing**: Implement distributed tracing to track the flow of messages through the pipeline
- **Logging**: Centralized logging with structured log formats for easy querying
- **Alerting**: Set up alerts for critical errors, performance degradation, etc.
- **Dashboards**: Create dashboards to visualize the health and performance of the system

### Admin UI

The admin UI will provide a user-friendly interface for managing the pipeline system:

- **Pipeline Management**: Create, update, and delete pipelines and steps
- **Configuration Management**: Manage configuration for pipelines, steps, and modules
- **Monitoring**: Monitor the health and performance of the system
- **Troubleshooting**: Identify and resolve issues in the pipeline
- **User Management**: Manage users and permissions

### Search API

The search API will provide a RESTful interface for searching indexed documents:

- **Full-Text Search**: Search for documents using full-text queries
- **Faceted Search**: Filter search results by facets
- **Semantic Search**: Search for documents using semantic queries
- **Hybrid Search**: Combine full-text and semantic search
- **Personalized Search**: Customize search results based on user preferences

### Search API Analytics

The search API analytics will provide insights into how users are interacting with the search API:

- **Query Analytics**: Analyze the types of queries users are making
- **Result Analytics**: Analyze the results users are clicking on
- **User Analytics**: Analyze user behavior and preferences
- **Performance Analytics**: Analyze the performance of the search API
- **Trend Analytics**: Identify trends in search behavior over time

### White Label for Search

The white label for search will allow organizations to customize the search experience with their own branding:

- **Custom UI**: Customize the look and feel of the search interface
- **Custom Domain**: Use a custom domain for the search interface
- **Custom Branding**: Add custom logos, colors, and other branding elements
- **Custom Features**: Enable or disable specific search features
- **Custom Analytics**: Customize the analytics dashboard

### Pipeline Editor

The pipeline editor will provide a visual interface for creating and editing pipelines:

- **Visual Editor**: Drag-and-drop interface for creating pipelines
- **Step Configuration**: Configure pipeline steps with a user-friendly interface
- **Validation**: Validate pipeline configurations before saving
- **Testing**: Test pipelines with sample data
- **Versioning**: Track changes to pipelines over time

### Dashboard

The dashboard will provide a high-level overview of the pipeline system:

- **System Health**: Monitor the health of the pipeline system
- **Performance Metrics**: Track key performance metrics
- **Error Rates**: Monitor error rates across the system
- **Resource Usage**: Track resource usage (CPU, memory, disk, etc.)
- **Throughput**: Monitor message throughput across the system
