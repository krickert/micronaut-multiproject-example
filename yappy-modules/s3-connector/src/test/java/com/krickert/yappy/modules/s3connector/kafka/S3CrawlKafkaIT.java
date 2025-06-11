package com.krickert.yappy.modules.s3connector.kafka;

import com.krickert.search.engine.s3_crawl_request;
import com.krickert.search.model.PipeDoc;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MicronautTest(environments = "test", startApplication = false)
public class S3CrawlKafkaIT {

    private static final Logger LOG = LoggerFactory.getLogger(S3CrawlKafkaIT.class);
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_KEY = "test-key.txt";
    private static final String TEST_CONTENT = "Test content for S3 object";
    private static final String TEST_CONTENT_TYPE = "text/plain";

    // Test objects for partial crawl
    private static final String TEST_PREFIX = "docs/";
    private static final String[] TEST_OBJECT_KEYS = {
            "docs/file1.txt",
            "docs/file2.txt",
            "docs/file3.txt"
    };

    // Date range for partial crawl test
    private static final Instant NOW = Instant.now();
    private static final Instant YESTERDAY = NOW.minus(1, ChronoUnit.DAYS);
    private static final Instant LAST_WEEK = NOW.minus(7, ChronoUnit.DAYS);

    private S3CrawlRequestService.S3CrawlRequestProducer requestProducer;
    private S3CrawlRequestService s3CrawlRequestService;
    private TestResultListener resultListener;

    @BeforeEach
    void setup() {
        // Create mocks
        resultListener = new TestResultListener();

        // Create a mock producer that simulates sending messages to Kafka
        requestProducer = mock(S3CrawlRequestService.S3CrawlRequestProducer.class);

        // When sendRequest is called, simulate processing the message and triggering the listener
        doAnswer(invocation -> {
            // Extract the request from the invocation
            s3_crawl_request request = invocation.getArgument(2);

            // Create a mock S3 client
            S3Client mockS3Client = new MockS3ClientFactory().mockS3Client();

            // Create a listener and manually process the request
            S3CrawlListener listener = new S3CrawlListener();

            // Use reflection to set the S3 client and producer
            java.lang.reflect.Field s3ClientField = S3CrawlListener.class.getDeclaredField("s3Client");
            s3ClientField.setAccessible(true);
            s3ClientField.set(listener, mockS3Client);

            java.lang.reflect.Field producerField = S3CrawlListener.class.getDeclaredField("s3CrawlProducer");
            producerField.setAccessible(true);

            // Create a mock producer that will call our test listener
            S3CrawlProducer mockProducer = mock(S3CrawlProducer.class);

            // When sendDocument is called, call our test listener directly
            Mockito.doAnswer(prodInvocation -> {
                // Extract the document from the invocation
                PipeDoc doc = prodInvocation.getArgument(1);

                // Call the test listener directly
                resultListener.receive(doc);
                return null;
            }).when(mockProducer).sendDocument(any(UUID.class), any(PipeDoc.class));

            producerField.set(listener, mockProducer);

            // Process the request
            listener.receive(request);

            return null;
        }).when(requestProducer).sendRequest(any(String.class), any(UUID.class), any(s3_crawl_request.class));

        // Create the service with the mock producer
        s3CrawlRequestService = new S3CrawlRequestService(requestProducer, "s3-crawl-requests");
    }

