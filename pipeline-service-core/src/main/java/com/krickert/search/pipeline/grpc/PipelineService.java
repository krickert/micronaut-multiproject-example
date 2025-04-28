package com.krickert.search.pipeline.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.model.OutputResponse;
import com.krickert.search.model.PipeRequest;
import com.krickert.search.model.PipeStream;
import io.grpc.stub.StreamObserver;

public interface PipelineService {
    void forward(PipeStream request, StreamObserver<Empty> responseObserver);

    void getOutput(PipeRequest request, StreamObserver<OutputResponse> responseObserver);

    void processKafkaMessage(PipeStream request);
}
