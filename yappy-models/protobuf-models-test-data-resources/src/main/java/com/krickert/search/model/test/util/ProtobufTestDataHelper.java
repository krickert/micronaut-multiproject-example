package com.krickert.search.model.test.util;

import com.google.common.collect.Maps;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * TestDataHelper is a utility class that provides methods for retrieving protobuf test data.
 * It contains static fields and static methods for creating and retrieving test data.
 */
public class ProtobufTestDataHelper {
    
    private static final String PIPE_DOC_DIRECTORY = "/test-data/pipe-docs";
    private static final String PIPE_STREAM_DIRECTORY = "/test-data/pipe-streams";
    private static final String TIKA_PIPE_DOC_DIRECTORY = "/test-data/tika-pipe-docs";
    private static final String TIKA_PIPE_STREAM_DIRECTORY = "/test-data/tika-pipe-streams";
    private static final String CHUNKER_PIPE_DOC_DIRECTORY = "/test-data/chunker-pipe-docs";
    private static final String CHUNKER_PIPE_STREAM_DIRECTORY = "/test-data/chunker-pipe-streams";
    private static final String FILE_EXTENSION = ".bin";
    
    /**
     * Collection of PipeDoc objects.
     */
    private static final Collection<PipeDoc> pipeDocuments = createPipeDocuments();
    
    /**
     * Collection of PipeStream objects.
     */
    private static final Collection<PipeStream> pipeStreams = createPipeStreams();
    
    /**
     * Collection of PipeDoc objects from Tika parser.
     */
    private static final Collection<PipeDoc> tikaPipeDocuments = createTikaPipeDocuments();
    
    /**
     * Collection of PipeStream objects from Tika parser.
     */
    private static final Collection<PipeStream> tikaPipeStreams = createTikaPipeStreams();
    
    /**
     * Collection of PipeDoc objects from Chunker.
     */
    private static final Collection<PipeDoc> chunkerPipeDocuments = createChunkerPipeDocuments();
    
    /**
     * Collection of PipeStream objects from Chunker.
     */
    private static final Collection<PipeStream> chunkerPipeStreams = createChunkerPipeStreams();
    
    /**
     * Map of PipeDoc objects by ID.
     */
    private static final Map<String, PipeDoc> pipeDocumentsMap = createPipeDocumentMapById();
    
    /**
     * Map of PipeStream objects by stream ID.
     */
    private static final Map<String, PipeStream> pipeStreamsMap = createPipeStreamMapById();
    
    /**
     * Map of Tika PipeDoc objects by ID.
     */
    private static final Map<String, PipeDoc> tikaPipeDocumentsMap = createTikaPipeDocumentMapById();
    
    /**
     * Map of Tika PipeStream objects by stream ID.
     */
    private static final Map<String, PipeStream> tikaPipeStreamsMap = createTikaPipeStreamMapById();
    
    /**
     * Map of Chunker PipeDoc objects by ID.
     */
    private static final Map<String, PipeDoc> chunkerPipeDocumentsMap = createChunkerPipeDocumentMapById();
    
    /**
     * Map of Chunker PipeStream objects by stream ID.
     */
    private static final Map<String, PipeStream> chunkerPipeStreamsMap = createChunkerPipeStreamMapById();
    
    /**
     * Retrieves a collection of PipeDoc objects.
     *
     * @return A collection of PipeDoc objects.
     */
    public static Collection<PipeDoc> getPipeDocuments() {
        return pipeDocuments;
    }
    
    /**
     * Retrieves a collection of PipeStream objects.
     *
     * @return A collection of PipeStream objects.
     */
    public static Collection<PipeStream> getPipeStreams() {
        return pipeStreams;
    }
    
    /**
     * Retrieves a collection of PipeDoc objects from Tika parser.
     *
     * @return A collection of PipeDoc objects from Tika parser.
     */
    public static Collection<PipeDoc> getTikaPipeDocuments() {
        return tikaPipeDocuments;
    }
    
    /**
     * Retrieves a collection of PipeStream objects from Tika parser.
     *
     * @return A collection of PipeStream objects from Tika parser.
     */
    public static Collection<PipeStream> getTikaPipeStreams() {
        return tikaPipeStreams;
    }
    
    /**
     * Retrieves a collection of PipeDoc objects from Chunker.
     *
     * @return A collection of PipeDoc objects from Chunker.
     */
    public static Collection<PipeDoc> getChunkerPipeDocuments() {
        return chunkerPipeDocuments;
    }
    
