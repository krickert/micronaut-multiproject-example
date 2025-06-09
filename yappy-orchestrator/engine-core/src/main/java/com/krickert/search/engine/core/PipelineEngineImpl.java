package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.health.model.HealthService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.search.model.util.ProcessingBuffer;
import com.krickert.search.model.util.ProcessingBufferFactory;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.StepExecutionRecord;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implementation of PipelineEngine that uses Consul service discovery
 * to find and execute pipeline steps on registered modules.
 * 
 * This implementation is temporary for testing. The real implementation
 * will process PipeStream messages as defined in the interface.
 */
@Singleton
public class PipelineEngineImpl implements PipelineEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(PipelineEngineImpl.class);
    
    private final ConsulClient consulClient;
    private final String clusterName;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final DynamicConfigurationManager configManager;
    
    // Processing buffers for capturing test data - one set per pipeline step
    private final Map<String, ProcessingBuffer<ProcessRequest>> requestBuffers = new ConcurrentHashMap<>();
    private final Map<String, ProcessingBuffer<ProcessResponse>> responseBuffers = new ConcurrentHashMap<>();
    private final Map<String, ProcessingBuffer<com.krickert.search.model.PipeDoc>> pipeDocBuffers = new ConcurrentHashMap<>();
    
    // Sampling configuration
    private final boolean bufferEnabled;
    private final int bufferCapacity;
    private final int bufferPrecision;
    private final double sampleRate;
    
    @Inject
    public PipelineEngineImpl(
            ConsulClient consulClient,
            DynamicConfigurationManager configManager,
            @Value("${engine.cluster.name:default-cluster}") String clusterName,
            @Value("${engine.test-data-buffer.enabled:false}") boolean bufferEnabled,
            @Value("${engine.test-data-buffer.capacity:200}") int bufferCapacity,
            @Value("${engine.test-data-buffer.precision:3}") int bufferPrecision,
            @Value("${engine.test-data-buffer.sample-rate:0.1}") double sampleRate) {
        this.consulClient = consulClient;
        this.configManager = configManager;
        this.clusterName = clusterName;
        this.bufferEnabled = bufferEnabled;
        this.bufferCapacity = bufferCapacity;
        this.bufferPrecision = bufferPrecision;
        this.sampleRate = sampleRate;
        
        // Buffers will be created per step as needed
            
        logger.info("PipelineEngineImpl initialized for cluster: {} with buffer={}, capacity={}, sample-rate={}", 
            clusterName, bufferEnabled, bufferCapacity, sampleRate);
    }
    
    @Override
    public Mono<Void> processMessage(PipeStream pipeStream) {
        return Mono.defer(() -> {
            logger.info("Processing PipeStream: streamId={}, pipeline={}, hop={}", 
                pipeStream.getStreamId(), 
                pipeStream.getCurrentPipelineName(),
                pipeStream.getCurrentHopNumber());
                
            // Get pipeline configuration
            return getPipelineConfig(pipeStream.getCurrentPipelineName())
                .flatMap(config -> {
                    // Determine next step based on current position
                    String nextStepName = determineNextStep(pipeStream, config);
                    
                    if (nextStepName == null) {
                        logger.info("Pipeline {} completed for stream {}", 
                            pipeStream.getCurrentPipelineName(), pipeStream.getStreamId());
                        return Mono.empty();
                    }
                    
                    // Find step configuration
                    PipelineStepConfig stepConfig = findStepConfig(config, nextStepName);
                    if (stepConfig == null) {
                        return Mono.error(new IllegalStateException(
                            "Step not found in pipeline config: " + nextStepName));
                    }
                    
                    // Execute the step
                    return executeStep(pipeStream, stepConfig)
                        .flatMap(response -> {
                            if (!response.getSuccess()) {
                                logger.error("Step {} failed for stream {}", 
                                    nextStepName, pipeStream.getStreamId());
                                return handleStepFailure(pipeStream, stepConfig, response);
                            }
                            
                            // Update PipeStream with results and continue
                            PipeStream updatedStream = updatePipeStream(pipeStream, stepConfig, response);
                            
                            // Recursively process next step
                            return processMessage(updatedStream);
                        });
                });
        })
        .doOnError(error -> logger.error("Error processing stream {}: {}", 
            pipeStream.getStreamId(), error.getMessage(), error))
        .then();
    }
    
    @Override
    public Mono<Void> start() {
        logger.info("Starting PipelineEngine for cluster: {}", clusterName);
        return Mono.empty();
    }
    
    @Override
    public Mono<Void> stop() {
        logger.info("Stopping PipelineEngine");
        shutdown();
        return Mono.empty();
    }
    
    @Override
    public boolean isRunning() {
        return true; // TODO: Implement proper state tracking
    }
    
    // Temporary method for testing pipeline execution with simplified test objects
    public Mono<Boolean> executePipelineTest(String pipelineName, List<TestStep> steps) {
        return Flux.fromIterable(steps)
            .concatMap(step -> executeTestStep(step))
            .all(response -> response.getSuccess())
            .doOnNext(allSuccess -> {
                if (allSuccess) {
                    logger.info("Pipeline {} completed successfully with {} steps", 
                        pipelineName, steps.size());
                } else {
                    logger.warn("Pipeline {} had failures", pipelineName);
                }
            });
    }
    
    private Mono<ProcessResponse> executeTestStep(TestStep step) {
        return discoverService(step.serviceName)
            .flatMap(service -> invokeService(service, step.documentId, step.content))
            .doOnSuccess(response -> logger.debug("Step {} completed with success={}", 
                step.serviceName, response.getSuccess()))
            .doOnError(error -> logger.error("Step {} failed", step.serviceName, error));
    }
    
    // Simple test step class
    public static class TestStep {
        public final String serviceName;
        public final String documentId;
        public final String content;
        
        public TestStep(String serviceName, String documentId, String content) {
            this.serviceName = serviceName;
            this.documentId = documentId;
            this.content = content;
        }
    }
    
    private Mono<HealthService> discoverService(String serviceName) {
        return Mono.fromCallable(() -> {
            String clusterServiceName = clusterName + "-" + serviceName;
            
            // First try to get healthy services
            List<HealthService> services = consulClient.getHealthServices(
                clusterServiceName, true, null).getValue();
            
            // If no healthy services, get all services (including unhealthy)
            if (services.isEmpty()) {
                logger.warn("No healthy instances found for service: {}, checking all instances", clusterServiceName);
                services = consulClient.getHealthServices(
                    clusterServiceName, false, null).getValue();
            }
            
            if (services.isEmpty()) {
                throw new IllegalStateException(
                    "No instances found for service: " + clusterServiceName);
            }
            
            HealthService selected = services.get(0);
            logger.info("Discovered service {} at {}:{}", 
                serviceName, 
                selected.getService().getAddress(), 
                selected.getService().getPort());
            
            return selected;
        });
    }
    
    private Mono<ProcessResponse> invokeService(HealthService service, String documentId, String content) {
        return Mono.fromCallable(() -> {
            String address = service.getService().getAddress();
            int port = service.getService().getPort();
            String channelKey = address + ":" + port;
            
            ManagedChannel channel = channelCache.computeIfAbsent(channelKey, k -> 
                ManagedChannelBuilder.forAddress(address, port)
                    .usePlaintext()
                    .build()
            );
            
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);
            
            // Build process configuration for testing
            ProcessConfiguration processConfig = ProcessConfiguration.newBuilder()
                .build();
                
            // Build service metadata
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName(service.getService().getService())
                .setStreamId("test-stream-" + System.currentTimeMillis())
                .setCurrentHopNumber(1)
                .build();
            
            // Create a simple PipeDoc for testing
            com.krickert.search.model.PipeDoc document = com.krickert.search.model.PipeDoc.newBuilder()
                .setId(documentId)
                .setBody(content)
                .build();
            
            ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(document)
                .setConfig(processConfig)
                .setMetadata(metadata)
                .build();
            
            String stepName = "direct-test-" + clusterName; // For direct test calls
            
            // Sample and buffer request if enabled
            if (shouldSample()) {
                getRequestBuffer(stepName).add(request);
                getPipeDocBuffer(stepName).add(document);
            }
            
            ProcessResponse response = stub.processData(request);
            
            // Sample and buffer response if enabled
            if (shouldSample() && response.getSuccess() && response.hasOutputDoc()) {
                getResponseBuffer(stepName).add(response);
                getPipeDocBuffer(stepName).add(response.getOutputDoc());
            }
            
            return response;
        });
    }
    
    private Mono<PipelineConfig> getPipelineConfig(String pipelineName) {
        return Mono.fromCallable(() -> {
            // Get pipeline config
            return configManager.getPipelineConfig(pipelineName)
                .orElseThrow(() -> new IllegalStateException(
                    "Pipeline configuration not found: " + pipelineName));
        });
    }
    
    private String determineNextStep(PipeStream pipeStream, PipelineConfig config) {
        // Check if target step is already set in PipeStream
        if (!pipeStream.getTargetStepName().isEmpty()) {
            return pipeStream.getTargetStepName();
        }
        
        // If no history, use the first step from config
        if (pipeStream.getHistoryCount() == 0) {
            // Get the initial step name from pipeline config
            return config.pipelineSteps().isEmpty() ? null : 
                config.pipelineSteps().keySet().iterator().next();
        }
        
        // Get current step from history
        StepExecutionRecord lastExecution = 
            pipeStream.getHistory(pipeStream.getHistoryCount() - 1);
        
        // Find the current step config and get its output target
        PipelineStepConfig stepConfig = config.pipelineSteps().get(lastExecution.getStepName());
        if (stepConfig != null) {
            // Get default output target
            var defaultOutput = stepConfig.outputs().get("default");
            if (defaultOutput != null) {
                return defaultOutput.targetStepName();
            }
        }
        
        return null; // No next step
    }
    
    private PipelineStepConfig findStepConfig(PipelineConfig config, String stepName) {
        return config.pipelineSteps().get(stepName);
    }
    
    private Mono<ProcessResponse> executeStep(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        // Use gRPC processor for this step
        String serviceName = stepConfig.processorInfo().grpcServiceName();
        if (serviceName == null) {
            return Mono.error(new IllegalStateException(
                "No gRPC service name configured for step: " + stepConfig.stepName()));
        }
        
        return discoverService(serviceName)
            .flatMap(service -> {
                // Build process request from PipeStream
                ProcessRequest request = buildProcessRequest(pipeStream, stepConfig);
                
                // Execute via gRPC
                return invokeService(service, request);
            });
    }
    
    private ProcessRequest buildProcessRequest(PipeStream pipeStream, PipelineStepConfig stepConfig) {
        try {
            // Build process configuration from step config
            ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
            
            // Add custom JSON config if present
            if (stepConfig.customConfig() != null && stepConfig.customConfig().jsonConfig() != null) {
                try {
                    Struct.Builder structBuilder = Struct.newBuilder();
                    JsonFormat.parser().merge(
                        stepConfig.customConfig().jsonConfig().toString(), 
                        structBuilder);
                    configBuilder.setCustomJsonConfig(structBuilder.build());
                } catch (Exception e) {
                    logger.warn("Failed to parse custom JSON config: {}", e.getMessage());
                }
            }
            
            // Add config params
            if (stepConfig.customConfig() != null && stepConfig.customConfig().configParams() != null) {
                configBuilder.putAllConfigParams(stepConfig.customConfig().configParams());
            }
            
            // Build service metadata
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipeStream.getCurrentPipelineName())
                .setPipeStepName(stepConfig.stepName())
                .setStreamId(pipeStream.getStreamId())
                .setCurrentHopNumber(pipeStream.getCurrentHopNumber())
                .addAllHistory(pipeStream.getHistoryList())
                .putAllContextParams(pipeStream.getContextParamsMap())
                .build();
            
            // Build request
            return ProcessRequest.newBuilder()
                .setDocument(pipeStream.getDocument())
                .setConfig(configBuilder.build())
                .setMetadata(metadata)
                .build();
                
        } catch (Exception e) {
            throw new RuntimeException("Failed to build process request", e);
        }
    }
    
    private Mono<ProcessResponse> invokeService(HealthService service, ProcessRequest request) {
        return Mono.fromCallable(() -> {
            String address = service.getService().getAddress();
            int port = service.getService().getPort();
            String channelKey = address + ":" + port;
            
            ManagedChannel channel = channelCache.computeIfAbsent(channelKey, k -> 
                ManagedChannelBuilder.forAddress(address, port)
                    .usePlaintext()
                    .build()
            );
            
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                PipeStepProcessorGrpc.newBlockingStub(channel);
            
            String stepName = request.getMetadata().getPipeStepName();
            
            // Sample and buffer request if enabled
            if (shouldSample()) {
                getRequestBuffer(stepName).add(request);
                getPipeDocBuffer(stepName).add(request.getDocument());
            }
            
            ProcessResponse response = stub.processData(request);
            
            // Sample and buffer response if enabled
            if (shouldSample() && response.getSuccess() && response.hasOutputDoc()) {
                getResponseBuffer(stepName).add(response);
                getPipeDocBuffer(stepName).add(response.getOutputDoc());
            }
            
            return response;
        });
    }
    
    private PipeStream updatePipeStream(PipeStream original, PipelineStepConfig stepConfig, ProcessResponse response) {
        // Build execution record for this step
        StepExecutionRecord executionRecord = StepExecutionRecord.newBuilder()
            .setHopNumber(original.getCurrentHopNumber() + 1)
            .setStepName(stepConfig.stepName())
            .setStartTime(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .setEndTime(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .setStatus(response.getSuccess() ? "SUCCESS" : "FAILURE")
            .addAllProcessorLogs(response.getProcessorLogsList())
            .build();
        
        // Update PipeStream
        PipeStream.Builder builder = original.toBuilder()
            .setCurrentHopNumber(original.getCurrentHopNumber() + 1)
            .addHistory(executionRecord);
        
        // Update document if response has output
        if (response.hasOutputDoc()) {
            builder.setDocument(response.getOutputDoc());
        }
        
        // Add any error data if step failed
        if (!response.getSuccess() && response.hasErrorDetails()) {
            // Would need to convert error details to ErrorData protobuf
            logger.warn("Step failure error details not yet fully implemented");
        }
        
        return builder.build();
    }
    
    private Mono<Void> handleStepFailure(PipeStream pipeStream, PipelineStepConfig stepConfig, ProcessResponse response) {
        // For now, just log and stop processing
        logger.error("Step {} failed for stream {}. Error details: {}", 
            stepConfig.stepName(), 
            pipeStream.getStreamId(),
            response.hasErrorDetails() ? response.getErrorDetails() : "No details");
            
        // In a real implementation, could implement retry logic based on stepConfig.maxRetries()
        // or route to an error handling step
        
        return Mono.empty();
    }
    
    /**
     * Determines if the current request should be sampled based on the configured sample rate.
     */
    private boolean shouldSample() {
        return bufferEnabled && ThreadLocalRandom.current().nextDouble() < sampleRate;
    }
    
    // Helper methods to get or create buffers for a specific step
    private ProcessingBuffer<ProcessRequest> getRequestBuffer(String stepName) {
        return requestBuffers.computeIfAbsent(stepName, k -> 
            ProcessingBufferFactory.createBuffer(bufferEnabled, bufferCapacity, ProcessRequest.class));
    }
    
    private ProcessingBuffer<ProcessResponse> getResponseBuffer(String stepName) {
        return responseBuffers.computeIfAbsent(stepName, k -> 
            ProcessingBufferFactory.createBuffer(bufferEnabled, bufferCapacity, ProcessResponse.class));
    }
    
    private ProcessingBuffer<com.krickert.search.model.PipeDoc> getPipeDocBuffer(String stepName) {
        return pipeDocBuffers.computeIfAbsent(stepName, k -> 
            ProcessingBufferFactory.createBuffer(bufferEnabled, bufferCapacity, com.krickert.search.model.PipeDoc.class));
    }
    
    /**
     * Cleanup method to close all gRPC channels and save buffered data.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down PipelineEngineImpl, closing {} channels", 
            channelCache.size());
        
        channelCache.forEach((key, channel) -> {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.error("Error shutting down channel: {}", key, e);
                Thread.currentThread().interrupt();
            }
        });
        
        channelCache.clear();
        
        // Save buffered test data
        if (bufferEnabled) {
            logger.info("Saving buffered test data to disk...");
            String timestamp = String.valueOf(System.currentTimeMillis());
            
            // Save buffers for each step in its own directory
            requestBuffers.forEach((stepName, buffer) -> {
                if (buffer.size() > 0) {
                    Path stepDir = Path.of("buffer-dumps", stepName);
                    try {
                        Files.createDirectories(stepDir);
                        buffer.saveToDisk(stepDir, "requests-" + timestamp, bufferPrecision);
                        logger.info("Saved {} requests for step {}", buffer.size(), stepName);
                    } catch (IOException e) {
                        logger.error("Failed to save requests for step {}", stepName, e);
                    }
                }
            });
            
            responseBuffers.forEach((stepName, buffer) -> {
                if (buffer.size() > 0) {
                    Path stepDir = Path.of("buffer-dumps", stepName);
                    try {
                        Files.createDirectories(stepDir);
                        buffer.saveToDisk(stepDir, "responses-" + timestamp, bufferPrecision);
                        logger.info("Saved {} responses for step {}", buffer.size(), stepName);
                    } catch (IOException e) {
                        logger.error("Failed to save responses for step {}", stepName, e);
                    }
                }
            });
            
            pipeDocBuffers.forEach((stepName, buffer) -> {
                if (buffer.size() > 0) {
                    Path stepDir = Path.of("buffer-dumps", stepName);
                    try {
                        Files.createDirectories(stepDir);
                        buffer.saveToDisk(stepDir, "pipedocs-" + timestamp, bufferPrecision);
                        logger.info("Saved {} docs for step {}", buffer.size(), stepName);
                    } catch (IOException e) {
                        logger.error("Failed to save pipedocs for step {}", stepName, e);
                    }
                }
            });
        }
    }
}