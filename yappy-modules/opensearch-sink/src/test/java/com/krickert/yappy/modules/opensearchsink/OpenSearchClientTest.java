package com.krickert.yappy.modules.opensearchsink;

import com.krickert.testcontainers.opensearch.OpenSearchTestResourceProvider;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jdk.jfr.Name;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.IndexResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.DeleteRequest;
import org.opensearch.client.opensearch.core.DeleteResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.CreateIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class OpenSearchClientTest {
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchClientTest.class);
    private static final String TEST_INDEX = "test-index";

    @Inject
    OpenSearchClient openSearchClientTest;

    @Test
    @DisplayName("Should create an index, add data, search, and delete data")
    void testOpenSearchOperations() throws IOException {
        LOG.info("[DEBUG_LOG] Starting OpenSearch operations test");

        // Step 1: Create an index
        boolean indexExists = openSearchClientTest.indices().exists(
                new ExistsRequest.Builder().index(TEST_INDEX).build()
        ).value();

        if (!indexExists) {
            LOG.info("[DEBUG_LOG] Creating index: {}", TEST_INDEX);
            CreateIndexResponse createIndexResponse = openSearchClientTest.indices().create(
                    new CreateIndexRequest.Builder().index(TEST_INDEX).build()
            );
            assertTrue(createIndexResponse.acknowledged(), "Index creation should be acknowledged");
            LOG.info("[DEBUG_LOG] Index created: {}", createIndexResponse.acknowledged());
        } else {
            LOG.info("[DEBUG_LOG] Index already exists: {}", TEST_INDEX);
        }

        // Step 2: Add data
        Map<String, Object> document = new HashMap<>();
        document.put("title", "Test Document");
        document.put("content", "This is a test document for OpenSearch");
        document.put("timestamp", System.currentTimeMillis());

        LOG.info("[DEBUG_LOG] Indexing document: {}", document);
        IndexResponse indexResponse = openSearchClientTest.index(
                new IndexRequest.Builder<>()
                        .index(TEST_INDEX)
                        .id("1")
                        .document(document)
                        .build()
        );

        assertEquals("1", indexResponse.id(), "Document ID should match");
        assertEquals(TEST_INDEX, indexResponse.index(), "Index name should match");
        LOG.info("[DEBUG_LOG] Document indexed with ID: {}", indexResponse.id());

        // Refresh the index to make the document available for search
        openSearchClientTest.indices().refresh();

        // Step 3: Search for the data
        LOG.info("[DEBUG_LOG] Searching for documents");
        SearchResponse<Map> searchResponse = openSearchClientTest.search(
                new SearchRequest.Builder()
                        .index(TEST_INDEX)
                        .query(q -> q.match(m -> m.field("title").query(v -> v.stringValue("Test"))))
                        .build(),
                Map.class
        );

        assertEquals(1, searchResponse.hits().total().value(), "Should find one document");
        assertEquals("1", searchResponse.hits().hits().get(0).id(), "Document ID should match");
        LOG.info("[DEBUG_LOG] Found {} documents", searchResponse.hits().total().value());

        // Step 4: Delete the data
        LOG.info("[DEBUG_LOG] Deleting document with ID: 1");
        DeleteResponse deleteResponse = openSearchClientTest.delete(
                new DeleteRequest.Builder()
                        .index(TEST_INDEX)
                        .id("1")
                        .build()
        );

        assertEquals("1", deleteResponse.id(), "Deleted document ID should match");
        LOG.info("[DEBUG_LOG] Document deleted with ID: {}", deleteResponse.id());

        // Refresh the index again
        openSearchClientTest.indices().refresh();

        // Verify the document is deleted
        searchResponse = openSearchClientTest.search(
                new SearchRequest.Builder()
                        .index(TEST_INDEX)
                        .query(q -> q.match(m -> m.field("title").query(v -> v.stringValue("Test"))))
                        .build(),
                Map.class
        );

        assertEquals(0, searchResponse.hits().total().value(), "Should find no documents after deletion");
        LOG.info("[DEBUG_LOG] Found {} documents after deletion", searchResponse.hits().total().value());

        // Step 5: Delete the index
        LOG.info("[DEBUG_LOG] Deleting index: {}", TEST_INDEX);
        DeleteIndexResponse deleteIndexResponse = openSearchClientTest.indices().delete(
                new DeleteIndexRequest.Builder()
                        .index(TEST_INDEX)
                        .build()
        );

        assertTrue(deleteIndexResponse.acknowledged(), "Index deletion should be acknowledged");
        LOG.info("[DEBUG_LOG] Index deleted: {}", deleteIndexResponse.acknowledged());

        // Verify the index is deleted
        indexExists = openSearchClientTest.indices().exists(
                new ExistsRequest.Builder().index(TEST_INDEX).build()
        ).value();

        assertFalse(indexExists, "Index should not exist after deletion");
        LOG.info("[DEBUG_LOG] Index exists after deletion: {}", indexExists);

        LOG.info("[DEBUG_LOG] OpenSearch operations test completed successfully");
    }
}
