package com.krickert.search.model;

import com.google.protobuf.ListValue;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Utility class for working with protobuf messages.
 */
public class ProtobufUtils {

    /**
     * Returns the current timestamp as a Timestamp object.
     *
     * @return the current timestamp
     */
    public static Timestamp now() {
        Instant time = Instant.now();
        return Timestamp.newBuilder().setSeconds(time.getEpochSecond())
                .setNanos(time.getNano()).build();
    }

    /**
     * Creates a Timestamp object from the given epoch seconds.
     *
     * @param epochSeconds the number of seconds since January 1, 1970
     * @return a Timestamp object representing the given epoch seconds
     */
    public static Timestamp stamp(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds)
                .setNanos(0).build();
    }

    /**
     * Saves a Protobuf message to disk.
     *
     * @param dst  The destination file path.
     * @param item The Protobuf message to be saved.
     * @throws IOException If an I/O error occurs while writing to the file.
     */
    public static <T extends Message> void saveProtobufToDisk(String dst, T item) throws IOException {
        Path path = Paths.get(dst);
        try (OutputStream os = Files.newOutputStream(path)) {
            item.writeTo(os);
        }
    }


    /**
     * Saves a collection of Protocol Buffer messages to disk.
     *
     * @param dstPrefix The prefix of the destination file path.
     * @param items     The collection of Protocol Buffer messages to be saved.
     * @param <T>       The type of Protocol Buffer message.
     * @throws RuntimeException If an I/O error occurs while saving the messages.
     */
    public static <T extends Message> void saveProtocoBufsToDisk(String dstPrefix, Collection<T> items) {
        int leftPad = (String.valueOf(items.size())).length();
        saveProtocoBufsToDisk(dstPrefix, items, leftPad);
    }

    /**
     * Saves a collection of Protocol Buffer messages to disk.
     *
     * @param dstPrefix The prefix of the destination file path.
     * @param items     The collection of Protocol Buffer messages to be saved.
     * @param leftPad   The number of digits used for left padding the index of each saved message in the file name.
     * @param <T>       The type of Protocol Buffer message.
     * @throws RuntimeException If an I/O error occurs while saving the messages.
     */
    public static <T extends Message> void saveProtocoBufsToDisk(String dstPrefix, Collection<T> items, int leftPad) {
        AtomicInteger i = new AtomicInteger();
        items.forEach((item) -> {
            try {
                saveProtobufToDisk(dstPrefix + StringUtils.leftPad(String.valueOf(i.getAndIncrement()), leftPad, "0") + ".bin", item);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Creates a UUID key from a given string identifier.
     *
     * @param id The string identifier.
     * @return The UUID key.
     */
    public static UUID createKey(String id) {
        return UUID.nameUUIDFromBytes(id.getBytes());
    }

    /**
     * Creates a UUID key from a given PipeDocument object.
     *
     * @param pipeDocument The PipeDocument object to generate the key from.
     * @return The generated UUID key.
     */
    public static UUID createKey(PipeDoc pipeDocument) {
        return createKey(pipeDocument.getId());
    }

    /**
     * Creates a UUID key from a given PipeDocument object.
     *
     * @param pipeDocument The PipeDocument object to generate the key from.
     * @return The generated UUID key.
     */
    public static UUID createKey(PipeStream pipeDocument) {
        return createKey(pipeDocument.getStreamId());
    }


    /**
     * Creates a ListValue object from a collection of strings.
     *
     * @param collectionToConvert The collection of strings to be converted.
     * @return A ListValue object containing the converted strings.
     */
    public static ListValue createListValueFromCollection(Collection<String> collectionToConvert) {
        ListValue.Builder builder = ListValue.newBuilder();
        collectionToConvert.forEach((obj) -> builder.addValues(Value.newBuilder().setStringValue(obj).build()));
        return builder.build();
    }

    /**
     * Loads a Protocol Buffer message from disk.
     *
     * @param filePath The path to the file containing the Protocol Buffer message.
     * @param parser   The parser to use for parsing the message.
     * @param <T>      The type of Protocol Buffer message.
     * @return The loaded Protocol Buffer message.
     * @throws IOException If an I/O error occurs while reading the file.
     */
    public static <T extends Message> T loadProtobufFromDisk(String filePath, Parser<T> parser) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            return parser.parseFrom(fis);
        }
    }

    /**
     * Loads a PipeDoc from disk.
     *
     * @param filePath The path to the file containing the PipeDoc.
     * @return The loaded PipeDoc.
     * @throws IOException If an I/O error occurs while reading the file.
     */
    public static PipeDoc loadPipeDocFromDisk(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            return PipeDoc.parseFrom(fis);
        }
    }

    /**
     * Loads a PipeStream from disk.
     *
     * @param filePath The path to the file containing the PipeStream.
     * @return The loaded PipeStream.
     * @throws IOException If an I/O error occurs while reading the file.
     */
    public static PipeStream loadPipeStreamFromDisk(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            return PipeStream.parseFrom(fis);
        }
    }

    /**
     * Loads all Protocol Buffer messages of a specific type from a directory.
     *
     * @param directoryPath The path to the directory containing the Protocol Buffer messages.
     * @param parser        The parser to use for parsing the messages.
     * @param fileExtension The file extension to filter by (e.g., ".bin").
     * @param <T>           The type of Protocol Buffer message.
     * @return A list of loaded Protocol Buffer messages.
     * @throws IOException If an I/O error occurs while reading the files.
     */
    public static <T extends Message> List<T> loadProtobufFilesFromDirectory(String directoryPath, Parser<T> parser, String fileExtension) throws IOException {
        Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
        }

        List<T> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir, 1)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(fileExtension))
                .forEach(path -> {
                    try {
                        result.add(loadProtobufFromDisk(path.toString(), parser));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load protobuf file: " + path, e);
                    }
                });
        }
        return result;
    }

    /**
     * Loads all PipeDoc messages from a directory.
     *
     * @param directoryPath The path to the directory containing the PipeDoc messages.
     * @param fileExtension The file extension to filter by (e.g., ".bin").
     * @return A list of loaded PipeDoc messages.
     * @throws IOException If an I/O error occurs while reading the files.
     */
    public static List<PipeDoc> loadPipeDocsFromDirectory(String directoryPath, String fileExtension) throws IOException {
        Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
        }

        List<PipeDoc> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir, 1)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(fileExtension))
                .forEach(path -> {
                    try {
                        result.add(loadPipeDocFromDisk(path.toString()));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load PipeDoc file: " + path, e);
                    }
                });
        }
        return result;
    }

    /**
     * Loads all PipeStream messages from a directory.
     *
     * @param directoryPath The path to the directory containing the PipeStream messages.
     * @param fileExtension The file extension to filter by (e.g., ".bin").
     * @return A list of loaded PipeStream messages.
     * @throws IOException If an I/O error occurs while reading the files.
     */
    public static List<PipeStream> loadPipeStreamsFromDirectory(String directoryPath, String fileExtension) throws IOException {
        Path dir = Paths.get(directoryPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
        }

        List<PipeStream> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir, 1)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(fileExtension))
                .forEach(path -> {
                    try {
                        result.add(loadPipeStreamFromDisk(path.toString()));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to load PipeStream file: " + path, e);
                    }
                });
        }
        return result;
    }
}
