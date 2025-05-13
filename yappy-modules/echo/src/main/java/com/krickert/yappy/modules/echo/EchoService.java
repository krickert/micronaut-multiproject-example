package com.krickert.yappy.modules.echo; // Or your chosen package for modules

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessPipeDocRequest;
import com.krickert.search.sdk.ProcessResponse;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
@Singleton
public class EchoService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoService.class);

    @Override
    public void processDocument(ProcessPipeDocRequest request, StreamObserver<ProcessResponse> responseObserver) {
        LOG.info("EchoService received request for pipeline: {}, step: {}",
                request.getPipelineName(), request.getPipeStepName());

        PipeStream pipeStreamData = request.getPipeStreamData();
        String streamId = pipeStreamData.getStreamId();
        String docId = pipeStreamData.hasDocument() ? pipeStreamData.getDocument().getId() : "N/A";

        LOG.debug("Stream ID: {}, Document ID: {}", streamId, docId);

        // --- Placeholder for Custom Config Handling ---
        String logPrefix = "";
        Struct customConfig = request.getCustomJsonConfig();
        if (customConfig != null && customConfig.containsFields("log_prefix")) {
            Value prefixValue = customConfig.getFieldsOrDefault("log_prefix", null);
            if (prefixValue != null && prefixValue.hasStringValue()) {
                logPrefix = prefixValue.getStringValue();
                LOG.info("Using custom log_prefix: '{}'", logPrefix);
            }
        }
        // --- End Placeholder ---

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        // Echo the document
        if (pipeStreamData.hasDocument()) {
            responseBuilder.setOutputDoc(pipeStreamData.getDocument());
            LOG.debug("Echoing document ID: {}", pipeStreamData.getDocument().getId());
        } else {
            LOG.debug("No document in input pipestream to echo.");
        }

        // Echo the blob
        if (pipeStreamData.hasBlob()) {
            responseBuilder.setOutputBlob(pipeStreamData.getBlob());
            LOG.debug("Echoing blob with ID: {} and filename: {}", pipeStreamData.getBlob().getBlobId(), pipeStreamData.getBlob().getFilename());
        } else {
            LOG.debug("No blob in input pipestream to echo.");
        }

        String logMessage = String.format("%sEchoService successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                                          logPrefix,
                                          request.getPipeStepName(),
                                          request.getPipelineName(),
                                          streamId,
                                          docId);
        responseBuilder.addProcessorLogs(logMessage);
        LOG.info("Sending response for stream ID: {}", streamId);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}