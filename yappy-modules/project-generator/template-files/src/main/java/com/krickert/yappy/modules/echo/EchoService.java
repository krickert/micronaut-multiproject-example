package@ @BASE_PACKAGE @ @;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

// Imports from yappy_core_types.proto (assuming java_package = "com.krickert.search.model")
import com.krickert.search.model.PipeDoc;
// Blob is now part of PipeDoc, so direct import might not be needed at this level if always accessed via PipeDoc.
// import com.krickert.search.model.Blob;


// Imports from pipe_step_processor_service.proto (assuming java_package = "com.krickert.search.sdk")
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;

import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@GrpcService
public class@ @MODULE_NAME_PASCAL_CASE @ @Service extends PipeStepProcessorGrpc.

PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger( @ @MODULE_NAME_PASCAL_CASE @ @Service.class);

    @Override
    public void processData (ProcessRequest request, StreamObserver < ProcessResponse > responseObserver){
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument(); // Document now contains the blob

        LOG.info("@@MODULE_NAME_PASCAL_CASE@@Service (Unary) received request for pipeline: {}, step: {}",
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


        String logMessage = String.format("%s@@MODULE_NAME_PASCAL_CASE@@Service (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
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
