package com.krickert.search.model.util;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.krickert.search.model.PipeDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ProcessingBuffer implementations.
 * Uses jimfs for in-memory file system testing.
 */
public class ProcessingBufferTest {

    private FileSystem fileSystem;
    private Path rootPath;

    @BeforeEach
    public void setUp() {
        // Create an in-memory file system for testing
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        rootPath = fileSystem.getPath("/test");
        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            fail("Failed to create test directory: " + e.getMessage());
        }
    }

    @AfterEach
    public void tearDown() throws IOException {
        fileSystem.close();
    }

    @Test
    public void testProcessingBufferImpl_SaveToDisk() throws IOException {
        // Create a buffer with capacity 3
        int capacity = 3;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class, fileSystem);

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
        buffer.saveToDisk(rootPath, prefix, precision);

        // Verify files were created
        List<Path> files = Files.list(rootPath)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());

        assertEquals(capacity, files.size(), "Should have created " + capacity + " files");

        // Verify file names
        for (int i = 0; i < capacity; i++) {
            String expectedFileName = String.format("%s%02d.bin", prefix, i);
            Path expectedPath = rootPath.resolve(expectedFileName);
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
        buffer.saveToDisk(rootPath, "test-doc", 2);

        // Verify no files were created
        List<Path> files = Files.list(rootPath)
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());

        assertEquals(0, files.size(), "NoOp buffer should not create any files");
    }

    @Test
    public void testProcessingBufferImpl_DefaultParameters() throws IOException {
        // Create a buffer with capacity 2
        int capacity = 2;
        ProcessingBuffer<PipeDoc> buffer = new ProcessingBufferImpl<>(capacity, PipeDoc.class, fileSystem);

        // Add some test messages
        for (int i = 0; i < capacity; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + i)
                    .setTitle("Test Document " + i)
                    .setBody("This is test document " + i)
                    .build();
            buffer.add(doc);
        }

        // Create a directory for the default save location
        Path defaultPath = fileSystem.getPath("./");
        Files.createDirectories(defaultPath);

        // Save to disk with default parameters
        buffer.saveToDisk();

        // Verify files were created with default naming
        List<Path> files = Files.list(defaultPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith("protobuf"))
                .collect(Collectors.toList());

        assertEquals(capacity, files.size(), "Should have created " + capacity + " files with default parameters");
    }
}
