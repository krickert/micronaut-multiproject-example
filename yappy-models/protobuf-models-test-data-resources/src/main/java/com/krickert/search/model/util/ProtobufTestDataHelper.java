package com.krickert.search.model.util;

import com.google.common.collect.Maps;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TestDataHelper is a utility class that provides methods for retrieving protobuf test data.
 * It contains static fields and static methods for creating and retrieving test data.
 * This implementation uses lazy loading to improve performance and reduce memory usage.
 */
public class ProtobufTestDataHelper {

    private static final String PIPE_DOC_DIRECTORY = "/test-data/pipe-docs";
    private static final String PIPE_STREAM_DIRECTORY = "/test-data/pipe-streams";
    private static final String TIKA_PIPE_DOC_DIRECTORY = "/test-data/tika-pipe-docs";
    private static final String TIKA_PIPE_STREAM_DIRECTORY = "/test-data/tika-pipe-streams";
    private static final String CHUNKER_PIPE_DOC_DIRECTORY = "/test-data/chunker-pipe-docs";
    private static final String CHUNKER_PIPE_STREAM_DIRECTORY = "/test-data/chunker-pipe-streams";
    private static final String SAMPLE_PIPE_DOC_DIRECTORY = "/test-data/sample-documents/pipe-docs";
    private static final String SAMPLE_PIPE_STREAM_DIRECTORY = "/test-data/sample-documents/pipe-streams";
    private static final String PIPELINE_GENERATED_DIRECTORY = "/test-data/pipeline-generated";
    private static final String FILE_EXTENSION = ".bin";

    // Lazy-loaded collections and maps
    private static volatile Collection<PipeDoc> pipeDocuments;
    private static volatile Collection<PipeStream> pipeStreams;
    private static volatile Collection<PipeDoc> tikaPipeDocuments;
    private static volatile Collection<PipeStream> tikaPipeStreams;
    private static volatile Collection<PipeDoc> chunkerPipeDocuments;
    private static volatile Collection<PipeStream> chunkerPipeStreams;
    private static volatile Collection<PipeDoc> samplePipeDocuments;
    private static volatile Collection<PipeStream> samplePipeStreams;
    
    private static volatile Map<String, PipeDoc> pipeDocumentsMap;
    private static volatile Map<String, PipeStream> pipeStreamsMap;
    private static volatile Map<String, PipeDoc> tikaPipeDocumentsMap;
    private static volatile Map<String, PipeStream> tikaPipeStreamsMap;
    private static volatile Map<String, PipeDoc> chunkerPipeDocumentsMap;
    private static volatile Map<String, PipeStream> chunkerPipeStreamsMap;
    private static volatile Map<String, PipeDoc> samplePipeDocumentsMap;
    private static volatile Map<String, PipeStream> samplePipeStreamsMap;
    
    // Lazy-loaded ordered lists for sample documents
    private static volatile List<PipeDoc> orderedSamplePipeDocs;
    private static volatile List<PipeStream> orderedSamplePipeStreams;
    
    // Pipeline generated data
    private static volatile Map<String, Collection<PipeDoc>> pipelineGeneratedDocs;