    /**
     * Retrieves a collection of PipeStream objects from Chunker.
     *
     * @return A collection of PipeStream objects from Chunker.
     */
    public static Collection<PipeStream> getChunkerPipeStreams() {
        return chunkerPipeStreams;
    }
    
    /**
     * Retrieves a map of PipeDoc objects by ID.
     *
     * @return A map of PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getPipeDocumentsMap() {
        return pipeDocumentsMap;
    }
    
    /**
     * Retrieves a map of PipeStream objects by stream ID.
     *
     * @return A map of PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getPipeStreamsMap() {
        return pipeStreamsMap;
    }
    
    /**
     * Retrieves a map of Tika PipeDoc objects by ID.
     *
     * @return A map of Tika PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getTikaPipeDocumentsMap() {
        return tikaPipeDocumentsMap;
    }
    
    /**
     * Retrieves a map of Tika PipeStream objects by stream ID.
     *
     * @return A map of Tika PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getTikaPipeStreamsMap() {
        return tikaPipeStreamsMap;
    }
    
    /**
     * Retrieves a map of Chunker PipeDoc objects by ID.
     *
     * @return A map of Chunker PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getChunkerPipeDocumentsMap() {
        return chunkerPipeDocumentsMap;
    }
    
    /**
     * Retrieves a map of Chunker PipeStream objects by stream ID.
     *
     * @return A map of Chunker PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getChunkerPipeStreamsMap() {
        return chunkerPipeStreamsMap;
    }
    
    /**
     * Creates a map of PipeDoc objects by ID.
     *
     * @return A map of PipeDoc objects by ID.
     */
    private static Map<String, PipeDoc> createPipeDocumentMapById() {
        Collection<PipeDoc> docs = createPipeDocuments();
        Map<String, PipeDoc> returnVal = Maps.newHashMapWithExpectedSize(docs.size());
        docs.forEach((doc) -> returnVal.put(doc.getId(), doc));
        return returnVal;
    }
    
    /**
     * Creates a map of PipeStream objects by stream ID.
     *
     * @return A map of PipeStream objects by stream ID.
     */
    private static Map<String, PipeStream> createPipeStreamMapById() {
        Collection<PipeStream> streams = createPipeStreams();
        Map<String, PipeStream> returnVal = Maps.newHashMapWithExpectedSize(streams.size());
        streams.forEach((stream) -> returnVal.put(stream.getStreamId(), stream));
        return returnVal;
    }
    
    /**
     * Creates a map of Tika PipeDoc objects by ID.
     *
     * @return A map of Tika PipeDoc objects by ID.
     */
    private static Map<String, PipeDoc> createTikaPipeDocumentMapById() {
        Collection<PipeDoc> docs = createTikaPipeDocuments();
        Map<String, PipeDoc> returnVal = Maps.newHashMapWithExpectedSize(docs.size());
        docs.forEach((doc) -> returnVal.put(doc.getId(), doc));
        return returnVal;
    }
    
    /**
     * Creates a map of Tika PipeStream objects by stream ID.
     *
     * @return A map of Tika PipeStream objects by stream ID.
     */
    private static Map<String, PipeStream> createTikaPipeStreamMapById() {
        Collection<PipeStream> streams = createTikaPipeStreams();
        Map<String, PipeStream> returnVal = Maps.newHashMapWithExpectedSize(streams.size());
        streams.forEach((stream) -> returnVal.put(stream.getStreamId(), stream));
        return returnVal;
    }
    
    /**
     * Creates a map of Chunker PipeDoc objects by ID.
     *
     * @return A map of Chunker PipeDoc objects by ID.
     */
    private static Map<String, PipeDoc> createChunkerPipeDocumentMapById() {
        Collection<PipeDoc> docs = createChunkerPipeDocuments();
        Map<String, PipeDoc> returnVal = Maps.newHashMapWithExpectedSize(docs.size());
        docs.forEach((doc) -> returnVal.put(doc.getId(), doc));
        return returnVal;
    }
    
    /**
     * Creates a map of Chunker PipeStream objects by stream ID.
     *
     * @return A map of Chunker PipeStream objects by stream ID.
     */
    private static Map<String, PipeStream> createChunkerPipeStreamMapById() {
        Collection<PipeStream> streams = createChunkerPipeStreams();
        Map<String, PipeStream> returnVal = Maps.newHashMapWithExpectedSize(streams.size());
        streams.forEach((stream) -> returnVal.put(stream.getStreamId(), stream));
        return returnVal;
    }
    