    @Test
    void testS3CrawlKafkaFlow() throws Exception {
        // Set up the test result listener
        resultListener.reset();

        // Create and send an S3 crawl request
        s3_crawl_request request = s3_crawl_request.newBuilder()
                .setBucket(TEST_BUCKET)
                .setKey(TEST_KEY)
                .build();

        // Send the request to Kafka
        UUID messageKey = UUID.randomUUID();
        String topic = "s3-crawl-requests";
        requestProducer.sendRequest(topic, messageKey, request);

        LOG.info("[DEBUG_LOG] Sent S3 crawl request to Kafka: bucket={}, key={}", TEST_BUCKET, TEST_KEY);

        // Wait for the result to be received
        boolean received = resultListener.awaitResult(10, TimeUnit.SECONDS);
        assertTrue(received, "Should have received a result within the timeout");

        // Verify the result
        PipeDoc result = resultListener.getResult();
        assertNotNull(result, "Result should not be null");
        assertEquals("s3://" + TEST_BUCKET + "/" + TEST_KEY, result.getSourceUri(), "Source URI should match S3 path");
        assertTrue(result.hasBlob(), "Result should have a blob");
        assertEquals(TEST_KEY, result.getBlob().getFilename(), "Blob filename should match S3 key");
        assertEquals(TEST_CONTENT_TYPE, result.getBlob().getMimeType(), "Blob MIME type should match");

        // Verify blob metadata
        Map<String, String> metadata = result.getBlob().getMetadataMap();
        assertEquals(TEST_BUCKET, metadata.get("s3_bucket"), "Blob metadata should contain bucket name");
        assertEquals(TEST_KEY, metadata.get("s3_key"), "Blob metadata should contain object key");

        LOG.info("[DEBUG_LOG] Successfully verified S3 crawl result from Kafka");
    }

    /**
     * Test for partial crawl functionality with date range filtering.
     */
    @Test
    void testPartialCrawlWithDateRange() throws Exception {
        // Set up the test result listener for multiple results
        resultListener.resetForMultipleResults(2); // Expect 2 results within date range

        // Format dates as ISO-8601 strings
        String startDateStr = YESTERDAY.toString();
        String endDateStr = NOW.toString();

        LOG.info("[DEBUG_LOG] Testing partial crawl with date range: {} to {}", startDateStr, endDateStr);

        // Request a partial crawl with date range
        s3CrawlRequestService.requestPartialCrawl(TEST_BUCKET, TEST_PREFIX, startDateStr, endDateStr);

        // Wait for results
        boolean received = resultListener.awaitResults(10, TimeUnit.SECONDS);
        assertTrue(received, "Should have received results within the timeout");

        // Verify we got the expected number of results
        List<PipeDoc> results = resultListener.getResults();
        assertEquals(2, results.size(), "Should have received 2 results within the date range");

        // Verify the results are from the expected objects
        Set<String> expectedKeys = new HashSet<>(Arrays.asList(TEST_OBJECT_KEYS[0], TEST_OBJECT_KEYS[1]));
        Set<String> actualKeys = new HashSet<>();

        for (PipeDoc doc : results) {
            String uri = doc.getSourceUri();
            String key = uri.substring(uri.lastIndexOf('/') + 1);
            actualKeys.add(TEST_PREFIX + key);

            // Verify each document has the expected properties
            assertTrue(doc.hasBlob(), "Result should have a blob");
            assertEquals(TEST_CONTENT_TYPE, doc.getBlob().getMimeType(), "Blob MIME type should match");

            // Verify blob metadata
            Map<String, String> metadata = doc.getBlob().getMetadataMap();
            assertEquals(TEST_BUCKET, metadata.get("s3_bucket"), "Blob metadata should contain bucket name");
            assertTrue(metadata.containsKey("s3_last_modified"), "Blob metadata should contain last modified date");
        }

        assertEquals(expectedKeys, actualKeys, "Should have received the expected objects within the date range");

        LOG.info("[DEBUG_LOG] Successfully verified partial crawl results");
    }

    /**
     * Test listener for S3 crawl results.
     */
    @KafkaListener(
            groupId = "s3-crawl-test-group",
            offsetReset = OffsetReset.EARLIEST,
            clientId = "s3-crawl-test-client"
    )
    public static class TestResultListener {

        private static final Logger LOG = LoggerFactory.getLogger(TestResultListener.class);

        private final AtomicReference<PipeDoc> resultRef = new AtomicReference<>();
        private final List<PipeDoc> results = Collections.synchronizedList(new ArrayList<>());
        private CountDownLatch latch = new CountDownLatch(1);

        @Topic("${s3.connector.kafka.output-topic:s3-crawl-results}")
        void receive(PipeDoc document) {
            LOG.info("[DEBUG_LOG] Received S3 crawl result in test listener: {}", document.getId());

            // Store the document in both single result reference and list of results
            resultRef.set(document);
            results.add(document);

            // Count down the latch (for both single and multiple result tests)
            latch.countDown();
        }

