package com.krickert.yappy.wikicrawler.connector;

import com.krickert.search.engine.ConnectorEngineGrpc; // Generated gRPC stub
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;

import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

@Singleton
public class YappyIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(YappyIngestionService.class);

    // The gRPC client stub for ConnectorEngine
    private final ConnectorEngineGrpc.ConnectorEngineFutureStub futureStub;
    // Or use ConnectorEngineGrpc.ConnectorEngineStub for async/streaming, 
    // or ConnectorEngineGrpc.ConnectorEngineBlockingStub for blocking calls.
    // FutureStub is a good choice for Mono compatibility.

    // The source_identifier for this connector, should be configurable
    private final String sourceIdentifier = "wikicrawler-connector"; // TODO: Make this configurable

    public YappyIngestionService(ConnectorEngineGrpc.ConnectorEngineFutureStub futureStub) {
        this.futureStub = futureStub;
    }

    public Mono<ConnectorResponse> ingestPipeDoc(PipeDoc pipeDoc) {
        if (pipeDoc == null) {
            LOG.warn("Attempted to ingest a null PipeDoc.");
            return Mono.error(new IllegalArgumentException("PipeDoc cannot be null."));
        }

        LOG.debug("Ingesting PipeDoc ID: {} into Yappy ConnectorEngine", pipeDoc.getId());

        ConnectorRequest connectorRequest = ConnectorRequest.newBuilder()
                .setSourceIdentifier(this.sourceIdentifier) // Identify this connector
                .setDocument(pipeDoc)
                // .putInitialContextParams("key", "value") // Optional: if needed
                // .setSuggestedStreamId() // Optional: if needed
                .build();

        com.google.common.util.concurrent.ListenableFuture<ConnectorResponse> listenableFuture = 
            futureStub.processConnectorDoc(connectorRequest);

        return Mono.create(sink -> {
            listenableFuture.addListener(() -> {
                try {
                    ConnectorResponse response = listenableFuture.get();
                    sink.success(response);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Restore interruption status
                    LOG.error("gRPC call interrupted while waiting for ListenableFuture", e);
                    sink.error(e);
                } catch (java.util.concurrent.ExecutionException e) {
                    // Unwrap the actual exception from gRPC
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOG.error("gRPC call failed via ListenableFuture", cause);
                    sink.error(cause);
                }
            }, Runnable::run); // Or use a specific executor if needed, Runnable::run executes inline on Guava's thread
        })
            .doOnSuccess(response -> {
                if (response.getAccepted()) {
                    LOG.info("PipeDoc ID: {} successfully ingested. Stream ID: {}", pipeDoc.getId(), response.getStreamId());
                } else {
                    LOG.warn("PipeDoc ID: {} ingestion was not accepted by ConnectorEngine. Message: {}", pipeDoc.getId(), response.getMessage());
                }
            })
            .doOnError(e -> LOG.error("Error ingesting PipeDoc ID: {} to ConnectorEngine: ", pipeDoc.getId(), e));
    }
}

// Factory to provide the gRPC stub
@Factory
class GrpcClientFactory {
    // Micronaut will inject the channel configured for 'connector-engine-service'
    // This name ('connector-engine-service') needs to be defined in application.yml
    // under grpc.channels.connector-engine-service.address (e.g., "localhost:50051")
    @Bean
    ConnectorEngineGrpc.ConnectorEngineFutureStub futureStub(@GrpcChannel("connector-engine-service") ManagedChannel channel) {
        return ConnectorEngineGrpc.newFutureStub(channel);
    }
}
