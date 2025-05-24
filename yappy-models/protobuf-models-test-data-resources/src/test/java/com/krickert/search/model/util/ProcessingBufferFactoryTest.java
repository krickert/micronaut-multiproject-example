package com.krickert.search.model.util;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ProcessingBufferFactory.
 */
public class ProcessingBufferFactoryTest {

    private FileSystem fileSystem;

    @BeforeEach
    public void setUp() {
        // Create an in-memory file system for testing
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
    }

    @AfterEach
    public void tearDown() throws IOException {
        fileSystem.close();
    }

    @Test
    public void testCreateBuffer_Enabled() {
        // Create a buffer with enabled=true
        ProcessingBuffer<PipeDoc> buffer = ProcessingBufferFactory.createBuffer(true, PipeDoc.class);

        // Verify it's a ProcessingBufferImpl
        assertTrue(buffer instanceof ProcessingBufferImpl, "Buffer should be a ProcessingBufferImpl when enabled=true");

        // Add a test message
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setTitle("Test Document")
                .setBody("This is a test document")
                .build();
        buffer.add(doc);

        // Verify buffer size
        assertEquals(1, buffer.size(), "Buffer should contain 1 message");
    }

    @Test
    public void testCreateBuffer_Disabled() {
        // Create a buffer with enabled=false
        ProcessingBuffer<PipeDoc> buffer = ProcessingBufferFactory.createBuffer(false, PipeDoc.class);

        // Verify it's a NoOpProcessingBuffer
        assertTrue(buffer instanceof NoOpProcessingBuffer, "Buffer should be a NoOpProcessingBuffer when enabled=false");

        // Add a test message
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setTitle("Test Document")
                .setBody("This is a test document")
                .build();
        buffer.add(doc);

        // Verify buffer size is 0 (NoOp implementation)
        assertEquals(0, buffer.size(), "NoOp buffer size should always be 0");
    }

    @Test
    public void testCreateBuffer_WithFileSystem() {
        // Create a buffer with enabled=true and a custom file system
        ProcessingBuffer<PipeDoc> buffer = ProcessingBufferFactory.createBuffer(true, PipeDoc.class, fileSystem);

        // Verify it's a ProcessingBufferImpl
        assertTrue(buffer instanceof ProcessingBufferImpl, "Buffer should be a ProcessingBufferImpl when enabled=true");

        // Add a test message
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc")
                .setTitle("Test Document")
                .setBody("This is a test document")
                .build();
        buffer.add(doc);

        // Verify buffer size
        assertEquals(1, buffer.size(), "Buffer should contain 1 message");
    }

    @Test
    public void testCreateBuffer_WithCapacityAndFileSystem() {
        // Create a buffer with enabled=true, capacity=3, and a custom file system
        int capacity = 3;
        ProcessingBuffer<PipeDoc> buffer = ProcessingBufferFactory.createBuffer(true, capacity, PipeDoc.class, fileSystem);

        // Verify it's a ProcessingBufferImpl
        assertTrue(buffer instanceof ProcessingBufferImpl, "Buffer should be a ProcessingBufferImpl when enabled=true");

        // Add messages up to capacity
        for (int i = 0; i < capacity; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Verify buffer size
        assertEquals(capacity, buffer.size(), "Buffer should contain " + capacity + " messages");
    }

    @Test
    public void testCreateBuffer_WithCapacity() {
        // Create a buffer with enabled=true and capacity=3
        int capacity = 3;
        ProcessingBuffer<PipeDoc> buffer = ProcessingBufferFactory.createBuffer(true, capacity, PipeDoc.class);

        // Verify it's a ProcessingBufferImpl
        assertTrue(buffer instanceof ProcessingBufferImpl, "Buffer should be a ProcessingBufferImpl when enabled=true");

        // Add messages up to capacity
        for (int i = 0; i < capacity; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Verify buffer size
        assertEquals(capacity, buffer.size(), "Buffer should contain " + capacity + " messages");

        // Add one more message (exceeding capacity)
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-extra")
                .setTitle("Test Document Extra")
                .setBody("This is an extra test document")
                .build();
        buffer.add(doc);

        // Verify buffer size is still limited to capacity
        assertEquals(capacity, buffer.size(), "Buffer size should be limited to capacity");
    }
}
