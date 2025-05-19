package com.krickert.yappy.modules.echo;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@GrpcService
public class EchoService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(EchoService.class);

    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument(); // Document now contains the blob

        LOG.info("EchoService (Unary) received request for pipeline: {}, step: {}",
                metadata.getPipelineName(), metadata.getPipeStepName());

        String streamId = metadata.getStreamId();
        String docId = document.getId(); // Assuming document will always be present, even if empty.
        // Add hasDocument() check if PipeDoc can be entirely absent from ProcessRequest.
        // Based on new proto, PipeDoc is a required field in ProcessRequest.

        LOG.debug("(Unary) Stream ID: {}, Document ID: {}", streamId, docId);

        String logPrefix = "";
        Struct customConfig = config.getCustomJsonConfig(); // Get from ProcessConfiguration
        if (customConfig != null && customConfig.containsFields("log_prefix")) {
            Value prefixValue = customConfig.getFieldsOrDefault("log_prefix", null);
            if (prefixValue != null && prefixValue.hasStringValue()) {
                logPrefix = prefixValue.getStringValue();
                LOG.info("(Unary) Using custom log_prefix: '{}'", logPrefix);
            }
        }
        // Access config_params if needed: Map<String, String> params = config.getConfigParamsMap();

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        // Echo the document (which includes the blob)
        // The new PipeDoc in yappy_core_types.proto has an optional Blob field.
        responseBuilder.setOutputDoc(document);
        LOG.debug("(Unary) Echoing document ID: {}", docId);
        if (document.hasBlob()) {
            LOG.debug("(Unary) Echoing blob with ID: {} and filename: {}", document.getBlob().getBlobId(), document.getBlob().getFilename());
        } else {
            LOG.debug("(Unary) No blob present within the input document to echo.");
        }


        String logMessage = String.format("%sEchoService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix,
                metadata.getPipeStepName(),   // From ServiceMetadata
                metadata.getPipelineName(),  // From ServiceMetadata
                streamId,                    // From ServiceMetadata
                docId);
        responseBuilder.addProcessorLogs(logMessage);
        LOG.info("(Unary) Sending response for stream ID: {}", streamId);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
