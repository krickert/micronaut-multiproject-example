package com.krickert.search.model.util;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to verify that the ProtobufTestDataHelper is correctly loading the sample documents.
 */
public class ProtobufTestDataHelperTest {

    @Test
    void testSampleDocumentsAreLoaded() {
        // Get the sample documents
        Collection<PipeDoc> sampleDocs = ProtobufTestDataHelper.getSamplePipeDocuments();
        Collection<PipeStream> sampleStreams = ProtobufTestDataHelper.getSamplePipeStreams();
        
        // Verify that they are not empty
        assertFalse(sampleDocs.isEmpty(), "Sample PipeDoc collection should not be empty");
        assertFalse(sampleStreams.isEmpty(), "Sample PipeStream collection should not be empty");
        
        // Verify that they have the expected number of documents
        assertEquals(99, sampleDocs.size(), "Sample PipeDoc collection should have 99 documents");
        assertEquals(99, sampleStreams.size(), "Sample PipeStream collection should have 99 documents");
        
        // Verify that the documents have the expected fields
        for (PipeDoc doc : sampleDocs) {
            assertNotNull(doc.getId(), "Document ID should not be null");
            assertFalse(doc.getId().isEmpty(), "Document ID should not be empty");
            
            // Most documents should have a body
            if (doc.hasBody()) {
                assertFalse(doc.getBody().isEmpty(), "Document body should not be empty");
            }
        }
        
        // Verify that the streams have the expected fields
        for (PipeStream stream : sampleStreams) {
            assertNotNull(stream.getStreamId(), "Stream ID should not be null");
            assertFalse(stream.getStreamId().isEmpty(), "Stream ID should not be empty");
            
            // Each stream should have a document
            assertTrue(stream.hasDocument(), "Stream should have a document");
            assertNotNull(stream.getDocument(), "Stream document should not be null");
        }
        
        // Verify that the ordered lists work
        assertEquals(99, ProtobufTestDataHelper.getOrderedSamplePipeDocuments().size(), 
                "Ordered sample PipeDoc list should have 99 documents");
        assertEquals(99, ProtobufTestDataHelper.getOrderedSamplePipeStreams().size(), 
                "Ordered sample PipeStream list should have 99 documents");
        
        // Verify that the index-based methods work
        assertNotNull(ProtobufTestDataHelper.getSamplePipeDocByIndex(0), 
                "Should be able to get sample PipeDoc by index 0");
        assertNotNull(ProtobufTestDataHelper.getSamplePipeStreamByIndex(0), 
                "Should be able to get sample PipeStream by index 0");
        
        // Verify that the maps work
        assertEquals(99, ProtobufTestDataHelper.getSamplePipeDocumentsMap().size(), 
                "Sample PipeDoc map should have 99 entries");
        assertEquals(99, ProtobufTestDataHelper.getSamplePipeStreamsMap().size(), 
                "Sample PipeStream map should have 99 entries");
    }
}