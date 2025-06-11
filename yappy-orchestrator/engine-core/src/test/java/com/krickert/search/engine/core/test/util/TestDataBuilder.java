package com.krickert.search.engine.core.test.util;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Builder class for creating test data with various configurations.
 * Provides fluent API for constructing PipeStreams with different characteristics.
 */
public class TestDataBuilder {
    
    private final List<PipeStream> streams = new ArrayList<>();
    
    /**
     * Creates a new TestDataBuilder instance
     */
    public static TestDataBuilder create() {
        return new TestDataBuilder();
    }
    
    /**
     * Adds a simple text document
     */
    public TestDataBuilder withTextDocument(String id, String content) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setBody(content)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Adds a document with metadata
     */
    public TestDataBuilder withDocumentAndMetadata(String id, String content, Map<String, String> metadata) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        Struct.Builder structBuilder = Struct.newBuilder();
        metadata.forEach((key, value) -> {
            structBuilder.putFields(key, Value.newBuilder().setStringValue(value).build());
        });
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setBody(content)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .setCustomData(structBuilder.build())
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Adds a JSON document
     */
    public TestDataBuilder withJsonDocument(String id, String jsonContent) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setBody(jsonContent)
                .setDocumentType("json")
                .setSourceMimeType("application/json")
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Adds a binary document (e.g., PDF simulation)
     */
    public TestDataBuilder withBinaryDocument(String id, byte[] binaryData, String mimeType) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        // For binary data, we'd typically extract text first
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setBody("[Binary content - text extraction required]")
                .setSourceMimeType(mimeType)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Adds multiple documents with a pattern
     */
    public TestDataBuilder withDocumentBatch(String prefix, int count) {
        for (int i = 0; i < count; i++) {
            withTextDocument(
                    prefix + "-" + i,
                    "Document " + i + " content: Lorem ipsum dolor sit amet..."
            );
        }
        return this;
    }
    
    /**
     * Adds a large document for testing size limits
     */
    public TestDataBuilder withLargeDocument(String id, int sizeInKb) {
        StringBuilder content = new StringBuilder();
        String chunk = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";
        
        // Each chunk is ~58 bytes, so calculate how many we need
        int chunksNeeded = (sizeInKb * 1024) / chunk.length();
        
        for (int i = 0; i < chunksNeeded; i++) {
            content.append(chunk);
        }
        
        return withTextDocument(id, content.toString());
    }
    
    /**
     * Adds a document with S3 metadata
     */
    public TestDataBuilder withS3Document(String id, String bucket, String key, String content) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("s3.bucket", bucket);
        metadata.put("s3.key", key);
        metadata.put("s3.region", "us-east-1");
        metadata.put("source", "s3");
        
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        Struct.Builder structBuilder = Struct.newBuilder();
        metadata.forEach((k, v) -> structBuilder.putFields(k, Value.newBuilder().setStringValue(v).build()));
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setBody(content)
                .setSourceUri("s3://" + bucket + "/" + key)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .setCustomData(structBuilder.build())
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Adds a document with web crawler metadata
     */
    public TestDataBuilder withWebDocument(String id, String url, String content, String title) {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .build();
        
        Struct.Builder structBuilder = Struct.newBuilder();
        structBuilder.putFields("source.url", Value.newBuilder().setStringValue(url).build());
        structBuilder.putFields("crawl.timestamp", Value.newBuilder().setStringValue(Instant.now().toString()).build());
        structBuilder.putFields("source", Value.newBuilder().setStringValue("web").build());
        
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(id)
                .setTitle(title)
                .setBody(content)
                .setSourceUri(url)
                .setCreationDate(now)
                .setLastModifiedDate(now)
                .setProcessedDate(now)
                .setCustomData(structBuilder.build())
                .build();
        
        PipeStream stream = PipeStream.newBuilder()
                .setStreamId(UUID.randomUUID().toString())
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("next-step")
                .setCurrentHopNumber(0)
                .build();
        
        streams.add(stream);
        return this;
    }
    
    /**
     * Builds a single PipeStream (returns the first one)
     */
    public PipeStream buildSingle() {
        if (streams.isEmpty()) {
            throw new IllegalStateException("No documents added");
        }
        return streams.get(0);
    }
    
    /**
     * Builds a PipeStreams message with all added documents
     */
    public List<PipeStream> build() {
        return new ArrayList<>(streams);
    }
    
    /**
     * Returns the list of PipeStream objects
     */
    public List<PipeStream> buildList() {
        return new ArrayList<>(streams);
    }
    
    /**
     * Clears all streams and starts fresh
     */
    public TestDataBuilder clear() {
        streams.clear();
        return this;
    }
    
    /**
     * Creates common test scenarios
     */
    public static class Scenarios {
        
        /**
         * Creates a simple text processing scenario
         */
        public static List<PipeStream> simpleTextProcessing() {
            return TestDataBuilder.create()
                    .withTextDocument("doc1", "The quick brown fox jumps over the lazy dog.")
                    .withTextDocument("doc2", "Pack my box with five dozen liquor jugs.")
                    .withTextDocument("doc3", "How vexingly quick daft zebras jump!")
                    .build();
        }
        
        /**
         * Creates a mixed content type scenario
         */
        public static List<PipeStream> mixedContentTypes() {
            return TestDataBuilder.create()
                    .withTextDocument("text-1", "Plain text document")
                    .withJsonDocument("json-1", "{\"title\": \"JSON Document\", \"content\": \"Some content\"}")
                    .withBinaryDocument("pdf-1", "Mock PDF content".getBytes(), "application/pdf")
                    .build();
        }
        
        /**
         * Creates an S3 batch processing scenario
         */
        public static List<PipeStream> s3BatchProcessing() {
            return TestDataBuilder.create()
                    .withS3Document("s3-doc-1", "my-bucket", "documents/doc1.txt", "Document 1 from S3")
                    .withS3Document("s3-doc-2", "my-bucket", "documents/doc2.txt", "Document 2 from S3")
                    .withS3Document("s3-doc-3", "my-bucket", "documents/doc3.txt", "Document 3 from S3")
                    .build();
        }
        
        /**
         * Creates a web crawling scenario
         */
        public static List<PipeStream> webCrawlingScenario() {
            return TestDataBuilder.create()
                    .withWebDocument("web-1", "https://example.com/page1", 
                            "Page 1 content", "Example Page 1")
                    .withWebDocument("web-2", "https://example.com/page2", 
                            "Page 2 content", "Example Page 2")
                    .build();
        }
    }
}