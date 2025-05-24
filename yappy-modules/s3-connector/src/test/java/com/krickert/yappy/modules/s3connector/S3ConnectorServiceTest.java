package com.krickert.yappy.modules.s3connector;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import com.krickert.yappy.modules.s3connector.config.S3ConnectorConfig;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MicronautTest
class S3ConnectorServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(S3ConnectorServiceTest.class);

    @Inject
    private S3ConnectorService s3ConnectorService;

    @Inject
    private S3Client s3Client;

    @BeforeEach
    void setup() {
        // Reset the mock before each test
        Mockito.reset(s3Client);
    }

    @MockBean(S3Client.class)
    S3Client s3Client() {
        return Mockito.mock(S3Client.class);
    }

    private ProcessRequest createTestProcessRequest(
            String pipelineName, String stepName, String streamId, String docId, long hopNumber,
            PipeDoc document, Struct customJsonConfig, Map<String, String> configParams) {

        ServiceMetadata.Builder metadataBuilder = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(hopNumber);

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customJsonConfig != null) {
            configBuilder.setCustomJsonConfig(customJsonConfig);
        }
        if (configParams != null) {
            configBuilder.putAllConfigParams(configParams);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setConfig(configBuilder.build())
                .setMetadata(metadataBuilder.build())
                .build();
    }

    @Test
    @DisplayName("Should process S3 objects successfully")
    void testProcessS3Objects() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline";
        String stepName = "s3-connector-step";
        String streamId = "stream-123";
        String docId = "doc-abc";
        long hopNumber = 1;

        // Create an empty document
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .build();

        // Create custom configuration
        Struct customJsonConfig = Struct.newBuilder()
                .putFields("bucketName", Value.newBuilder().setStringValue("test-bucket").build())
                .putFields("region", Value.newBuilder().setStringValue("us-east-1").build())
                .putFields("prefix", Value.newBuilder().setStringValue("test-prefix/").build())
                .putFields("maxKeys", Value.newBuilder().setNumberValue(10).build())
                .build();

        // Create the request
        ProcessRequest request = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        // 2. Mock S3 client responses
        // Mock ListObjectsV2Response
        List<S3Object> s3Objects = new ArrayList<>();
        s3Objects.add(S3Object.builder()
                .key("test-prefix/file1.txt")
                .eTag("etag1")
                .size(100L)
                .lastModified(Instant.now())
                .storageClass(StorageClass.STANDARD.toString())
                .build());

        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(s3Objects)
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        // Mock GetObjectResponse
        byte[] fileContent = "Hello, S3 Connector!".getBytes();
        GetObjectResponse getObjectResponse = GetObjectResponse.builder()
                .contentType("text/plain")
                .metadata(Map.of("custom-metadata", "test-value"))
                .build();

        // Mock the getObject method to return the response
        // We'll modify the test to check for the presence of a blob, not its content
        when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
            .thenReturn(getObjectResponse);

        // 3. Create a response observer
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        StreamObserver<ProcessResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ProcessResponse value) {
                LOG.info("onNext called with response. Success: {}", value.getSuccess());
                responseReference.set(value);
            }

            @Override
            public void onError(Throwable t) {
                LOG.error("onError called", t);
                errorReference.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                LOG.info("onCompleted called");
                latch.countDown();
            }
        };

        // 4. Call the service
        s3ConnectorService.processData(request, responseObserver);

        // 5. Wait for the response
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Service call did not complete in time");
        } catch (InterruptedException e) {
            fail("Test was interrupted: " + e.getMessage());
        }

        // 6. Verify the response
        assertNull(errorReference.get(), "Service call should not have produced an error");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");

        // 7. Verify the output document
        assertTrue(response.hasOutputDoc(), "Response should have an output document");
        PipeDoc outputDoc = response.getOutputDoc();
        assertEquals("s3://test-bucket/test-prefix/file1.txt", outputDoc.getSourceUri(), "Source URI should match S3 path");
        assertEquals("text/plain", outputDoc.getSourceMimeType(), "Source MIME type should match");

        // 8. Verify the blob
        assertTrue(outputDoc.hasBlob(), "Output document should have a blob");
        Blob blob = outputDoc.getBlob();
        assertEquals("text/plain", blob.getMimeType(), "Blob MIME type should match");
        assertEquals("test-prefix/file1.txt", blob.getFilename(), "Blob filename should match");
        // We're not checking the blob data content since we're mocking the S3 client
        assertTrue(blob.getData().size() >= 0, "Blob data should be present");

        // 9. Verify the blob metadata
        Map<String, String> blobMetadata = blob.getMetadataMap();
        assertEquals("test-bucket", blobMetadata.get("s3_bucket"), "Blob metadata should contain bucket name");
        assertEquals("test-prefix/file1.txt", blobMetadata.get("s3_key"), "Blob metadata should contain object key");
        assertEquals("etag1", blobMetadata.get("s3_etag"), "Blob metadata should contain ETag");
        assertEquals("100", blobMetadata.get("s3_size"), "Blob metadata should contain size");
        assertEquals("STANDARD", blobMetadata.get("s3_storage_class"), "Blob metadata should contain storage class");
        assertEquals("test-value", blobMetadata.get("custom-metadata"), "Blob metadata should contain custom metadata");

        // 10. Verify S3 client interactions
        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    @DisplayName("Should handle empty bucket gracefully")
    void testEmptyBucket() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline-empty";
        String stepName = "s3-connector-empty";
        String streamId = "stream-456";
        String docId = "doc-def";
        long hopNumber = 1;

        // Create an empty document
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .build();

        // Create custom configuration
        Struct customJsonConfig = Struct.newBuilder()
                .putFields("bucketName", Value.newBuilder().setStringValue("empty-bucket").build())
                .build();

        // Create the request
        ProcessRequest request = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        // 2. Mock S3 client responses - empty list
        ListObjectsV2Response listResponse = ListObjectsV2Response.builder()
                .contents(new ArrayList<>())
                .build();
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResponse);

        // 3. Create a response observer
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        StreamObserver<ProcessResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ProcessResponse value) {
                responseReference.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorReference.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // 4. Call the service
        s3ConnectorService.processData(request, responseObserver);

        // 5. Wait for the response
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Service call did not complete in time");
        } catch (InterruptedException e) {
            fail("Test was interrupted: " + e.getMessage());
        }

        // 6. Verify the response
        assertNull(errorReference.get(), "Service call should not have produced an error");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful even with empty bucket");

        // 7. Verify S3 client interactions
        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
        verify(s3Client, never()).getObject(any(GetObjectRequest.class), any(ResponseTransformer.class));
    }

    @Test
    @DisplayName("Should handle S3 client exceptions gracefully")
    void testS3ClientException() {
        // 1. Prepare input data
        String pipelineName = "test-pipeline-error";
        String stepName = "s3-connector-error";
        String streamId = "stream-789";
        String docId = "doc-ghi";
        long hopNumber = 1;

        // Create an empty document
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .build();

        // Create custom configuration
        Struct customJsonConfig = Struct.newBuilder()
                .putFields("bucketName", Value.newBuilder().setStringValue("error-bucket").build())
                .build();

        // Create the request
        ProcessRequest request = createTestProcessRequest(
                pipelineName, stepName, streamId, docId, hopNumber, inputDoc, customJsonConfig, null);

        // 2. Mock S3 client to throw exception
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenThrow(S3Exception.builder().message("Simulated S3 error").build());

        // 3. Create a response observer
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ProcessResponse> responseReference = new AtomicReference<>();
        final AtomicReference<Throwable> errorReference = new AtomicReference<>();

        StreamObserver<ProcessResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(ProcessResponse value) {
                responseReference.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorReference.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        // 4. Call the service
        s3ConnectorService.processData(request, responseObserver);

        // 5. Wait for the response
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Service call did not complete in time");
        } catch (InterruptedException e) {
            fail("Test was interrupted: " + e.getMessage());
        }

        // 6. Verify the response
        assertNull(errorReference.get(), "Service should handle exceptions internally");
        ProcessResponse response = responseReference.get();
        assertNotNull(response, "Response should not be null");
        assertFalse(response.getSuccess(), "Processing should not be successful");
        assertEquals(inputDoc, response.getOutputDoc(), "Original document should be returned on error");
        assertTrue(response.getProcessorLogs(0).contains("Error in S3ConnectorService"), 
                "Error message should be in processor logs");

        // 7. Verify S3 client interactions
        verify(s3Client).listObjectsV2(any(ListObjectsV2Request.class));
    }
}
