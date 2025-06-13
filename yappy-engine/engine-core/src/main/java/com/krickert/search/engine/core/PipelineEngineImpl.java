package com.krickert.search.engine.core;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.krickert.search.engine.core.routing.Router;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.kiwiproject.consul.model.catalog.CatalogService;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.search.model.util.ProcessingBuffer;
import com.krickert.search.model.util.ProcessingBufferFactory;
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
import java.time.Duration;
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
    
    private final BusinessOperationsService businessOperationsService;
    private final String clusterName;
    private final Map<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();
    private final Router router;
    
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
            BusinessOperationsService businessOperationsService,
            Router router,
            @Value("${engine.cluster.name:default-cluster}") String clusterName,
            @Value("${engine.test-data-buffer.enabled:false}") boolean bufferEnabled,
            @Value("${engine.test-data-buffer.capacity:200}") int bufferCapacity,
            @Value("${engine.test-data-buffer.precision:3}") int bufferPrecision,
            @Value("${engine.test-data-buffer.sample-rate:0.1}") double sampleRate) {
        this.businessOperationsService = businessOperationsService;
        this.router = router;
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
            logger.info("Processing PipeStream: streamId={}, pipeline={}, targetStep={}, hop={}", 
                pipeStream.getStreamId(), 
                pipeStream.getCurrentPipelineName(),
                pipeStream.getTargetStepName(),
                pipeStream.getCurrentHopNumber());
            
            // Check if target step is set for routing
            if (pipeStream.getTargetStepName() == null || pipeStream.getTargetStepName().isBlank()) {
                logger.info("No target step specified, pipeline {} completed for stream {}", 
                    pipeStream.getCurrentPipelineName(), pipeStream.getStreamId());
                return Mono.empty();
            }
            
            // Use Router to handle the message routing
            // TODO: Add configurable timeout for routing operations to prevent hanging
            // TODO: Add circuit breaker pattern for routing failures
            return router.route(pipeStream)
                .timeout(Duration.ofSeconds(30)) // Prevent hanging on route operations
                .doOnSuccess(v -> logger.debug("Successfully routed message {} to step {}", 
                    pipeStream.getStreamId(), pipeStream.getTargetStepName()))
                .doOnError(e -> {
                    // TODO: Add error categorization (timeout, config not found, step not found, etc.)
                    String errorMsg = String.format("Failed to route message %s to step %s in pipeline %s: %s", 
                            pipeStream.getStreamId(), pipeStream.getTargetStepName(), 
                            pipeStream.getCurrentPipelineName(), e.getMessage());
                    logger.error(errorMsg, e);
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
    
    // Simple record to hold service instance information
    private record ServiceInstance(String address, int port, String serviceName) {}
    
    private Mono<ServiceInstance> discoverService(String serviceName) {
        String clusterServiceName = clusterName + "-" + serviceName;
        
        // First try to get healthy services
        return businessOperationsService.getHealthyServiceInstances(clusterServiceName)
            .flatMap(services -> {
                if (!services.isEmpty()) {
                    ServiceHealth selected = services.get(0);
                    ServiceInstance instance = new ServiceInstance(
                        selected.getService().getAddress(),
                        selected.getService().getPort(),
                        serviceName
                    );
                    logger.info("Discovered healthy service {} at {}:{}", 
                        serviceName, instance.address, instance.port);
                    return Mono.just(instance);
                }
                
                // If no healthy services, get all services (including unhealthy)
                logger.warn("No healthy instances found for service: {}, checking all instances", clusterServiceName);
                return businessOperationsService.getServiceInstances(clusterServiceName)
                    .flatMap(allServices -> {
                        if (allServices.isEmpty()) {
                            return Mono.error(new IllegalStateException(
                                "No instances found for service: " + clusterServiceName));
                        }
                        
                        CatalogService catalogService = allServices.get(0);
                        ServiceInstance instance = new ServiceInstance(
                            catalogService.getAddress(),
                            catalogService.getServicePort(),
                            serviceName
                        );
                        logger.info("Discovered service {} at {}:{}", 
                            serviceName, instance.address, instance.port);
                        return Mono.just(instance);
                    });
            });
    }
    
    private Mono<ProcessResponse> invokeService(ServiceInstance service, String documentId, String content) {
        return Mono.fromCallable(() -> {
            String address = service.address;
            int port = service.port;
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
                .setPipeStepName(service.serviceName)
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