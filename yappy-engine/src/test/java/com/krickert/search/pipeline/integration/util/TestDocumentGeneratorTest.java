package com.krickert.search.pipeline.integration.util;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.ProcessRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for TestDocumentGenerator.
 */
public class TestDocumentGeneratorTest {

    @Test
    public void testCreateSampleDocument() {
        // Arrange
        String id = "test-doc-id";
        String title = "Test Document";
        String body = "This is a test document body";
        Map<String, String> customData = new HashMap<>();
        customData.put("author", "Test Author");
        customData.put("category", "Test");
        boolean includeBlob = true;

        // Act
        PipeDoc document = TestDocumentGenerator.createSampleDocument(id, title, body, customData, includeBlob);

        // Assert
        assertNotNull(document);
        assertEquals(id, document.getId());
        assertEquals(title, document.getTitle());
        assertEquals(body, document.getBody());
        
        // Verify custom data
        assertTrue(document.hasCustomData());
        Struct customDataStruct = document.getCustomData();
        assertEquals("Test Author", customDataStruct.getFieldsOrThrow("author").getStringValue());
        assertEquals("Test", customDataStruct.getFieldsOrThrow("category").getStringValue());
        
        // Verify blob
        assertTrue(document.hasBlob());
        Blob blob = document.getBlob();
        assertTrue(blob.getBlobId().startsWith("blob-"));
        assertEquals("sample.txt", blob.getFilename());
        assertEquals("text/plain", blob.getMimeType());
        assertTrue(blob.getData().toStringUtf8().contains("Sample blob content for Test Document"));
    }

    @Test
    public void testCreateSampleDocumentWithoutBlob() {
        // Arrange
        String id = "test-doc-id";
        String title = "Test Document";
        String body = "This is a test document body";
        Map<String, String> customData = new HashMap<>();
        customData.put("author", "Test Author");
        boolean includeBlob = false;

        // Act
        PipeDoc document = TestDocumentGenerator.createSampleDocument(id, title, body, customData, includeBlob);

        // Assert
        assertNotNull(document);
        assertEquals(id, document.getId());
        assertEquals(title, document.getTitle());
        assertEquals(body, document.getBody());
        
        // Verify custom data
        assertTrue(document.hasCustomData());
        Struct customDataStruct = document.getCustomData();
        assertEquals("Test Author", customDataStruct.getFieldsOrThrow("author").getStringValue());
        
        // Verify no blob
        assertFalse(document.hasBlob());
    }

    @Test
    public void testCreatePipeStream() {
        // Arrange
        PipeDoc document = TestDocumentGenerator.createSampleDocument(
                "test-doc-id", "Test Document", "Test body", null, false);
        String pipelineName = "test-pipeline";
        String targetStepName = "test-step";
        Map<String, String> contextParams = new HashMap<>();
        contextParams.put("param1", "value1");

        // Act
        PipeStream pipeStream = TestDocumentGenerator.createPipeStream(
                document, pipelineName, targetStepName, contextParams);

        // Assert
        assertNotNull(pipeStream);
        assertTrue(pipeStream.getStreamId().startsWith("stream-"));
        assertEquals(document, pipeStream.getDocument());
        assertEquals(pipelineName, pipeStream.getCurrentPipelineName());
        assertEquals(targetStepName, pipeStream.getTargetStepName());
        assertEquals(0, pipeStream.getCurrentHopNumber());
        assertEquals("value1", pipeStream.getContextParamsOrThrow("param1"));
    }

    @Test
    public void testCreateProcessRequest() {
        // Arrange
        PipeDoc document = TestDocumentGenerator.createSampleDocument(
                "test-doc-id", "Test Document", "Test body", null, false);
        String pipelineName = "test-pipeline";
        String stepName = "test-step";
        String streamId = "test-stream-id";
        long hopNumber = 5;
        
        Struct.Builder customConfigBuilder = Struct.newBuilder();
        customConfigBuilder.putFields("configKey", Value.newBuilder().setStringValue("configValue").build());
        Struct customConfig = customConfigBuilder.build();
        
        Map<String, String> configParams = new HashMap<>();
        configParams.put("param1", "value1");

        // Act
        ProcessRequest request = TestDocumentGenerator.createProcessRequest(
                document, pipelineName, stepName, streamId, hopNumber, customConfig, configParams);

        // Assert
        assertNotNull(request);
        assertEquals(document, request.getDocument());
        
        // Verify metadata
        assertEquals(pipelineName, request.getMetadata().getPipelineName());
        assertEquals(stepName, request.getMetadata().getPipeStepName());
        assertEquals(streamId, request.getMetadata().getStreamId());
        assertEquals(hopNumber, request.getMetadata().getCurrentHopNumber());
        
        // Verify config
        assertEquals("configValue", request.getConfig().getCustomJsonConfig().getFieldsOrThrow("configKey").getStringValue());
        assertEquals("value1", request.getConfig().getConfigParamsOrThrow("param1"));
    }

    @Test
    public void testCreateSampleDocuments() {
        // Act
        List<PipeDoc> documents = TestDocumentGenerator.createSampleDocuments();

        // Assert
        assertNotNull(documents);
        assertFalse(documents.isEmpty());
        
        // Verify some expected documents
        boolean foundIntegrationDoc = false;
        boolean foundMicroservicesDoc = false;
        boolean foundShortStoryDoc = false;
        
        for (PipeDoc doc : documents) {
            if ("doc-integration".equals(doc.getId())) {
                foundIntegrationDoc = true;
                assertEquals("The team's Integration Test Skills", doc.getTitle());
                assertTrue(doc.getBody().contains("The team is absolutely incredible at creating integrated tests"));
                assertTrue(doc.hasBlob());
            } else if ("doc-microservices".equals(doc.getId())) {
                foundMicroservicesDoc = true;
                assertEquals("Understanding Microservices Architecture", doc.getTitle());
                assertTrue(doc.getBody().contains("Microservices architecture is an approach"));
                assertFalse(doc.hasBlob());
            } else if ("doc-short-story".equals(doc.getId())) {
                foundShortStoryDoc = true;
                assertEquals("The Bug That Couldn't Be Fixed", doc.getTitle());
                assertTrue(doc.getBody().contains("Once upon a time, there was a mysterious bug"));
                assertTrue(doc.hasBlob());
            }
        }
        
        assertTrue(foundIntegrationDoc, "Should contain the integration document");
        assertTrue(foundMicroservicesDoc, "Should contain the microservices document");
        assertTrue(foundShortStoryDoc, "Should contain the short story document");
        
        // Verify documents from CSV are loaded
        assertTrue(documents.size() > 5, "Should contain documents loaded from CSV");
    }
}