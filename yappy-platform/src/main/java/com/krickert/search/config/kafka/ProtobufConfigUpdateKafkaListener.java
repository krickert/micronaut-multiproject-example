package com.krickert.search.config.kafka;

// Import generated Protobuf classes
import com.krickert.search.config.grpc.ReloadServiceEndpoint;
import com.krickert.search.model.*;
import io.micronaut.configuration.kafka.annotation.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord; // To access key/headers if needed
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@KafkaListener(
        groupId = "pipestream-engine-config-group", // Must match the group ID in application.yml
        clientId = "pipestream-engine-protobuf-config-listener-${random.uuid}"
)
// Optional: Configure error handling strategy (e.g., dead letter queue)
// @ErrorStrategy(value = ErrorStrategyValue.DEAD_LETTER_QUEUE, topic = "config-updates-dlq")
@Requires(notEnv = Environment.TEST)
public class ProtobufConfigUpdateKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(ProtobufConfigUpdateKafkaListener.class);
    private final ReloadServiceEndpoint reloadServiceEndpoint; // Inject the gRPC service implementation

    @Inject
    public ProtobufConfigUpdateKafkaListener(ReloadServiceEndpoint reloadServiceEndpoint) {
        this.reloadServiceEndpoint = reloadServiceEndpoint;
    }

    // Listener for Pipeline Reload events
    @Topic("${app.kafka.topics.pipeline-reload}")
    public void receivePipelineReload(ConsumerRecord<UUID, PipelineReloadRequest> record) {
        PipelineReloadRequest request = record.value(); // Get the deserialized Protobuf message
        if (request == null) {
            log.warn("Received null PipelineReloadRequest on topic '{}'. Key: {}", record.topic(), record.key());
            return;
        }
        log.info("Kafka listener received PipelineReloadRequest for pipeline: {} (from topic '{}')", request.getPipelineName(), record.topic());
        try {
            // Call the internal gRPC service implementation method
            // We need to adapt the async gRPC call style here or make ReloadServiceEndpoint methods synchronous internally
            // For simplicity, let's assume the gRPC endpoint methods can be called directly for now.
            // A more robust way might involve handling the StreamObserver within the listener,
            // but that complicates things unnecessarily if the listener doesn't need the response.

            // Simple direct call (assuming endpoint methods handle exceptions):
             reloadServiceEndpoint.reloadPipeline(request, new NoOpStreamObserver<>()); // Pass a dummy observer

            // Alternative: If ReloadServiceEndpoint methods were synchronous:
            // reloadServiceEndpoint.handlePipelineReloadSync(request);

        } catch (Exception e) {
            log.error("Error processing PipelineReloadRequest for pipeline '{}' from Kafka", request.getPipelineName(), e);
            // Error handling (e.g., throw to trigger DLQ if configured, or log and commit)
        }
    }

    // Listener for Service Reload events
    @Topic("${app.kafka.topics.service-reload}")
    public void receiveServiceReload(ConsumerRecord<String, PipeStepReloadRequest> record) {
         PipeStepReloadRequest request = record.value();
         if (request == null) {
            log.warn("Received null ServiceReloadRequest on topic '{}'. Key: {}", record.topic(), record.key());
            return;
         }
         log.info("Kafka listener received ServiceReloadRequest for service: {} (from topic '{}')", request.getServiceName(), record.topic());
        try {
            reloadServiceEndpoint.reloadService(request, new NoOpStreamObserver<>());
        } catch (Exception e) {
             log.error("Error processing ServiceReloadRequest for service '{}' from Kafka", request.getServiceName(), e);
        }
    }

     // Listener for Application Change events
    @Topic("${app.kafka.topics.app-change}")
    public void receiveAppChange(ConsumerRecord<String, ApplicationChangeEvent> record) {
         ApplicationChangeEvent request = record.value();
          if (request == null) {
            log.warn("Received null ApplicationChangeEvent on topic '{}'. Key: {}", record.topic(), record.key());
            return;
          }
         log.info("Kafka listener received ApplicationChangeEvent for app: {} (from topic '{}')", request.getApplication(), record.topic());
         try {
             reloadServiceEndpoint.applicationChanged(request, new NoOpStreamObserver<>());
         } catch (Exception e) {
              log.error("Error processing ApplicationChangeEvent for app '{}' from Kafka", request.getApplication(), e);
         }
    }

    // Dummy StreamObserver implementation as we don't need the response in the listener
    private static class NoOpStreamObserver<T> implements io.grpc.stub.StreamObserver<T> {
        @Override public void onNext(T value) {}
        @Override public void onError(Throwable t) {}
        @Override public void onCompleted() {}
    }
}