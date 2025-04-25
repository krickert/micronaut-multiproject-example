package com.krickert.search.model;

import com.google.protobuf.ListValue;
import com.google.protobuf.Timestamp;
import com.krickert.search.model.PipeDoc; // Example message
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtobufUtilsTest {

    // --- Timestamp Tests ---

    /**
     * Tests the {@link ProtobufUtils#now} method.
     * The method should return a {@link Timestamp} object representing the current time
     * as an Instant object, converted to seconds and nanoseconds.
     */
    @Test
    void testNowReturnsCurrentTimestamp() {
        // Act
        Timestamp timestamp = ProtobufUtils.now();

        // Assert
        Instant currentInstant = Instant.now();
        assertNotNull(timestamp);
        assertTrue(timestamp.getSeconds() <= currentInstant.getEpochSecond());
        assertTrue(timestamp.getNanos() >= 0 && timestamp.getNanos() < 1_000_000_000);
        // Allow for a small buffer due to execution time between now() calls
        assertTrue(timestamp.getSeconds() >= (currentInstant.getEpochSecond() - 2), "Timestamp seconds should be very close to current time");
    }

    @Test
    void nowIsNowNotThen() throws InterruptedException {
        Timestamp now1 = ProtobufUtils.now();
        Assertions.assertInstanceOf(Timestamp.class, now1);
        Thread.sleep(10); // Sleep briefly
        Timestamp now2 = ProtobufUtils.now();
        Thread.sleep(1001); // Sleep for over a second
        Timestamp now3 = ProtobufUtils.now();

        assertTrue(now2.getSeconds() >= now1.getSeconds()); // Could be same second
        // If same second, nanos should generally increase (though not guaranteed if clock resolution is low)
        if (now2.getSeconds() == now1.getSeconds()) {
             assertTrue(now2.getNanos() >= now1.getNanos());
        }

        assertTrue(now3.getSeconds() > now1.getSeconds(), "Timestamp after 1s sleep should have larger seconds value");
        assertTrue(now3.getSeconds() > now2.getSeconds());

    }

    @Test
    void stamp() {
        long time = System.currentTimeMillis() / 1000; // Current epoch seconds
        Timestamp stamp = ProtobufUtils.stamp(time);
        assertEquals(time, stamp.getSeconds());
        assertEquals(0, stamp.getNanos());

        Timestamp stampZero = ProtobufUtils.stamp(0);
        assertEquals(0, stampZero.getSeconds());
        assertEquals(0, stampZero.getNanos());

        Timestamp stampNegative = ProtobufUtils.stamp(-1234567890L);
        assertEquals(-1234567890L, stampNegative.getSeconds());
        assertEquals(0, stampNegative.getNanos());
    }

    // --- UUID Tests ---

    @Test
    void createKeyFromString() {
        String id1 = "test-id-1";
        String id2 = "test-id-2";
        String id1Again = "test-id-1";

        UUID key1 = ProtobufUtils.createKey(id1);
        UUID key2 = ProtobufUtils.createKey(id2);
        UUID key1Again = ProtobufUtils.createKey(id1Again);

        assertNotNull(key1);
        assertNotNull(key2);
        assertNotNull(key1Again);

        assertEquals(key1, key1Again); // Same input string -> same UUID
        assertNotEquals(key1, key2);   // Different input string -> different UUID

        // Test empty string
        UUID keyEmpty = ProtobufUtils.createKey("");
        assertNotNull(keyEmpty);

        // Test null string - should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            //noinspection DataFlowIssue
            ProtobufUtils.createKey((String) null);
        });
    }

    @Test
    void createKeyFromPipeDoc() {
        PipeDoc doc1 = PipeDoc.newBuilder().setId("doc-id-1").build();
        PipeDoc doc2 = PipeDoc.newBuilder().setId("doc-id-2").build();
        PipeDoc doc1Again = PipeDoc.newBuilder().setId("doc-id-1").setTitle("Different Title").build(); // ID is the same
        PipeDoc docEmptyId = PipeDoc.newBuilder().setId("").build();

        UUID key1 = ProtobufUtils.createKey(doc1);
        UUID key2 = ProtobufUtils.createKey(doc2);
        UUID key1Again = ProtobufUtils.createKey(doc1Again);
        UUID keyEmpty = ProtobufUtils.createKey(docEmptyId);


        assertNotNull(key1);
        assertNotNull(key2);
        assertNotNull(key1Again);
        assertNotNull(keyEmpty);

        assertEquals(key1, key1Again); // Same ID -> same UUID
        assertNotEquals(key1, key2);   // Different ID -> different UUID

        // Test null document - should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            //noinspection DataFlowIssue
            ProtobufUtils.createKey((PipeDoc) null);
        });

         // Test document with null ID - should throw NullPointerException when accessing id
         PipeDoc docNullId = PipeDoc.newBuilder().build(); // ID defaults to "", not null technically
         UUID keyFromDefaultEmptyId = ProtobufUtils.createKey(docNullId);
         assertEquals(keyEmpty, keyFromDefaultEmptyId);


    }

    // --- ListValue Test ---
    @Test
    void createListValueFromCollection() {
        Collection<String> strings = Arrays.asList("hello", "world", "", "another");
        ListValue listValue = ProtobufUtils.createListValueFromCollection(strings);

        assertNotNull(listValue);
        assertEquals(4, listValue.getValuesCount());
        assertEquals("hello", listValue.getValues(0).getStringValue());
        assertEquals("world", listValue.getValues(1).getStringValue());
        assertEquals("", listValue.getValues(2).getStringValue());
        assertEquals("another", listValue.getValues(3).getStringValue());

        // Test with empty collection
        ListValue emptyListValue = ProtobufUtils.createListValueFromCollection(Collections.emptyList());
        assertNotNull(emptyListValue);
        assertEquals(0, emptyListValue.getValuesCount());

         // Test with collection containing null - Protobuf Value doesn't allow null strings directly, check behavior
         // The current implementation would likely throw NPE on addValues(Value.newBuilder().setStringValue(null)...)
         Collection<String> listWithNull = Arrays.asList("a", null, "c");
         assertThrows(NullPointerException.class, ()-> ProtobufUtils.createListValueFromCollection(listWithNull), "setStringValue(null) should throw NPE");


        // Test with null collection - should throw NullPointerException
        assertThrows(NullPointerException.class, () -> {
            //noinspection DataFlowIssue
            ProtobufUtils.createListValueFromCollection(null);
        });
    }


    // --- Disk Saving Tests (Requires Temp Directory) ---

    @TempDir
    Path tempDir; // JUnit 5 Temp Directory injection

    @Test
    void saveProtobufToDisk_Single() throws IOException {
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("save-test-1")
                .setTitle("Save Me")
                .build();
        Path filePath = tempDir.resolve("single_doc.bin");
        String dst = filePath.toString();

        ProtobufUtils.saveProtobufToDisk(dst, doc);

        // Verify file exists
        assertTrue(Files.exists(filePath));
        assertTrue(Files.size(filePath) > 0);

        // Verify content can be parsed back
        try (FileInputStream fis = new FileInputStream(dst)) {
            PipeDoc readDoc = PipeDoc.parseFrom(fis);
            assertEquals(doc, readDoc);
        }
    }

    @Test
    void saveProtobufToDisk_Error() {
        PipeDoc doc = PipeDoc.newBuilder().setId("error-test").build();
        String invalidPath = tempDir.resolve("non_existent_dir/file.bin").toString(); // Invalid directory

        // Expect IOException or RuntimeException wrapping it
        assertThrows(IOException.class, () -> ProtobufUtils.saveProtobufToDisk(invalidPath, doc));
    }


    @Test
    void saveProtocoBufsToDisk_Multiple_DefaultPadding() throws IOException {
        PipeDoc doc1 = PipeDoc.newBuilder().setId("multi-1").build();
        PipeDoc doc2 = PipeDoc.newBuilder().setId("multi-2").build();
        List<PipeDoc> docs = Arrays.asList(doc1, doc2);
        String prefix = tempDir.resolve("multi_default_").toString();

        ProtobufUtils.saveProtocoBufsToDisk(prefix, docs);

        // Check files (padding based on size=2 -> 1 digit)
        Path path1 = tempDir.resolve("multi_default_0.bin");
        Path path2 = tempDir.resolve("multi_default_1.bin");

        assertTrue(Files.exists(path1));
        assertTrue(Files.exists(path2));
        assertEquals(doc1, PipeDoc.parseFrom(Files.readAllBytes(path1)));
        assertEquals(doc2, PipeDoc.parseFrom(Files.readAllBytes(path2)));
    }

    @Test
    void saveProtocoBufsToDisk_Multiple_CustomPadding() throws IOException {
        PipeDoc doc1 = PipeDoc.newBuilder().setId("multi-pad-1").build();
        PipeDoc doc2 = PipeDoc.newBuilder().setId("multi-pad-2").build();
        PipeDoc doc11 = PipeDoc.newBuilder().setId("multi-pad-11").build();
        List<PipeDoc> docs = Arrays.asList(doc1, doc2, doc11); // Size 3
        String prefix = tempDir.resolve("multi_pad_").toString();
        int leftPad = 3; // Custom padding

        ProtobufUtils.saveProtocoBufsToDisk(prefix, docs, leftPad);

        // Check files with custom padding
        Path path1 = tempDir.resolve("multi_pad_000.bin");
        Path path2 = tempDir.resolve("multi_pad_001.bin");
        Path path3 = tempDir.resolve("multi_pad_002.bin"); // Index 2 for 3rd item

        assertTrue(Files.exists(path1));
        assertTrue(Files.exists(path2));
        assertTrue(Files.exists(path3));
        assertEquals(doc1, PipeDoc.parseFrom(Files.readAllBytes(path1)));
        assertEquals(doc2, PipeDoc.parseFrom(Files.readAllBytes(path2)));
        assertEquals(doc11, PipeDoc.parseFrom(Files.readAllBytes(path3))); // Check 3rd item
    }

     @Test
    void saveProtocoBufsToDisk_EmptyList() throws IOException {
        List<PipeDoc> docs = Collections.emptyList();
        String prefix = tempDir.resolve("multi_empty_").toString();

        // Should not throw error and not create any files
        ProtobufUtils.saveProtocoBufsToDisk(prefix, docs);

        // Verify no files with the prefix were created
        @SuppressWarnings("resource")
        List<Path> files = Files.list(tempDir)
                                .filter(p -> p.getFileName().toString().startsWith("multi_empty_"))
                                .toList();
        assertTrue(files.isEmpty());
    }

}