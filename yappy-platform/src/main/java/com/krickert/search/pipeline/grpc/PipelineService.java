package com.krickert.search.pipeline.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.EngineProcessRequest;
import com.krickert.search.engine.EngineProcessResponse;
import com.krickert.search.model.PipeStream;
import io.grpc.stub.StreamObserver;

public interface PipelineService {
    void forward(PipeStream request, StreamObserver<Empty> responseObserver);

    void process(EngineProcessRequest request, StreamObserver<EngineProcessResponse> responseObserver);

    void processKafkaMessage(PipeStream request);
}
