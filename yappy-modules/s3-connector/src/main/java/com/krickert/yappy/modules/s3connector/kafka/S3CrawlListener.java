package com.krickert.yappy.modules.s3connector.kafka;

import com.krickert.search.engine.s3_crawl_request;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.s3connector.S3ConnectorService;
import com.krickert.yappy.modules.s3connector.config.S3ConnectorConfig;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka listener for S3 crawl requests.
 * This listener consumes s3_crawl_request messages from a Kafka topic,
 * retrieves the specified S3 object, and produces a PipeDoc with the object data.
 * 
 * It supports both direct object retrieval and partial crawls based on date range.
 */
@KafkaListener(
        groupId = "${s3.connector.kafka.group-id:s3-connector-group}",
        offsetReset = OffsetReset.EARLIEST,
        clientId = "${s3.connector.kafka.client-id:s3-connector-client}"
)
@Requires(property = "s3.connector.kafka.enabled", value = "true", defaultValue = "true")
public class S3CrawlListener {

    private static final Logger LOG = LoggerFactory.getLogger(S3CrawlListener.class);

    @Inject
    private S3Client s3Client;

    @Inject
    private S3CrawlProducer s3CrawlProducer;

    /**
     * Processes an S3 crawl request from Kafka.
     * If a specific key is provided, retrieves that object.
     * If date range parameters are provided, performs a partial crawl of objects
     * modified within that date range.
     *
     * @param request the S3 crawl request
     */
    @Topic("${s3.connector.kafka.input-topic:s3-crawl-requests}")
    public void receive(s3_crawl_request request) {
        String bucket = request.getBucket();
        String key = request.getKey();

        // Check if this is a partial crawl request
        boolean isPartialCrawl = request.hasStartDate() || request.hasEndDate();

        if (isPartialCrawl) {
            // Handle partial crawl based on date range
            handlePartialCrawl(request);
        } else if (key != null && !key.isEmpty()) {
            // Handle direct object retrieval
            handleSingleObject(bucket, key);
        } else {
            LOG.error("Invalid S3 crawl request: bucket={}, key={}", bucket, key);
        }
    }

    /**
     * Handles a partial crawl request based on date range.
     * Lists objects in the bucket and processes those modified within the specified date range.
     *
     * @param request the S3 crawl request with date range parameters
     */
    private void handlePartialCrawl(s3_crawl_request request) {
        String bucket = request.getBucket();
        String prefix = request.getKey(); // Use key as prefix if provided

        LOG.info("Received partial S3 crawl request for bucket: {}, prefix: {}, startDate: {}, endDate: {}",
                bucket, prefix, request.hasStartDate() ? request.getStartDate() : "none", 
                request.hasEndDate() ? request.getEndDate() : "none");

        try {
            // Parse date range
            Instant startDate = null;
            Instant endDate = null;

            if (request.hasStartDate()) {
                try {
                    startDate = Instant.parse(request.getStartDate());
                } catch (DateTimeParseException e) {
                    LOG.warn("Invalid start date format: {}, expected ISO-8601 format", request.getStartDate());
                }
            }

            if (request.hasEndDate()) {
                try {
                    endDate = Instant.parse(request.getEndDate());
                } catch (DateTimeParseException e) {
                    LOG.warn("Invalid end date format: {}, expected ISO-8601 format", request.getEndDate());
                }
            }

            // List objects in the bucket
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(bucket);

            // Add prefix if provided
            if (prefix != null && !prefix.isEmpty()) {
                requestBuilder.prefix(prefix);
            }

            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            int objectsFound = 0;
            int objectsProcessed = 0;

            // Process each object that matches the date range
            for (S3Object s3Object : response.contents()) {
                objectsFound++;
                Instant lastModified = s3Object.lastModified();

                // Check if the object is within the date range
                boolean inRange = true;
                if (startDate != null && lastModified.isBefore(startDate)) {
                    inRange = false;
                }
                if (endDate != null && lastModified.isAfter(endDate)) {
                    inRange = false;
                }

                if (inRange) {
                    // Process the object
                    processS3Object(bucket, s3Object);
                    objectsProcessed++;
                }
            }

            LOG.info("Partial crawl completed for bucket: {}. Found {} objects, processed {} objects within date range.",
                    bucket, objectsFound, objectsProcessed);

        } catch (Exception e) {
            LOG.error("Error performing partial crawl for bucket: {}", bucket, e);
        }
    }

