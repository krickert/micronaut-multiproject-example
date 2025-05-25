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

        // Convert Google's ListenableFuture (from gRPC FutureStub) to Mono
        return Mono.fromFuture(() -> futureStub.processConnectorDoc(connectorRequest))
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