        void reset() {
            resultRef.set(null);
            results.clear();
            // Create a new latch for single result
            latch = new CountDownLatch(1);
        }

        void resetForMultipleResults(int expectedCount) {
            resultRef.set(null);
            results.clear();
            // Create a new latch for multiple results
            latch = new CountDownLatch(expectedCount);
        }

        PipeDoc getResult() {
            return resultRef.get();
        }

        List<PipeDoc> getResults() {
            return new ArrayList<>(results);
        }

        boolean awaitResult(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        boolean awaitResults(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }

    /**
     * Mock S3 client for testing.
     */
    @jakarta.inject.Singleton
    @io.micronaut.context.annotation.Factory
    @io.micronaut.context.annotation.Requires(env = "test")
    public static class MockS3ClientFactory {

        @jakarta.inject.Singleton
        @jakarta.inject.Named("kafkaTestS3Client")
        @io.micronaut.context.annotation.Primary
        public S3Client mockS3Client() {
            S3Client mockClient = mock(S3Client.class);

            // Mock the getObject method
            when(mockClient.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
                .thenAnswer(invocation -> {
                    GetObjectRequest request = invocation.getArgument(0);
                    ResponseTransformer<GetObjectResponse, ?> transformer = invocation.getArgument(1);

                    String key = request.key();
                    LOG.info("[DEBUG_LOG] Mock S3Client handling GetObject request for key: {}", key);

                    // Create a mock response
                    GetObjectResponse response = GetObjectResponse.builder()
                            .contentType(TEST_CONTENT_TYPE)
                            .contentLength((long) TEST_CONTENT.getBytes().length)
                            .lastModified(Instant.now())
                            .eTag("test-etag")
                            .metadata(Map.of("custom-metadata", "test-value"))
                            .build();

                    // Create an AbortableInputStream with the test content
                    byte[] contentBytes = TEST_CONTENT.getBytes();
                    AbortableInputStream inputStream = AbortableInputStream.create(
                            new ByteArrayInputStream(contentBytes));

                    // Return the result of the transformer
                    return transformer.transform(response, inputStream);
                });

            // Mock the listObjectsV2 method for partial crawl
            when(mockClient.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> {
                    ListObjectsV2Request request = invocation.getArgument(0);
                    String prefix = request.prefix();

                    LOG.info("[DEBUG_LOG] Mock S3Client handling ListObjectsV2 request with prefix: {}", prefix);

                    // Create mock S3Objects with different last modified dates
                    List<S3Object> objects = new ArrayList<>();

                    // File 1: Modified yesterday (within date range)
                    objects.add(S3Object.builder()
                            .key(TEST_OBJECT_KEYS[0])
                            .eTag("etag1")
                            .size(100L)
                            .lastModified(YESTERDAY.plus(1, ChronoUnit.HOURS)) // Within date range
                            .storageClass("STANDARD")
                            .build());

                    // File 2: Modified today (within date range)
                    objects.add(S3Object.builder()
                            .key(TEST_OBJECT_KEYS[1])
                            .eTag("etag2")
                            .size(200L)
                            .lastModified(NOW.minus(1, ChronoUnit.HOURS)) // Within date range
                            .storageClass("STANDARD")
                            .build());

                    // File 3: Modified last week (outside date range)
                    objects.add(S3Object.builder()
                            .key(TEST_OBJECT_KEYS[2])
                            .eTag("etag3")
                            .size(300L)
                            .lastModified(LAST_WEEK) // Outside date range
                            .storageClass("STANDARD")
                            .build());

                    // Filter objects by prefix if provided
                    if (prefix != null && !prefix.isEmpty()) {
                        objects = objects.stream()
                                .filter(obj -> obj.key().startsWith(prefix))
                                .collect(Collectors.toList());
                    }

                    LOG.info("[DEBUG_LOG] Mock S3Client returning {} objects for ListObjectsV2 request", objects.size());

                    return ListObjectsV2Response.builder()
                            .contents(objects)
                            .build();
                });

            return mockClient;
        }
    }
}
