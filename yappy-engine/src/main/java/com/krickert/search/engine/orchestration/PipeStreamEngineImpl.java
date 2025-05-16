package com.krickert.search.engine.orchestration;

import com.krickert.search.engine.EngineServiceProto.*;
import com.krickert.search.engine.IngestDataRequest;
import com.krickert.search.engine.IngestDataResponse;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.engine.orchestration.AsyncPipelineProcessor;
import com.krickert.search.engine.orchestration.IngestionService;
import com.krickert.search.engine.orchestration.SyncStepExecutor;

import com.google.protobuf.Empty;
import com.krickert.search.model.PipeStream;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final IngestionService ingestionService;
    private final AsyncPipelineProcessor asyncPipelineProcessor;
    private final SyncStepExecutor syncStepExecutor;

    @Inject
    public PipeStreamEngineImpl(IngestionService ingestionService,
                                AsyncPipelineProcessor asyncPipelineProcessor,
                                SyncStepExecutor syncStepExecutor) {
        this.ingestionService = ingestionService;
        this.asyncPipelineProcessor = asyncPipelineProcessor;
        this.syncStepExecutor = syncStepExecutor;
    }

    @Override
    public void ingestDataAsync(IngestDataRequest request, StreamObserver<IngestDataResponse> responseObserver) {
        LOG.debug("PipeStreamEngineImpl.ingestDataAsync called");
        ingestionService.handleIngestion(request, responseObserver);
    }

    @Override
    public void processAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
        LOG.debug("PipeStreamEngineImpl.processAsync called for stream_id: {}, target_step: {}", request.getStreamId(), request.getTargetStepName());
        asyncPipelineProcessor.processAndDispatchAsync(request, responseObserver);
    }

    @Override
    public void process(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        LOG.debug("PipeStreamEngineImpl.process called for stream_id: {}, target_step: {}", request.getStreamId(), request.getTargetStepName());
        syncStepExecutor.executeStepSync(request, responseObserver);
    }
}