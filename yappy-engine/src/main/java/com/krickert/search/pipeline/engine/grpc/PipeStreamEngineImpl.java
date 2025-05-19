package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.common.RouteData;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of the PipeStreamEngine gRPC service.
 */
@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

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
            stateBuilder.withHopNumber((int)request.getCurrentHopNumber() + 1);

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

            // Note: We do NOT forward to next steps in process
            // This method is for testing only and returns where it would have forwarded
        } catch (Exception e) {
            log.error("Error in process: {}", e.getMessage(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
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
            stateBuilder.withHopNumber((int)request.getCurrentHopNumber() + 1);

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
                    PipeStreamGrpcForwarder.RouteData grpcRoute = PipeStreamGrpcForwarder.RouteData.builder()
                        .targetPipeline(route.targetPipeline())
                        .nextTargetStep(route.nextTargetStep())
                        .destination(route.destination())
                        .streamId(request.getStreamId())
                        .build();
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, grpcRoute);
                }
            }
        } catch (Exception e) {
            // Since we've already sent the response, we can only log the error
            log.error("Error in processAsync: {}", e.getMessage(), e);
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
                    PipeStreamGrpcForwarder.RouteData grpcRoute = PipeStreamGrpcForwarder.RouteData.builder()
                        .targetPipeline(route.targetPipeline())
                        .nextTargetStep(route.nextTargetStep())
                        .destination(route.destination())
                        .streamId(processedStream.getStreamId())
                        .build();
                    grpcForwarder.forwardToGrpc(destinationPipeBuilder, grpcRoute);
                }
            }
        } catch (Exception e) {
            log.error("Error processing connector document: {}", e.getMessage(), e);
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
