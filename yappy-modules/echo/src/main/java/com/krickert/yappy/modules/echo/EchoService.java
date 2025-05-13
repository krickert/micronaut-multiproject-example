package com.krickert.yappy.modules.echo; // Or your chosen package for modules

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessPipeDocRequest;
import com.krickert.search.sdk.ProcessResponse;

import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService; // Correct Micronaut gRPC annotation
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton // Micronaut annotation
@GrpcService // Micronaut gRPC annotation for service implementation
public class EchoService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoService.class);

    @Override
    public void processDocument(ProcessPipeDocRequest request, StreamObserver<ProcessResponse> responseObserver) {
        LOG.info("EchoService (Unary) received request for pipeline: {}, step: {}", // Added (Unary) for clarity
                request.getPipelineName(), request.getPipeStepName());

        PipeStream pipeStreamData = request.getPipeStreamData();
        String streamId = pipeStreamData.getStreamId();
        // Use the correct getter for PipeDoc from PipeStream based on your .proto definition
        String docId = pipeStreamData.hasDocument() ? pipeStreamData.getDocument().getId() : "N/A";


        LOG.debug("(Unary) Stream ID: {}, Document ID: {}", streamId, docId);

        String logPrefix = "";
        Struct customConfig = request.getCustomJsonConfig();
        if (customConfig != null && customConfig.containsFields("log_prefix")) {
            Value prefixValue = customConfig.getFieldsOrDefault("log_prefix", null);
            if (prefixValue != null && prefixValue.hasStringValue()) {
                logPrefix = prefixValue.getStringValue();
                LOG.info("(Unary) Using custom log_prefix: '{}'", logPrefix);
            }
        }

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        // Echo the document
        // Use the correct getter for PipeDoc from PipeStream
        if (pipeStreamData.hasDocument()) {
            responseBuilder.setOutputDoc(pipeStreamData.getDocument());
            LOG.debug("(Unary) Echoing document ID: {}", pipeStreamData.getDocument().getId());
        } else {
            LOG.debug("(Unary) No document in input pipestream to echo.");
        }

        // Echo the blob
        // Use the correct getter for Blob from PipeStream
        if (pipeStreamData.hasBlob()) {
            responseBuilder.setOutputBlob(pipeStreamData.getBlob());
            LOG.debug("(Unary) Echoing blob with ID: {} and filename: {}", pipeStreamData.getBlob().getBlobId(), pipeStreamData.getBlob().getFilename());
        } else {
            LOG.debug("(Unary) No blob in input pipestream to echo.");
        }

        // Corrected log message to include (Unary)
        String logMessage = String.format("%sEchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                                          logPrefix,
                                          request.getPipeStepName(),
                                          request.getPipelineName(),
                                          streamId,
                                          docId);
        responseBuilder.addProcessorLogs(logMessage);
        LOG.info("(Unary) Sending response for stream ID: {}", streamId);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
