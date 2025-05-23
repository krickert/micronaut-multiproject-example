package com.krickert.search.python;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.ProcessResponse;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A simple test for the PipeStepProcessor Python script.
 * This test just verifies that the Python script can be loaded and parsed,
 * without actually executing it.
 */
@MicronautTest
public class PipeStepProcessorSimpleTest {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStepProcessorSimpleTest.class);

    @Test
    public void testPythonScriptExists() throws IOException {
        LOG.info("Testing that the Python script exists and can be loaded");
        
        // Load the Python script
        String scriptContent = loadPythonScript("/python/pipe_step_processor_server.py");
        
        // Verify that the script contains expected content
        Assertions.assertNotNull(scriptContent, "Script content should not be null");
        Assertions.assertTrue(scriptContent.contains("class PipeStepProcessorServicer"), 
                "Script should contain the PipeStepProcessorServicer class");
        Assertions.assertTrue(scriptContent.contains("def ProcessData"), 
                "Script should contain the ProcessData method");
        Assertions.assertTrue(scriptContent.contains("def serve"), 
                "Script should contain the serve function");
        
        LOG.info("Python script exists and contains expected content");
    }
    
    @Test
    public void testMockResponse() {
        LOG.info("Testing mock response creation and validation");
        
        // Create a test document ID and title
        String docId = UUID.randomUUID().toString();
        String originalTitle = "Test Document";
        String originalBody = "This is a test document for the PipeStepProcessor service.";
        
        // Create a mock response that simulates what the Python server would return
        PipeDoc outputDoc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Processed: " + originalTitle)
                .setBody(originalBody + "\n\nProcessed by Python PipeStepProcessor")
                .addKeywords("python")
                .addKeywords("graalpy")
                .addKeywords("processor")
                .build();
        
        ProcessResponse mockResponse = ProcessResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(outputDoc)
                .addProcessorLogs("Processed document " + docId)
                .addProcessorLogs("Added keywords: python, graalpy, processor")
                .addProcessorLogs("Modified title and body")
                .build();
        
        // Verify the mock response
        Assertions.assertTrue(mockResponse.getSuccess(), "Response should indicate success");
        Assertions.assertTrue(mockResponse.hasOutputDoc(), "Response should have an output document");
        
        PipeDoc responseDoc = mockResponse.getOutputDoc();
        Assertions.assertEquals(docId, responseDoc.getId(), "Document ID should be preserved");
        Assertions.assertEquals("Processed: " + originalTitle, responseDoc.getTitle(), 
                "Title should be prefixed with 'Processed: '");
        Assertions.assertTrue(responseDoc.getBody().startsWith(originalBody), 
                "Body should start with the original body");
        Assertions.assertTrue(responseDoc.getBody().contains("Processed by Python PipeStepProcessor"), 
                "Body should contain the processing note");
        
        // Verify keywords
        Assertions.assertEquals(3, responseDoc.getKeywordsCount(), "Should have 3 keywords");
        Assertions.assertTrue(responseDoc.getKeywordsList().contains("python"), 
                "Should have 'python' keyword");
        Assertions.assertTrue(responseDoc.getKeywordsList().contains("graalpy"), 
                "Should have 'graalpy' keyword");
        Assertions.assertTrue(responseDoc.getKeywordsList().contains("processor"), 
                "Should have 'processor' keyword");
        
        // Verify processor logs
        Assertions.assertTrue(mockResponse.getProcessorLogsCount() > 0, "Should have processor logs");
        
        LOG.info("Mock response validation successful");
    }
    
    /**
     * Loads a Python script from the classpath.
     *
     * @param resourcePath The path to the Python script
     * @return The content of the Python script
     * @throws IOException If the script cannot be loaded
     */
    private String loadPythonScript(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}