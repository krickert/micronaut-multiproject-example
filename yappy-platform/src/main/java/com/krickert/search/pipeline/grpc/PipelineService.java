package com.krickert.search.pipeline.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.model.ServiceProcessRequest;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ServiceProcessRepsonse;
import io.grpc.stub.StreamObserver;

public interface PipelineService {
    void forward(PipeStream request, StreamObserver<Empty> responseObserver);

    void process(ServiceProcessRequest request, StreamObserver<ServiceProcessRepsonse> responseObserver);

    void processKafkaMessage(PipeStream request);
}
