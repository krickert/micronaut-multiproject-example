package com.krickert.search.model.util;

import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ProtobufTestDataHelper
 */
class ProtobufTestDataHelperTest {
    
    private static final Logger log = LoggerFactory.getLogger(ProtobufTestDataHelperTest.class);
    
    @Test
    void testLoadSamplePipeStreams() {
        // Test loading sample PipeStreams from resources
        Collection<PipeStream> pipeStreams = ProtobufTestDataHelper.loadSamplePipeStreams();
        
        assertNotNull(pipeStreams, "PipeStreams collection should not be null");
        assertFalse(pipeStreams.isEmpty(), "PipeStreams collection should not be empty");
        
        log.info("Loaded {} sample PipeStreams", pipeStreams.size());
        
        // Verify each PipeStream has required fields
        for (PipeStream pipeStream : pipeStreams) {
            assertNotNull(pipeStream.getStreamId(), "Stream ID should not be null");
            assertTrue(pipeStream.getStreamId().startsWith("sample-stream-"), 
                "Stream ID should start with 'sample-stream-'");
            
            assertNotNull(pipeStream.getDocument(), "Document should not be null");
            assertNotNull(pipeStream.getDocument().getId(), "Document ID should not be null");
            assertTrue(pipeStream.getDocument().getId().startsWith("sample-doc-"), 
                "Document ID should start with 'sample-doc-'");
            
            assertEquals("sample-pipeline", pipeStream.getCurrentPipelineName(), 
                "Pipeline name should be 'sample-pipeline'");
            assertEquals("initial", pipeStream.getTargetStepName(), 
                "Target step name should be 'initial'");
            assertEquals(0, pipeStream.getCurrentHopNumber(), 
                "Current hop number should be 0");
            
            // Check context params
            assertTrue(pipeStream.getContextParamsMap().containsKey("filename"), 
                "Context params should contain 'filename'");
            assertEquals("sample-documents", pipeStream.getContextParamsMap().get("source"), 
                "Source should be 'sample-documents'");
            
            // Check document blob
            if (pipeStream.getDocument().hasBlob()) {
                assertNotNull(pipeStream.getDocument().getBlob().getData(), 
                    "Blob data should not be null");
                assertFalse(pipeStream.getDocument().getBlob().getData().isEmpty(), 
                    "Blob data should not be empty");
                
                String filename = pipeStream.getDocument().getBlob().getFilename();
                assertNotNull(filename, "Blob filename should not be null");
                
                // Log some details about the loaded file
                log.debug("Loaded file: {} with {} bytes", 
                    filename, 
                    pipeStream.getDocument().getBlob().getData().size());
            }
        }
    }
    
    @Test
    void testLoadSamplePipeStreamsFromJar(@TempDir Path tempDir) throws IOException {
        // This test simulates loading from a JAR by creating a test JAR structure
        // For now, we'll just verify that the existing method works with classpath resources
        
        // The actual JAR loading is tested implicitly when the test runs from compiled JAR
        Collection<PipeStream> pipeStreams = ProtobufTestDataHelper.loadSamplePipeStreams();
        assertNotNull(pipeStreams);
        
        // Additional verification that we're loading known files
        boolean foundPdf = false;
        boolean foundDocx = false;
        boolean foundTxt = false;
        
        for (PipeStream stream : pipeStreams) {
            String filename = stream.getContextParamsMap().get("filename");
            if (filename != null) {
                if (filename.toLowerCase().endsWith(".pdf")) foundPdf = true;
                if (filename.toLowerCase().endsWith(".docx")) foundDocx = true;
                if (filename.toLowerCase().endsWith(".txt")) foundTxt = true;
            }
        }
        
        assertTrue(foundPdf, "Should have found at least one PDF file");
        assertTrue(foundDocx, "Should have found at least one DOCX file");
        assertTrue(foundTxt, "Should have found at least one TXT file");
    }
    
    @Test
    void testMimeTypeDetection() {
        // Test that MIME types are correctly set for known file types
        Collection<PipeStream> pipeStreams = ProtobufTestDataHelper.loadSamplePipeStreams();
        
        for (PipeStream stream : pipeStreams) {
            if (stream.getDocument().hasBlob()) {
                String filename = stream.getDocument().getBlob().getFilename();
                String mimeType = stream.getDocument().getSourceMimeType();
                
                // Check some common file types
                if (filename.toLowerCase().endsWith(".pdf")) {
                    assertEquals("application/pdf", mimeType, 
                        "PDF files should have correct MIME type");
                } else if (filename.toLowerCase().endsWith(".txt")) {
                    assertEquals("text/plain", mimeType, 
                        "TXT files should have correct MIME type");
                } else if (filename.toLowerCase().endsWith(".png")) {
                    assertEquals("image/png", mimeType, 
                        "PNG files should have correct MIME type");
                } else if (filename.toLowerCase().endsWith(".jpg")) {
                    assertEquals("image/jpeg", mimeType, 
                        "JPG files should have correct MIME type");
                }
            }
        }
    }
    
    @Test
    void testExistingMethods() {
        // Test that existing methods still work
        assertNotNull(ProtobufTestDataHelper.getPipeDocuments());
        assertNotNull(ProtobufTestDataHelper.getPipeStreams());
        assertNotNull(ProtobufTestDataHelper.getTikaPipeDocuments());
        assertNotNull(ProtobufTestDataHelper.getTikaPipeStreams());
        assertNotNull(ProtobufTestDataHelper.getChunkerPipeDocuments());
        assertNotNull(ProtobufTestDataHelper.getChunkerPipeStreams());
        
        assertNotNull(ProtobufTestDataHelper.getPipeDocumentsMap());
        assertNotNull(ProtobufTestDataHelper.getPipeStreamsMap());
        assertNotNull(ProtobufTestDataHelper.getTikaPipeDocumentsMap());
        assertNotNull(ProtobufTestDataHelper.getTikaPipeStreamsMap());
        assertNotNull(ProtobufTestDataHelper.getChunkerPipeDocumentsMap());
        assertNotNull(ProtobufTestDataHelper.getChunkerPipeStreamsMap());
    }
}