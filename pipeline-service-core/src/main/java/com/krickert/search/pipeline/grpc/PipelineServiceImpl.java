// PipelineServiceImpl.java
package com.krickert.search.pipeline.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.model.*;
import com.krickert.search.pipeline.config.PipelineConfigService;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.kafka.KafkaForwarder;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@GrpcService
@Slf4j
public class PipelineServiceImpl extends PipelineServiceGrpc.PipelineServiceImplBase {

    @Inject
    KafkaForwarder kafkaForwarder;

    @Inject
    GrpcForwarder grpcForwarder;

    @Inject
    PipelineConfigService pipelineConfigService;

    @Inject
    PipelineServiceProcessor serviceProcessor;

    @Override
    public void forward(PipeStream request, StreamObserver<Empty> responseObserver) {
        processStream(request);
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void getOutput(PipeRequest request, StreamObserver<OutputResponse> responseObserver) {
        PipeResponse processingResponse = processRequest(request);

        OutputResponse.Builder builder = OutputResponse.newBuilder()
            .setSuccess(processingResponse.getSuccess());

        if (processingResponse.getSuccess()) {
            // If processing was successful, include the document in the response
            builder.setOutputDoc(request.getDoc());
        } else if (processingResponse.getErrorDate() != null) {
            // If there was an error, include the error data
            builder.setErrorData(ErrorData.newBuilder()
                .setErrorMessage(processingResponse.getErrorDate().getErrorMessage())
                .addAllFailedRoutes(processingResponse.getErrorDate().getFailedRoutesList())
                .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    /**
     * Helper method for Kafka listener to process a PipeStream without needing a StreamObserver.
     * 
     * @param request The PipeStream to process
     */
    public void processKafkaMessage(PipeStream request) {
        processStream(request);
    }

    private PipeResponse processRequest(PipeRequest request) {
        // Create a PipeStream from the request
        PipeStream pipeStream = PipeStream.newBuilder()
            .setRequest(request)
            .build();

        // Process the PipeStream using the service processor
        PipeResponse processorResponse = serviceProcessor.process(pipeStream);

        // If processing was successful, forward to configured routes
        if (processorResponse.getSuccess()) {
            PipeResponse.Builder responseBuilder = PipeResponse.newBuilder();
            List<Route> routes = buildRoutesFromConfig();

            boolean success = true;
            ErrorData.Builder errorBuilder = ErrorData.newBuilder();

            for (Route route : routes) {
                try {
                    switch (route.getRouteType()) {
                        case KAFKA:
                            kafkaForwarder.forwardToKafka(pipeStream, route);
                            break;
                        case GRPC:
                            grpcForwarder.forwardToGrpc(pipeStream, route);
                            break;
                        case NULL_TERMINATION:
                            // Do nothing: this route terminates forwarding.
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported route type: " + route.getRouteType());
                    }
                } catch (Exception e) {
                    log.error("Error processing route {}: {}", route.getDestination(), e.getMessage(), e);
                    // Record the failed route
                    errorBuilder.addFailedRoutes(route);
                    success = false;

                    // Optionally: forward the same pipe to a backup Kafka topic for reprocessing
                    try {
                        kafkaForwarder.forwardToBackup(pipeStream, route);
                    } catch (Exception backupEx) {
                        log.error("Error forwarding to backup: {}", backupEx.getMessage(), backupEx);
                    }
                }
            }

            responseBuilder.setSuccess(success);
            if (!success) {
                errorBuilder.setErrorMessage("Some routes failed during processing.");
                responseBuilder.setErrorDate(errorBuilder.build());
            }

            return responseBuilder.build();
        } else {
            // If processing failed, return the processor's response
            return processorResponse;
        }
    }

    private void processStream(PipeStream pipeStream) {
        // First, process the PipeStream using the service processor
        PipeResponse processorResponse = serviceProcessor.process(pipeStream);

        // If processing was successful, forward to configured routes
        if (processorResponse.getSuccess()) {
            List<Route> routes = buildRoutesFromConfig();

            for (Route route : routes) {
                try {
                    switch (route.getRouteType()) {
                        case KAFKA:
                            kafkaForwarder.forwardToKafka(pipeStream, route);
                            break;
                        case GRPC:
                            grpcForwarder.forwardToGrpc(pipeStream, route);
                            break;
                        case NULL_TERMINATION:
                            // Do nothing: this route terminates forwarding.
                            break;
                        default:
                            throw new IllegalArgumentException("Unsupported route type: " + route.getRouteType());
                    }
                } catch (Exception e) {
                    log.error("Error processing stream route {}: {}", route.getDestination(), e.getMessage(), e);
                    // Optionally: forward the same pipe to a backup Kafka topic for reprocessing
                    try {
                        kafkaForwarder.forwardToBackup(pipeStream, route);
                    } catch (Exception backupEx) {
                        log.error("Error forwarding stream to backup: {}", backupEx.getMessage(), backupEx);
                    }
                }
            }
        } else {
            log.error("Service processor failed to process PipeStream: {}", 
                processorResponse.getErrorDate() != null ? processorResponse.getErrorDate().getErrorMessage() : "Unknown error");
        }
    }

    private List<Route> buildRoutesFromConfig() {
        List<Route> routes = new ArrayList<>();
        Map<String, ServiceConfiguration> services = pipelineConfigService.getActivePipelineConfig().getService();

        for (Map.Entry<String, ServiceConfiguration> entry : services.entrySet()) {
            ServiceConfiguration service = entry.getValue();

            // Add Kafka routes for publish topics
            if (service.getKafkaPublishTopics() != null) {
                for (String topic : service.getKafkaPublishTopics()) {
                    Route kafkaRoute = Route.newBuilder()
                        .setRouteType(RouteType.KAFKA)
                        .setDestination(topic)
                        .build();
                    routes.add(kafkaRoute);
                }
            }

            // Add gRPC routes for forward-to services
            if (service.getGrpcForwardTo() != null) {
                for (String target : service.getGrpcForwardTo()) {
                    if ("null".equalsIgnoreCase(target)) {
                        Route nullRoute = Route.newBuilder()
                            .setRouteType(RouteType.NULL_TERMINATION)
                            .setDestination("null")
                            .build();
                        routes.add(nullRoute);
                    } else {
                        Route grpcRoute = Route.newBuilder()
                            .setRouteType(RouteType.GRPC)
                            .setDestination(target)
                            .build();
                        routes.add(grpcRoute);
                    }
                }
            }
        }

        return routes;
    }
}
