package com.krickert.yappy.modules.s3connector;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.yappy.modules.s3connector.config.S3ConnectorConfig;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S3 connector service that implements the PipeStepProcessor interface.
 * This service connects to an S3 bucket, lists objects, downloads them,
 * and prepares them for processing by the Tika parser.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.s3-connector.enabled", value = "true", defaultValue = "true")
public class S3ConnectorService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(S3ConnectorService.class);

    @Inject
    private S3Client s3Client;

    /**
     * Processes a request to connect to an S3 bucket and process its contents.
     *
     * @param request          the process request
     * @param responseObserver the response observer
     */
    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument();

        LOG.info("S3ConnectorService received request for pipeline: {}, step: {}",
                metadata.getPipelineName(), metadata.getPipeStepName());

        String streamId = metadata.getStreamId();
        String docId = document.getId();

        LOG.debug("Stream ID: {}, Document ID: {}", streamId, docId);

        // Extract configuration
        S3ConnectorConfig s3Config = extractConfig(config.getCustomJsonConfig());
        String logPrefix = s3Config.logPrefix() != null ? s3Config.logPrefix() : "";

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        try {
            // Use the injected S3Client

            // List objects in the bucket
            List<S3Object> s3Objects = listS3Objects(s3Client, s3Config);
            LOG.info("{}Found {} objects in bucket: {}", logPrefix, s3Objects.size(), s3Config.bucketName());

            // Process each object
            for (S3Object s3Object : s3Objects) {
                try {
                    // Download the object
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(s3Config.bucketName())
                            .key(s3Object.key())
                            .build();

                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    GetObjectResponse objectResponse = s3Client.getObject(getObjectRequest, 
                            ResponseTransformer.toOutputStream(outputStream));

                    // Create a blob with the object data
                    Blob.Builder blobBuilder = Blob.newBuilder()
                            .setBlobId(UUID.randomUUID().toString())
                            .setData(com.google.protobuf.ByteString.copyFrom(outputStream.toByteArray()))
                            .setFilename(s3Object.key());

                    // Set MIME type if available
                    if (objectResponse.contentType() != null) {
                        blobBuilder.setMimeType(objectResponse.contentType());
                    }

                    // Add S3 metadata to the blob
                    Map<String, String> blobMetadata = new HashMap<>();
                    blobMetadata.put("s3_bucket", s3Config.bucketName());
                    blobMetadata.put("s3_key", s3Object.key());
                    blobMetadata.put("s3_etag", s3Object.eTag());
                    blobMetadata.put("s3_size", String.valueOf(s3Object.size()));
                    blobMetadata.put("s3_last_modified", s3Object.lastModified().toString());
                    blobMetadata.put("s3_storage_class", s3Object.storageClass().toString());

                    // Add any additional metadata from the object response
                    objectResponse.metadata().forEach(blobMetadata::put);

                    blobBuilder.putAllMetadata(blobMetadata);

                    // Create a new PipeDoc with the blob
                    PipeDoc.Builder docBuilder = PipeDoc.newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setSourceUri("s3://" + s3Config.bucketName() + "/" + s3Object.key())
                            .setBlob(blobBuilder.build());

                    // Set source MIME type if available
                    if (objectResponse.contentType() != null) {
                        docBuilder.setSourceMimeType(objectResponse.contentType());
                    }

                    // Add the document to the response
                    responseBuilder.setOutputDoc(docBuilder.build());

                    LOG.info("{}Successfully processed S3 object: {}", logPrefix, s3Object.key());
                } catch (Exception e) {
                    LOG.error("{}Error processing S3 object: {}", logPrefix, s3Object.key(), e);
                    // Continue with the next object
                }
            }

            String logMessage = String.format("%sS3ConnectorService successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                    logPrefix,
                    metadata.getPipeStepName(),
                    metadata.getPipelineName(),
                    streamId,
                    docId);
            responseBuilder.addProcessorLogs(logMessage);
            LOG.info("Sending response for stream ID: {}", streamId);

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("{}Error in S3ConnectorService: {}", logPrefix, e.getMessage(), e);
            responseBuilder.setSuccess(false);
            responseBuilder.addProcessorLogs("Error in S3ConnectorService: " + e.getMessage());
            responseBuilder.setOutputDoc(document); // Return the original document on error
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }
    }

    /**
     * Extracts the S3 connector configuration from the custom JSON configuration.
     *
     * @param customConfig the custom JSON configuration
     * @return the S3 connector configuration
     */
    private S3ConnectorConfig extractConfig(Struct customConfig) {
        S3ConnectorConfig.S3ConnectorConfigBuilder builder = S3ConnectorConfig.defaults();

        if (customConfig == null) {
            LOG.info("No custom configuration provided, using defaults");
            return builder.build();
        }

        // Extract S3 connection settings
        if (customConfig.containsFields("bucketName")) {
            Value value = customConfig.getFieldsOrDefault("bucketName", null);
            if (value != null && value.hasStringValue()) {
                builder.bucketName(value.getStringValue());
            }
        }

        if (customConfig.containsFields("region")) {
            Value value = customConfig.getFieldsOrDefault("region", null);
            if (value != null && value.hasStringValue()) {
                builder.region(value.getStringValue());
            }
        }

        if (customConfig.containsFields("endpoint")) {
            Value value = customConfig.getFieldsOrDefault("endpoint", null);
            if (value != null && value.hasStringValue()) {
                builder.endpoint(value.getStringValue());
            }
        }

        if (customConfig.containsFields("accessKey")) {
            Value value = customConfig.getFieldsOrDefault("accessKey", null);
            if (value != null && value.hasStringValue()) {
                builder.accessKey(value.getStringValue());
            }
        }

        if (customConfig.containsFields("secretKey")) {
            Value value = customConfig.getFieldsOrDefault("secretKey", null);
            if (value != null && value.hasStringValue()) {
                builder.secretKey(value.getStringValue());
            }
        }

        // Extract S3 object filtering
        if (customConfig.containsFields("prefix")) {
            Value value = customConfig.getFieldsOrDefault("prefix", null);
            if (value != null && value.hasStringValue()) {
                builder.prefix(value.getStringValue());
            }
        }

        if (customConfig.containsFields("suffix")) {
            Value value = customConfig.getFieldsOrDefault("suffix", null);
            if (value != null && value.hasStringValue()) {
                builder.suffix(value.getStringValue());
            }
        }

        // Extract processing options
        if (customConfig.containsFields("recursive")) {
            Value value = customConfig.getFieldsOrDefault("recursive", null);
            if (value != null && value.hasBoolValue()) {
                builder.recursive(value.getBoolValue());
            }
        }

        if (customConfig.containsFields("maxKeys")) {
            Value value = customConfig.getFieldsOrDefault("maxKeys", null);
            if (value != null && value.hasNumberValue()) {
                builder.maxKeys((int) value.getNumberValue());
            }
        }

        // Extract Kafka settings
        if (customConfig.containsFields("kafkaTopic")) {
            Value value = customConfig.getFieldsOrDefault("kafkaTopic", null);
            if (value != null && value.hasStringValue()) {
                builder.kafkaTopic(value.getStringValue());
            }
        }

        // Extract logging options
        if (customConfig.containsFields("logPrefix")) {
            Value value = customConfig.getFieldsOrDefault("logPrefix", null);
            if (value != null && value.hasStringValue()) {
                builder.logPrefix(value.getStringValue());
            }
        }

        return builder.build();
    }

    /**
     * Creates an S3 client based on the provided configuration.
     *
     * @param config the S3 connector configuration
     * @return the S3 client
     */
    private S3Client createS3Client(S3ConnectorConfig config) {
        S3ClientBuilder builder = S3Client.builder();

        // Set region
        if (config.region() != null && !config.region().isEmpty()) {
            builder.region(Region.of(config.region()));
        }

        // Set endpoint if provided
        if (config.endpoint() != null && !config.endpoint().isEmpty()) {
            builder.endpointOverride(URI.create(config.endpoint()));
        }

        // Set credentials if provided
        if (config.accessKey() != null && !config.accessKey().isEmpty() &&
                config.secretKey() != null && !config.secretKey().isEmpty()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey(), config.secretKey())));
        }

        return builder.build();
    }

    /**
     * Lists objects in the S3 bucket based on the provided configuration.
     *
     * @param s3Client the S3 client
     * @param config   the S3 connector configuration
     * @return the list of S3 objects
     */
    private List<S3Object> listS3Objects(S3Client s3Client, S3ConnectorConfig config) {
        List<S3Object> result = new ArrayList<>();

        ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                .bucket(config.bucketName())
                .maxKeys(config.maxKeys());

        // Set prefix if provided
        if (config.prefix() != null && !config.prefix().isEmpty()) {
            requestBuilder.prefix(config.prefix());
        }

        ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());

        // Filter objects based on suffix if provided
        if (config.suffix() != null && !config.suffix().isEmpty()) {
            for (S3Object object : response.contents()) {
                if (object.key().endsWith(config.suffix())) {
                    result.add(object);
                }
            }
        } else {
            result.addAll(response.contents());
        }

        return result;
    }
}