    /**
     * Retrieves a collection of PipeDoc objects.
     *
     * @return A collection of PipeDoc objects.
     */
    public static Collection<PipeDoc> getPipeDocuments() {
        if (pipeDocuments == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipeDocuments == null) {
                    pipeDocuments = createPipeDocuments();
                }
            }
        }
        return pipeDocuments;
    }

    /**
     * Retrieves a collection of PipeStream objects.
     *
     * @return A collection of PipeStream objects.
     */
    public static Collection<PipeStream> getPipeStreams() {
        if (pipeStreams == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipeStreams == null) {
                    pipeStreams = createPipeStreams();
                }
            }
        }
        return pipeStreams;
    }

    /**
     * Retrieves a collection of PipeDoc objects from Tika parser.
     *
     * @return A collection of PipeDoc objects from Tika parser.
     */
    public static Collection<PipeDoc> getTikaPipeDocuments() {
        if (tikaPipeDocuments == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (tikaPipeDocuments == null) {
                    tikaPipeDocuments = createTikaPipeDocuments();
                }
            }
        }
        return tikaPipeDocuments;
    }

    /**
     * Retrieves a collection of PipeStream objects from Tika parser.
     *
     * @return A collection of PipeStream objects from Tika parser.
     */
    public static Collection<PipeStream> getTikaPipeStreams() {
        if (tikaPipeStreams == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (tikaPipeStreams == null) {
                    tikaPipeStreams = createTikaPipeStreams();
                }
            }
        }
        return tikaPipeStreams;
    }

    /**
     * Retrieves a collection of PipeDoc objects from Chunker.
     *
     * @return A collection of PipeDoc objects from Chunker.
     */
    public static Collection<PipeDoc> getChunkerPipeDocuments() {
        if (chunkerPipeDocuments == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (chunkerPipeDocuments == null) {
                    chunkerPipeDocuments = createChunkerPipeDocuments();
                }
            }
        }
        return chunkerPipeDocuments;
    }

    /**
     * Retrieves a collection of PipeStream objects from Chunker.
     *
     * @return A collection of PipeStream objects from Chunker.
     */
    public static Collection<PipeStream> getChunkerPipeStreams() {
        if (chunkerPipeStreams == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (chunkerPipeStreams == null) {
                    chunkerPipeStreams = createChunkerPipeStreams();
                }
            }
        }
        return chunkerPipeStreams;
    }

    /**
     * Retrieves a collection of PipeDoc objects from Sample Documents.
     *
     * @return A collection of PipeDoc objects from Sample Documents.
     */
    public static Collection<PipeDoc> getSamplePipeDocuments() {
        if (samplePipeDocuments == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (samplePipeDocuments == null) {
                    samplePipeDocuments = createSamplePipeDocuments();
                }
            }
        }
        return samplePipeDocuments;
    }

    /**
     * Retrieves a collection of PipeStream objects from Sample Documents.
     *
     * @return A collection of PipeStream objects from Sample Documents.
     */
    public static Collection<PipeStream> getSamplePipeStreams() {
        if (samplePipeStreams == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (samplePipeStreams == null) {
                    samplePipeStreams = createSamplePipeStreams();
                }
            }
        }
        return samplePipeStreams;
    }

    /**
     * Retrieves a map of PipeDoc objects by ID.
     *
     * @return A map of PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getPipeDocumentsMap() {
        if (pipeDocumentsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipeDocumentsMap == null) {
                    pipeDocumentsMap = createPipeDocumentMapById();
                }
            }
        }
        return pipeDocumentsMap;
    }

    /**
     * Retrieves a map of PipeStream objects by stream ID.
     *
     * @return A map of PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getPipeStreamsMap() {
        if (pipeStreamsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipeStreamsMap == null) {
                    pipeStreamsMap = createPipeStreamMapById();
                }
            }
        }
        return pipeStreamsMap;
    }

    /**
     * Retrieves a map of Tika PipeDoc objects by ID.
     *
     * @return A map of Tika PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getTikaPipeDocumentsMap() {
        if (tikaPipeDocumentsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (tikaPipeDocumentsMap == null) {
                    tikaPipeDocumentsMap = createTikaPipeDocumentMapById();
                }
            }
        }
        return tikaPipeDocumentsMap;
    }

    /**
     * Retrieves a map of Tika PipeStream objects by stream ID.
     *
     * @return A map of Tika PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getTikaPipeStreamsMap() {
        if (tikaPipeStreamsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (tikaPipeStreamsMap == null) {
                    tikaPipeStreamsMap = createTikaPipeStreamMapById();
                }
            }
        }
        return tikaPipeStreamsMap;
    }

    /**
     * Retrieves a map of Chunker PipeDoc objects by ID.
     *
     * @return A map of Chunker PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getChunkerPipeDocumentsMap() {
        if (chunkerPipeDocumentsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (chunkerPipeDocumentsMap == null) {
                    chunkerPipeDocumentsMap = createChunkerPipeDocumentMapById();
                }
            }
        }
        return chunkerPipeDocumentsMap;
    }

    /**
     * Retrieves a map of Chunker PipeStream objects by stream ID.
     *
     * @return A map of Chunker PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getChunkerPipeStreamsMap() {
        if (chunkerPipeStreamsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (chunkerPipeStreamsMap == null) {
                    chunkerPipeStreamsMap = createChunkerPipeStreamMapById();
                }
            }
        }
        return chunkerPipeStreamsMap;
    }

    /**
     * Retrieves a map of Sample PipeDoc objects by ID.
     *
     * @return A map of Sample PipeDoc objects by ID.
     */
    public static Map<String, PipeDoc> getSamplePipeDocumentsMap() {
        if (samplePipeDocumentsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (samplePipeDocumentsMap == null) {
                    samplePipeDocumentsMap = createSamplePipeDocumentMapById();
                }
            }
        }
        return samplePipeDocumentsMap;
    }

    /**
     * Retrieves a map of Sample PipeStream objects by stream ID.
     *
     * @return A map of Sample PipeStream objects by stream ID.
     */
    public static Map<String, PipeStream> getSamplePipeStreamsMap() {
        if (samplePipeStreamsMap == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (samplePipeStreamsMap == null) {
                    samplePipeStreamsMap = createSamplePipeStreamMapById();
                }
            }
        }
        return samplePipeStreamsMap;
    }

    /**
     * Retrieves an ordered list of PipeDoc objects from the Sample Documents.
     *
     * @return An ordered list of PipeDoc objects from the Sample Documents.
     */
    public static List<PipeDoc> getOrderedSamplePipeDocuments() {
        if (orderedSamplePipeDocs == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (orderedSamplePipeDocs == null) {
                    Collection<PipeDoc> docs = getSamplePipeDocuments();
                    orderedSamplePipeDocs = docs.stream()
                            .sorted(Comparator.comparing(doc -> {
                                String id = doc.getId();
                                // Extract the index from the ID (assuming format "doc-XXXXXXXX")
                                return id;
                            }))
                            .collect(Collectors.toList());
                }
            }
        }
        return orderedSamplePipeDocs;
    }

    /**
     * Retrieves an ordered list of PipeStream objects from the Sample Documents.
     *
     * @return An ordered list of PipeStream objects from the Sample Documents.
     */
    public static List<PipeStream> getOrderedSamplePipeStreams() {
        if (orderedSamplePipeStreams == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (orderedSamplePipeStreams == null) {
                    Collection<PipeStream> streams = getSamplePipeStreams();
                    orderedSamplePipeStreams = streams.stream()
                            .sorted(Comparator.comparing(stream -> {
                                String id = stream.getStreamId();
                                // Extract the index from the ID (assuming format "stream-XXXXXXXX")
                                return id;
                            }))
                            .collect(Collectors.toList());
                }
            }
        }
        return orderedSamplePipeStreams;
    }

    /**
     * Retrieves a specific PipeDoc object from the Sample Documents by index.
     *
     * @param index The index of the PipeDoc object to retrieve (0-98).
     * @return The PipeDoc object at the specified index, or null if not found.
     * @throws IndexOutOfBoundsException if the index is out of range (index &lt; 0 || index &gt;= 99)
     */
    public static PipeDoc getSamplePipeDocByIndex(int index) {
        if (index < 0 || index >= 99) {
            throw new IndexOutOfBoundsException("Index must be between 0 and 98, inclusive");
        }
        List<PipeDoc> orderedDocs = getOrderedSamplePipeDocuments();
        return orderedDocs.get(index);
    }

    /**
     * Retrieves pipeline generated documents for a specific stage.
     * Stages include: "after-chunker1", "after-chunker2", "after-embedder1", "after-embedder2"
     *
     * @param stage The pipeline stage to retrieve documents from
     * @return A collection of PipeDoc objects from that stage
     */
    public static Collection<PipeDoc> getPipelineGeneratedDocuments(String stage) {
        if (pipelineGeneratedDocs == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipelineGeneratedDocs == null) {
                    pipelineGeneratedDocs = loadPipelineGeneratedDocuments();
                }
            }
        }
        return pipelineGeneratedDocs.getOrDefault(stage, Collections.emptyList());
    }
    
    /**
     * Retrieves all pipeline stages available.
     *
     * @return A set of stage names
     */
    public static Set<String> getPipelineStages() {
        if (pipelineGeneratedDocs == null) {
            synchronized (ProtobufTestDataHelper.class) {
                if (pipelineGeneratedDocs == null) {
                    pipelineGeneratedDocs = loadPipelineGeneratedDocuments();
                }
            }
        }
        return pipelineGeneratedDocs.keySet();
    }
    
    /**
     * Retrieves a specific PipeStream object from the Sample Documents by index.
     *
     * @param index The index of the PipeStream object to retrieve (0-98).
     * @return The PipeStream object at the specified index, or null if not found.
     * @throws IndexOutOfBoundsException if the index is out of range (index &lt; 0 || index &gt;= 99)
     */
    public static PipeStream getSamplePipeStreamByIndex(int index) {
        if (index < 0 || index >= 99) {
            throw new IndexOutOfBoundsException("Index must be between 0 and 98, inclusive");
        }
        List<PipeStream> orderedStreams = getOrderedSamplePipeStreams();
        return orderedStreams.get(index);
    }

    /**
     * Creates a map of PipeDoc objects by ID.
     *
     * @return A map of PipeDoc objects by ID.
     */
    private static Map<String, PipeDoc> createPipeDocumentMapById() {
        Collection<PipeDoc> docs = getPipeDocuments();
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
        Collection<PipeStream> streams = getPipeStreams();
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
        Collection<PipeDoc> docs = getTikaPipeDocuments();
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
        Collection<PipeStream> streams = getTikaPipeStreams();
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
        Collection<PipeDoc> docs = getChunkerPipeDocuments();
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
        Collection<PipeStream> streams = getChunkerPipeStreams();
        Map<String, PipeStream> returnVal = Maps.newHashMapWithExpectedSize(streams.size());
        streams.forEach((stream) -> returnVal.put(stream.getStreamId(), stream));
        return returnVal;
    }

    /**
     * Creates a map of Sample PipeDoc objects by ID.
     *
     * @return A map of Sample PipeDoc objects by ID.
     */
    private static Map<String, PipeDoc> createSamplePipeDocumentMapById() {
        Collection<PipeDoc> docs = getSamplePipeDocuments();
        Map<String, PipeDoc> returnVal = Maps.newHashMapWithExpectedSize(docs.size());
        docs.forEach((doc) -> returnVal.put(doc.getId(), doc));
        return returnVal;
    }

    /**
     * Creates a map of Sample PipeStream objects by stream ID.
     *
     * @return A map of Sample PipeStream objects by stream ID.
     */
    private static Map<String, PipeStream> createSamplePipeStreamMapById() {
        Collection<PipeStream> streams = getSamplePipeStreams();
        Map<String, PipeStream> returnVal = Maps.newHashMapWithExpectedSize(streams.size());
        streams.forEach((stream) -> returnVal.put(stream.getStreamId(), stream));
        return returnVal;
    }

    /**
     * Creates a collection of PipeDoc objects from the specified directory.
     *
     * @return A collection of PipeDoc objects from the specified directory.
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
     * @return A collection of PipeStream objects from the specified directory.
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
     * @return A collection of PipeDoc objects from the Tika parser directory.
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
     * @return A collection of PipeStream objects from the Tika parser directory.
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
     * @return A collection of PipeDoc objects from the Chunker directory.
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
     * @return A collection of PipeStream objects from the Chunker directory.
     */
    private static Collection<PipeStream> createChunkerPipeStreams() {
        try {
            return loadPipeStreamsFromDirectory(CHUNKER_PIPE_STREAM_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Creates a collection of PipeDoc objects from the Sample Documents directory.
     *
     * @return A collection of PipeDoc objects from the Sample Documents.
     */
    private static Collection<PipeDoc> createSamplePipeDocuments() {
        try {
            return loadPipeDocsFromDirectory(SAMPLE_PIPE_DOC_DIRECTORY);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Creates a collection of PipeStream objects from the Sample Documents directory.
     *
     * @return A collection of PipeStream objects from the Sample Documents.
     */
    private static Collection<PipeStream> createSamplePipeStreams() {
        try {
            return loadPipeStreamsFromDirectory(SAMPLE_PIPE_STREAM_DIRECTORY);
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
        Collection<PipeDoc> returnVal = new ArrayList<>();
        Stream<Path> walk = getPathsFromDirectory(directory);
        walk.forEach(path -> {
            try {
                PipeDoc doc = PipeDoc.parseFrom(Files.newInputStream(path));
                returnVal.add(doc);
            } catch (IOException e) {
                // Skip this file
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
        Collection<PipeStream> returnVal = new ArrayList<>();
        Stream<Path> walk = getPathsFromDirectory(directory);
        walk.forEach(path -> {
            try {
                PipeStream stream = PipeStream.parseFrom(Files.newInputStream(path));
                returnVal.add(stream);
            } catch (IOException e) {
                // Skip this file
            }
        });
        return returnVal;
    }

    /**
     * Retrieves a stream of paths from the given directory.
     *
     * @param directory The directory to retrieve paths from.
     * @return A stream of paths.
     * @throws IOException If an I/O error occurs.
     */
    private static Stream<Path> getPathsFromDirectory(String directory) throws IOException {
        URI uri;
        try {
            uri = ProtobufTestDataHelper.class.getResource(directory).toURI();
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }

        Path myPath;
        if (uri.getScheme().equals("jar")) {
            FileSystem fileSystem;
            try {
                fileSystem = FileSystems.getFileSystem(uri);
            } catch (Exception e) {
                fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
            }
            myPath = fileSystem.getPath(directory);
        } else {
            myPath = Paths.get(uri);
        }

        Stream<Path> walk;
        try {
            walk = Files.walk(myPath, 1);
        } catch (IOException e) {
            throw new IOException(e);
        }
        return walk;
    }
    
    /**
     * Loads pipeline generated documents from all stages.
     *
     * @return A map of stage name to collection of PipeDoc objects
     */
    private static Map<String, Collection<PipeDoc>> loadPipelineGeneratedDocuments() {
        Map<String, Collection<PipeDoc>> result = new HashMap<>();
        
        try {
            URI uri = ProtobufTestDataHelper.class.getResource(PIPELINE_GENERATED_DIRECTORY).toURI();
            Path pipelineDir;
            
            if (uri.getScheme().equals("jar")) {
                FileSystem fileSystem;
                try {
                    fileSystem = FileSystems.getFileSystem(uri);
                } catch (Exception e) {
                    fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                }
                pipelineDir = fileSystem.getPath(PIPELINE_GENERATED_DIRECTORY);
            } else {
                pipelineDir = Paths.get(uri);
            }
            
            // List all subdirectories (stages)
            try (Stream<Path> stages = Files.list(pipelineDir)) {
                stages.filter(Files::isDirectory).forEach(stageDir -> {
                    String stageName = stageDir.getFileName().toString();
                    try {
                        Collection<PipeDoc> docs = loadPipeDocsFromDirectory(stageDir.toString());
                        result.put(stageName, docs);
                    } catch (IOException e) {
                        // Skip this stage on error
                        result.put(stageName, Collections.emptyList());
                    }
                });
            }
        } catch (Exception e) {
            // Return empty map on any error
            return Collections.emptyMap();
        }
        
        return result;
    }
}