package com.krickert.search.model.util;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TestDataHelper is a utility class that provides methods for retrieving protobuf test data.
 * It contains static fields and static methods for creating and retrieving test data.
 */
public class ProtobufTestDataHelper {
    
    private static final Logger log = LoggerFactory.getLogger(ProtobufTestDataHelper.class);
    
    private static final String PIPE_DOC_DIRECTORY = "/test-data/pipe-docs";
    private static final String PIPE_STREAM_DIRECTORY = "/test-data/pipe-streams";
    private static final String TIKA_PIPE_DOC_DIRECTORY = "/test-data/tika-pipe-docs";
    private static final String TIKA_PIPE_STREAM_DIRECTORY = "/test-data/tika-pipe-streams";
    private static final String CHUNKER_PIPE_DOC_DIRECTORY = "/test-data/chunker-pipe-docs";
    private static final String CHUNKER_PIPE_STREAM_DIRECTORY = "/test-data/chunker-pipe-streams";
    private static final String SAMPLE_DOCUMENTS_DIRECTORY = "/test-data/sample-documents";
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
    
    /**
     * Loads sample PipeStreams from the sample documents directory.
     * This method reads various file types from the sample documents directory and creates PipeStream objects
     * that can be used for testing purposes.
     *
     * @return A collection of PipeStream objects created from sample documents
     */
    public static Collection<PipeStream> loadSamplePipeStreams() {
        List<PipeStream> pipeStreams = new ArrayList<>();
        
        try {
            // Get the URI of the sample documents directory
            URI uri = ProtobufTestDataHelper.class.getResource(SAMPLE_DOCUMENTS_DIRECTORY).toURI();
            Path sampleDocsPath;
            
            // Handle both JAR and filesystem paths
            if (uri.getScheme().equals("jar")) {
                FileSystem fileSystem = null;
                try {
                    fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                    sampleDocsPath = fileSystem.getPath(SAMPLE_DOCUMENTS_DIRECTORY);
                } catch (FileSystemAlreadyExistsException e) {
                    fileSystem = FileSystems.getFileSystem(uri);
                    sampleDocsPath = fileSystem.getPath(SAMPLE_DOCUMENTS_DIRECTORY);
                }
            } else {
                sampleDocsPath = Paths.get(uri);
            }
            
            // Read all files in the directory (non-recursive)
            try (Stream<Path> paths = Files.list(sampleDocsPath)) {
                List<Path> fileList = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("metadata.csv")) // Skip metadata file
                    .collect(Collectors.toList());
                
                // Process each file
                for (Path filePath : fileList) {
                    try {
                        String fileName = filePath.getFileName().toString();
                        
                        // Read file content
                        byte[] fileContent;
                        try (InputStream is = Files.newInputStream(filePath)) {
                            fileContent = is.readAllBytes();
                        }
                        
                        // Create a PipeDoc to hold the binary data in a Blob
                        Blob.Builder blobBuilder = Blob.newBuilder()
                            .setData(ByteString.copyFrom(fileContent))
                            .setFilename(fileName);
                        
                        // Add MIME type based on file extension
                        String mimeType = guessMimeType(fileName);
                        if (mimeType != null) {
                            blobBuilder.setMimeType(mimeType);
                        }
                        
                        // Add metadata
                        blobBuilder.putMetadata("source", "sample-documents");
                        int lastDot = fileName.lastIndexOf('.');
                        if (lastDot > 0 && lastDot < fileName.length() - 1) {
                            String extension = fileName.substring(lastDot + 1).toLowerCase();
                            blobBuilder.putMetadata("file_extension", extension);
                        }
                        
                        // Create PipeDoc with the blob
                        PipeDoc.Builder docBuilder = PipeDoc.newBuilder()
                            .setId("sample-doc-" + fileName.replaceAll("[^a-zA-Z0-9-]", "_"))
                            .setSourceUri("file://" + fileName)
                            .setBlob(blobBuilder.build());
                        
                        if (mimeType != null) {
                            docBuilder.setSourceMimeType(mimeType);
                        }
                        
                        // Create PipeStream with the document
                        PipeStream pipeStream = PipeStream.newBuilder()
                            .setStreamId("sample-stream-" + fileName.replaceAll("[^a-zA-Z0-9-]", "_"))
                            .setDocument(docBuilder.build())
                            .setCurrentPipelineName("sample-pipeline")
                            .setTargetStepName("initial")
                            .setCurrentHopNumber(0)
                            .putContextParams("filename", fileName)
                            .putContextParams("source", "sample-documents")
                            .build();
                        
                        pipeStreams.add(pipeStream);
                        
                    } catch (IOException e) {
                        // Log error but continue processing other files
                        log.error("Failed to load sample file: {} - {}", filePath, e.getMessage(), e);
                    }
                }
            }
            
        } catch (Exception e) {
            // If we can't access the directory, return empty list
            log.error("Failed to load sample documents", e);
            return Collections.emptyList();
        }
        
        return pipeStreams;
    }
    
    /**
     * Guesses the MIME type based on file extension.
     *
     * @param fileName The name of the file
     * @return The guessed MIME type, or null if unknown
     */
    private static String guessMimeType(String fileName) {
        if (fileName == null) {
            return null;
        }
        
        String lowercaseFileName = fileName.toLowerCase();
        
        // Common document types
        if (lowercaseFileName.endsWith(".pdf")) return "application/pdf";
        if (lowercaseFileName.endsWith(".doc")) return "application/msword";
        if (lowercaseFileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lowercaseFileName.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lowercaseFileName.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lowercaseFileName.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lowercaseFileName.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lowercaseFileName.endsWith(".odt")) return "application/vnd.oasis.opendocument.text";
        if (lowercaseFileName.endsWith(".ods")) return "application/vnd.oasis.opendocument.spreadsheet";
        if (lowercaseFileName.endsWith(".odp")) return "application/vnd.oasis.opendocument.presentation";
        if (lowercaseFileName.endsWith(".rtf")) return "application/rtf";
        
        // Text types
        if (lowercaseFileName.endsWith(".txt")) return "text/plain";
        if (lowercaseFileName.endsWith(".html") || lowercaseFileName.endsWith(".htm")) return "text/html";
        if (lowercaseFileName.endsWith(".csv")) return "text/csv";
        if (lowercaseFileName.endsWith(".json")) return "application/json";
        
        // Image types
        if (lowercaseFileName.endsWith(".png")) return "image/png";
        if (lowercaseFileName.endsWith(".jpg") || lowercaseFileName.endsWith(".jpeg")) return "image/jpeg";
        if (lowercaseFileName.endsWith(".gif")) return "image/gif";
        
        // Video types
        if (lowercaseFileName.endsWith(".mp4")) return "video/mp4";
        if (lowercaseFileName.endsWith(".avi")) return "video/x-msvideo";
        if (lowercaseFileName.endsWith(".mov")) return "video/quicktime";
        
        return null; // Unknown type
    }
}