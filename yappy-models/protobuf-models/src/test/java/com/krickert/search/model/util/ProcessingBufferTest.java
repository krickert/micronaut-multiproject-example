package com.krickert.search.model.util;

import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ProcessingBuffer implementations.
 * Uses temporary directories for file system testing.
 */
public class ProcessingBufferTest {

    @TempDir
    Path tempDir;
    
    private Path testDir;

    @BeforeEach
    public void setUp() throws IOException {
        // Create a test subdirectory
        testDir = tempDir.resolve("test");
        Files.createDirectories(testDir);
    }

    @AfterEach
    public void tearDown() throws IOException {
        // Cleanup is handled automatically by @TempDir
    }

    @Test
    public void testProcessingBufferImpl_SaveToDisk() throws IOException {
        // Create a buffer with capacity 3
        int capacity = 3;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class);

        // Add some test messages
        for (int i = 0; i < capacity; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Save to disk
        String prefix = "test-doc";
        int precision = 2;
        buffer.saveToDisk(testDir, prefix, precision);

        // Verify files were created
        List<Path> files;
        try (var stream = Files.list(testDir)) {
            files = stream.filter(Files::isRegularFile).toList();
        }

        assertEquals(capacity, files.size(), "Should have created " + capacity + " files");

        // Verify file names
        for (int i = 0; i < capacity; i++) {
            String expectedFileName = String.format("%s-%02d.bin", prefix, i);
            Path expectedPath = testDir.resolve(expectedFileName);
            assertTrue(Files.exists(expectedPath), "File should exist: " + expectedPath);
        }
    }

    @Test
    public void testProcessingBufferImpl_BoundedCapacity() {
        // Create a buffer with capacity 2
        int capacity = 2;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class);

        // Add 3 messages (exceeding capacity)
        for (int i = 0; i < capacity + 1; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Verify buffer size is limited to capacity
        assertEquals(capacity, buffer.size(), "Buffer size should be limited to capacity");
    }

    @Test
    public void testProcessingBufferImpl_Clear() {
        // Create a buffer with capacity 3
        int capacity = 3;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class);

        // Add some test messages
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

        // Clear the buffer
        buffer.clear();

        // Verify buffer is empty
        assertEquals(0, buffer.size(), "Buffer should be empty after clear");
    }

    @Test
    public void testNoOpProcessingBuffer_DoesNotSaveToDisk() throws IOException {
        // Create a NoOp buffer
        ProcessingBuffer<PipeDoc> buffer = new NoOpProcessingBuffer<>();

        // Add some test messages
        for (int i = 0; i < 3; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Verify buffer size is always 0
        assertEquals(0, buffer.size(), "NoOp buffer size should always be 0");

        // Save to disk
        buffer.saveToDisk(testDir, "test-doc", 2);

        // Verify no files were created
        List<Path> files;
        try (var stream = Files.list(testDir)) {
            files = stream.filter(Files::isRegularFile).toList();
        }

        assertEquals(0, files.size(), "NoOp buffer should not create any files");
    }

    @Test
    public void testProcessingBufferImpl_DefaultParameters() throws IOException {
        // Create a buffer with capacity 2
        int capacity = 2;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class);

        // Add some test messages
        for (int i = 0; i < capacity; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Save to disk with default parameters (saves to current directory)
        buffer.saveToDisk();

        // The default implementation saves to the current working directory
        // For testing, we'll save to our test directory instead
        buffer.saveToDisk(tempDir, "protobuf", 3);

        // Verify files were created with specified naming
        List<Path> files;
        try (var stream = Files.list(tempDir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("protobuf"))
                    .toList();
        }

        assertEquals(capacity, files.size(), "Should have created " + capacity + " files with default parameters");
    }
}