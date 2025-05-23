package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

// Imports from yappy_core_types.proto (assuming java_package = "com.krickert.search.model")
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ParsedDocument;
import com.krickert.search.model.ParsedDocumentReply;

// Imports from pipe_step_processor_service.proto (assuming java_package = "com.krickert.search.sdk")
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;

import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Singleton;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Singleton
@GrpcService
@Requires(property = "grpc.services.tika-parser.enabled", value = "true", defaultValue = "true")
public class TikaParserService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserService.class);

    @Override
    public void processData (ProcessRequest request, StreamObserver < ProcessResponse > responseObserver){
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument(); // Document now contains the blob

        LOG.info("TikaParserService (Unary) received request for pipeline: {}, step: {}",
                metadata.getPipelineName(), metadata.getPipeStepName());

        String streamId = metadata.getStreamId();
        String docId = document.getId();

        LOG.debug("(Unary) Stream ID: {}, Document ID: {}", streamId, docId);

        // Extract configuration
        Map<String, String> parserConfig = new HashMap<>(config.getConfigParamsMap());

        // Extract custom configuration if available
        String logPrefix = "";
        Struct customConfig = config.getCustomJsonConfig();
        if (customConfig != null) {
            // Extract log_prefix if available
            if (customConfig.containsFields("log_prefix")) {
                Value prefixValue = customConfig.getFieldsOrDefault("log_prefix", null);
                if (prefixValue != null && prefixValue.hasStringValue()) {
                    logPrefix = prefixValue.getStringValue();
                    LOG.info("(Unary) Using custom log_prefix: '{}'", logPrefix);
                }
            }

            // Extract other custom configuration options
            if (customConfig.containsFields("maxContentLength")) {
                Value maxLengthValue = customConfig.getFieldsOrDefault("maxContentLength", null);
                if (maxLengthValue != null && maxLengthValue.hasNumberValue()) {
                    parserConfig.put("maxContentLength", String.valueOf((int)maxLengthValue.getNumberValue()));
                }
            }

            if (customConfig.containsFields("extractMetadata")) {
                Value extractMetadataValue = customConfig.getFieldsOrDefault("extractMetadata", null);
                if (extractMetadataValue != null && extractMetadataValue.hasBoolValue()) {
                    parserConfig.put("extractMetadata", String.valueOf(extractMetadataValue.getBoolValue()));
                }
            }

            if (customConfig.containsFields("tikaConfigPath")) {
                Value tikaConfigPathValue = customConfig.getFieldsOrDefault("tikaConfigPath", null);
                if (tikaConfigPathValue != null && tikaConfigPathValue.hasStringValue()) {
                    parserConfig.put("tikaConfigPath", tikaConfigPathValue.getStringValue());
                }
            }
        }

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();

        // Check if document has a blob to parse
        if (document.hasBlob()) {
            Blob blob = document.getBlob();
            LOG.debug("(Unary) Parsing blob with ID: {} and filename: {}", blob.getBlobId(), blob.getFilename());

            try {
                // Parse the document using Tika
                ParsedDocumentReply parsedDocReply = DocumentParser.parseDocument(blob.getData(), parserConfig);
                ParsedDocument parsedDoc = parsedDocReply.getDoc();

                // Create a new PipeDoc with the parsed content
                PipeDoc.Builder newDocBuilder = PipeDoc.newBuilder()
                        .setId(docId)
                        .setTitle(parsedDoc.getTitle())
                        .setBody(parsedDoc.getBody());

                // Skip copying metadata for now
                // We could use custom_data field in the future if needed

                // Keep the original blob
                newDocBuilder.setBlob(blob);

                // Set the output document
                responseBuilder.setOutputDoc(newDocBuilder.build());
                responseBuilder.setSuccess(true);

                LOG.info("(Unary) Successfully parsed document with ID: {}", docId);
            } catch (IOException | SAXException | TikaException e) {
                LOG.error("(Unary) Error parsing document: {}", e.getMessage(), e);
                responseBuilder.setSuccess(false);
                // Add error message to processor logs instead of using setErrorMessage
                String errorMessage = "Error parsing document: " + e.getMessage();
                responseBuilder.addProcessorLogs(errorMessage);
                responseBuilder.setOutputDoc(document); // Return the original document on error
            }
        } else {
            LOG.warn("(Unary) No blob present in the document to parse. Returning original document.");
            responseBuilder.setOutputDoc(document);
            responseBuilder.setSuccess(true);
        }

        String logMessage = String.format("%sTikaParserService (Unary) processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix,
                metadata.getPipeStepName(),
                metadata.getPipelineName(),
                streamId,
                docId);
        responseBuilder.addProcessorLogs(logMessage);
        LOG.info("(Unary) Sending response for stream ID: {}", streamId);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
