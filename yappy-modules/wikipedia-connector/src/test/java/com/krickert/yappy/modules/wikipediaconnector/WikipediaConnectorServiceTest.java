package com.krickert.yappy.modules.wikipediaconnector;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.yappy.modules.wikipediaconnector.config.WikipediaConnectorConfig;
import com.krickert.yappy.modules.wikipediaconnector.service.WikipediaService;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.dkpro.jwpl.api.Wikipedia;
import org.dkpro.jwpl.api.exception.WikiApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MicronautTest(environments = "test")
@ExtendWith(MockitoExtension.class)
class WikipediaConnectorServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaConnectorServiceTest.class);

    @Inject
    private WikipediaConnectorService wikipediaConnectorService;

    @Mock
    private StreamObserver<ProcessResponse> responseObserver;

    @Captor
    private ArgumentCaptor<ProcessResponse> responseCaptor;

    /**
     * Mock implementation of the WikipediaService for testing.
     */
    @Singleton
    @Replaces(WikipediaService.class)
    static class MockWikipediaService extends WikipediaService {

        @Mock
        private Wikipedia wikipedia;

        public MockWikipediaService() {
            wikipedia = Mockito.mock(Wikipedia.class);
        }

        @Override
        public Wikipedia createWikipediaApi(WikipediaConnectorConfig config) {
            return wikipedia;
        }

        @Override
        public PipeDoc getArticleByTitle(Wikipedia wikipedia, String title) throws WikiApiException {
            // Create a mock article
            Blob blob = Blob.newBuilder()
                    .setBlobId(UUID.randomUUID().toString())
                    .setData(com.google.protobuf.ByteString.copyFromUtf8("Mock Wikipedia article content for " + title))
                    .setFilename(title.replace(" ", "_") + ".txt")
                    .setMimeType("text/plain")
                    .putMetadata("wikipedia_language", "en")
                    .putMetadata("wikipedia_title", title)
                    .putMetadata("wikipedia_id", "12345")
                    .putMetadata("wikipedia_source", "dkpro-jwpl")
                    .build();

            return PipeDoc.newBuilder()
                    .setId(UUID.randomUUID().toString())
                    .setSourceUri("wikipedia://en/" + title)
                    .setSourceMimeType("text/plain")
                    .setBlob(blob)
                    .build();
        }

        @Override
        public List<PipeDoc> getArticlesByCategory(Wikipedia wikipedia, String categoryName, 
                                                  int maxArticles, boolean includeSubcategories) throws WikiApiException {
            List<PipeDoc> articles = new ArrayList<>();

            // Create a few mock articles
            for (int i = 1; i <= 3; i++) {
                String title = "Article " + i + " in " + categoryName;
                articles.add(getArticleByTitle(wikipedia, title));
            }

            return articles;
        }
    }

    @Test
    void testProcessDataWithTitleFilter() {
        // Create a test request with a title filter
        ProcessRequest request = createTestRequest("en", "Test Article", null);

        // Process the request
        wikipediaConnectorService.processData(request, responseObserver);

        // Verify the response
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        ProcessResponse response = responseCaptor.getValue();
        assertTrue(response.getSuccess(), "Response should be successful");
        assertFalse(response.getProcessorLogsList().isEmpty(), "Response should have processor logs");

        PipeDoc outputDoc = response.getOutputDoc();
        assertNotNull(outputDoc, "Output document should not be null");
        assertTrue(outputDoc.hasBlob(), "Output document should have a blob");
        assertEquals("text/plain", outputDoc.getSourceMimeType(), "Source MIME type should be text/plain");
        assertTrue(outputDoc.getSourceUri().startsWith("wikipedia://"), "Source URI should start with wikipedia://");

        // Check blob metadata
        Map<String, String> metadata = outputDoc.getBlob().getMetadataMap();
        assertEquals("en", metadata.get("wikipedia_language"), "Language should be 'en'");
        assertEquals("Test Article", metadata.get("wikipedia_title"), "Title should be 'Test Article'");
        assertEquals("dkpro-jwpl", metadata.get("wikipedia_source"), "Source should be 'dkpro-jwpl'");

        // Check blob content
        String content = outputDoc.getBlob().getData().toStringUtf8();
        assertTrue(content.contains("Mock Wikipedia article content"), "Content should contain mock article text");
    }

    @Test
    void testProcessDataWithCategoryFilter() {
        // Create a test request with a category filter
        ProcessRequest request = createTestRequest("en", null, "Test Category");

        // Process the request
        wikipediaConnectorService.processData(request, responseObserver);

        // Verify the response
        verify(responseObserver).onNext(responseCaptor.capture());
        verify(responseObserver).onCompleted();

        ProcessResponse response = responseCaptor.getValue();
        assertTrue(response.getSuccess(), "Response should be successful");
        assertFalse(response.getProcessorLogsList().isEmpty(), "Response should have processor logs");

        PipeDoc outputDoc = response.getOutputDoc();
        assertNotNull(outputDoc, "Output document should not be null");
        assertTrue(outputDoc.hasBlob(), "Output document should have a blob");
        assertEquals("text/plain", outputDoc.getSourceMimeType(), "Source MIME type should be text/plain");
        assertTrue(outputDoc.getSourceUri().startsWith("wikipedia://"), "Source URI should start with wikipedia://");

        // Check blob metadata
        Map<String, String> metadata = outputDoc.getBlob().getMetadataMap();
        assertEquals("en", metadata.get("wikipedia_language"), "Language should be 'en'");
        assertTrue(metadata.get("wikipedia_title").contains("Test Category"), "Title should contain the category name");
        assertEquals("dkpro-jwpl", metadata.get("wikipedia_source"), "Source should be 'dkpro-jwpl'");

        // Check blob content
        String content = outputDoc.getBlob().getData().toStringUtf8();
        assertTrue(content.contains("Mock Wikipedia article content"), "Content should contain mock article text");
    }

    private ProcessRequest createTestRequest(String language, String title, String category) {
        // Create a test document
        PipeDoc document = PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .build();

        // Create metadata
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("wikipedia-connector")
                .setStreamId(UUID.randomUUID().toString())
                .build();

        // Create configuration with custom JSON config
        Map<String, Value> configMap = new HashMap<>();
        configMap.put("language", Value.newBuilder().setStringValue(language).build());
        configMap.put("database", Value.newBuilder().setStringValue("wikipedia").build());

        // Add title filter if provided
        if (title != null && !title.isEmpty()) {
            configMap.put("titleFilter", Value.newBuilder().setStringValue(title).build());
        }

        // Add category filter if provided
        if (category != null && !category.isEmpty()) {
            configMap.put("categoryFilter", Value.newBuilder().setStringValue(category).build());
        }

        Struct customConfig = Struct.newBuilder()
                .putAllFields(configMap)
                .build();

        ProcessConfiguration config = ProcessConfiguration.newBuilder()
                .setCustomJsonConfig(customConfig)
                .build();

        // Create the request
        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(config)
                .build();
    }
}