    /**
     * Handles a request for a single S3 object.
     *
     * @param bucket the S3 bucket name
     * @param key the S3 object key
     */
    private void handleSingleObject(String bucket, String key) {
        LOG.info("Processing single S3 object - bucket: {}, key: {}", bucket, key);

        try {
            // Download the object
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            GetObjectResponse objectResponse = s3Client.getObject(getObjectRequest, 
                    ResponseTransformer.toOutputStream(outputStream));

            // Create and send the PipeDoc
            createAndSendPipeDoc(bucket, key, outputStream.toByteArray(), objectResponse);

            LOG.info("Successfully processed S3 object: {}", key);
        } catch (Exception e) {
            LOG.error("Error processing S3 object: {}", key, e);
        }
    }

    /**
     * Processes an S3 object by downloading it and sending it to the output topic.
     *
     * @param bucket the S3 bucket name
     * @param s3Object the S3 object to process
     */
    private void processS3Object(String bucket, S3Object s3Object) {
        String key = s3Object.key();
        LOG.debug("Processing S3 object from partial crawl - bucket: {}, key: {}", bucket, key);

        try {
            // Download the object
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            GetObjectResponse objectResponse = s3Client.getObject(getObjectRequest, 
                    ResponseTransformer.toOutputStream(outputStream));

            // Create and send the PipeDoc
            createAndSendPipeDoc(bucket, key, outputStream.toByteArray(), objectResponse);

            LOG.debug("Successfully processed S3 object from partial crawl: {}", key);
        } catch (Exception e) {
            LOG.error("Error processing S3 object from partial crawl: {}", key, e);
        }
    }

    /**
     * Creates a PipeDoc from an S3 object and sends it to the output topic.
     *
     * @param bucket the S3 bucket name
     * @param key the S3 object key
     * @param data the object data
     * @param objectResponse the GetObjectResponse
     */
    private void createAndSendPipeDoc(String bucket, String key, byte[] data, GetObjectResponse objectResponse) {
        // Create a blob with the object data
        Blob.Builder blobBuilder = Blob.newBuilder()
                .setBlobId(UUID.randomUUID().toString())
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .setFilename(key);

        // Set MIME type if available
        if (objectResponse.contentType() != null) {
            blobBuilder.setMimeType(objectResponse.contentType());
        }

        // Add S3 metadata to the blob
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("s3_bucket", bucket);
        blobMetadata.put("s3_key", key);
        blobMetadata.put("s3_etag", objectResponse.eTag());
        blobMetadata.put("s3_content_length", String.valueOf(objectResponse.contentLength()));
        blobMetadata.put("s3_last_modified", objectResponse.lastModified().toString());

        // Add any additional metadata from the object response
        objectResponse.metadata().forEach(blobMetadata::put);

        blobBuilder.putAllMetadata(blobMetadata);

        // Create a new PipeDoc with the blob
        PipeDoc.Builder docBuilder = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSourceUri("s3://" + bucket + "/" + key)
                .setBlob(blobBuilder.build());

        // Set source MIME type if available
        if (objectResponse.contentType() != null) {
            docBuilder.setSourceMimeType(objectResponse.contentType());
        }

        // Create a UUID key for the Kafka message
        UUID messageKey = UUID.randomUUID();

        // Send the PipeDoc to the output topic
        s3CrawlProducer.sendDocument(messageKey, docBuilder.build());
    }
}