    /**
     * Creates a collection of PipeDoc objects from the specified directory.
     *
     * @return A collection of PipeDoc objects.
     */
    private static Collection<PipeDoc> createPipeDocuments() {
        try {
            return loadPipeDocsFromDirectory(PIPE_DOC_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a collection of PipeStream objects from the specified directory.
     *
     * @return A collection of PipeStream objects.
     */
    private static Collection<PipeStream> createPipeStreams() {
        try {
            return loadPipeStreamsFromDirectory(PIPE_STREAM_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a collection of PipeDoc objects from the Tika parser directory.
     *
     * @return A collection of PipeDoc objects from the Tika parser.
     */
    private static Collection<PipeDoc> createTikaPipeDocuments() {
        try {
            return loadPipeDocsFromDirectory(TIKA_PIPE_DOC_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a collection of PipeStream objects from the Tika parser directory.
     *
     * @return A collection of PipeStream objects from the Tika parser.
     */
    private static Collection<PipeStream> createTikaPipeStreams() {
        try {
            return loadPipeStreamsFromDirectory(TIKA_PIPE_STREAM_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a collection of PipeDoc objects from the Chunker directory.
     *
     * @return A collection of PipeDoc objects from the Chunker.
     */
    private static Collection<PipeDoc> createChunkerPipeDocuments() {
        try {
            return loadPipeDocsFromDirectory(CHUNKER_PIPE_DOC_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Creates a collection of PipeStream objects from the Chunker directory.
     *
     * @return A collection of PipeStream objects from the Chunker.
     */
    private static Collection<PipeStream> createChunkerPipeStreams() {
        try {
            return loadPipeStreamsFromDirectory(CHUNKER_PIPE_STREAM_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
    
    /**
     * Loads PipeDoc objects from the specified directory.
     *
     * @param directory The directory to load PipeDoc objects from.
     * @return A collection of PipeDoc objects.
     * @throws IOException If an I/O error occurs.
     */
    private static Collection<PipeDoc> loadPipeDocsFromDirectory(String directory) throws IOException {
        Stream<Path> walk = getPathsFromDirectory(directory);
        List<PipeDoc> returnVal = new ArrayList<>();
        walk.forEach((file) -> {
            try {
                if (!file.getFileName().toString().equals(directory.substring(directory.lastIndexOf('/') + 1))) {
                    returnVal.add(ProtobufUtils.loadPipeDocFromDisk(file.toString()));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return returnVal;
    }
    
    /**
     * Loads PipeStream objects from the specified directory.
     *
     * @param directory The directory to load PipeStream objects from.
     * @return A collection of PipeStream objects.
     * @throws IOException If an I/O error occurs.
     */
    private static Collection<PipeStream> loadPipeStreamsFromDirectory(String directory) throws IOException {
        Stream<Path> walk = getPathsFromDirectory(directory);
        List<PipeStream> returnVal = new ArrayList<>();
        walk.forEach((file) -> {
            try {
                if (!file.getFileName().toString().equals(directory.substring(directory.lastIndexOf('/') + 1))) {
                    returnVal.add(ProtobufUtils.loadPipeStreamFromDisk(file.toString()));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return returnVal;
    }
    
    /**
     * Retrieves a stream of paths from the given directory.
     *
     * @param directory the directory from which to retrieve the paths
     * @return a stream of paths from the directory
     * @throws RuntimeException if any error occurs while retrieving the paths
     */
    private static Stream<Path> getPathsFromDirectory(String directory) {
        URI uri;
        try {
            uri = Objects.requireNonNull(ProtobufTestDataHelper.class.getResource(directory)).toURI();
        } catch (URISyntaxException | NullPointerException e) {
            throw new RuntimeException(e);
        }
        Path myPath;
        if (uri.getScheme().equals("jar")) {
            FileSystem fileSystem = null;
            try {
                fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                myPath = fileSystem.getPath(directory);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (FileSystemAlreadyExistsException e) {
                fileSystem = FileSystems.getFileSystem(uri);
                myPath = fileSystem.getPath(directory);
            }
        } else {
            myPath = Paths.get(uri);
        }
        Stream<Path> walk;
        try {
            walk = Files.walk(myPath, 1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return walk;
    }
}