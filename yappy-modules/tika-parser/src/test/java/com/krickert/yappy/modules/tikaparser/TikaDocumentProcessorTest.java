package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.ByteString;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ParsedDocument;
import com.krickert.search.model.ParsedDocumentReply;
import com.krickert.search.model.ProtobufUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class to process all documents in the test-documents directory and save the output as protobuf binaries.
 * This test ensures that Tika is properly parsing documents and saves the output for downstream testing.
 */
public class TikaDocumentProcessorTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaDocumentProcessorTest.class);
    private static final String TEST_DOCUMENTS_DIR = "test-documents";
    private static final String OUTPUT_DIR = "../../yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data/sample-documents";
    private static final String PIPE_DOC_OUTPUT_DIR = OUTPUT_DIR + "/pipe-docs";
    private static final String PIPE_STREAM_OUTPUT_DIR = OUTPUT_DIR + "/pipe-streams";

    @Test
    @DisplayName("Process all documents in test-documents directory and save output as protobuf binaries")
    void processAllDocumentsAndSaveOutput() throws IOException {
        // Create output directories if they don't exist
        Path pipeDocOutputPath = Paths.get(PIPE_DOC_OUTPUT_DIR);
        Path pipeStreamOutputPath = Paths.get(PIPE_STREAM_OUTPUT_DIR);

        // Clean output directories if they exist
        if (Files.exists(pipeDocOutputPath)) {
            Files.walk(pipeDocOutputPath)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        LOG.warn("Failed to delete file: {}", file);
                    }
                });
        }

        if (Files.exists(pipeStreamOutputPath)) {
            Files.walk(pipeStreamOutputPath)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        LOG.warn("Failed to delete file: {}", file);
                    }
                });
        }

        // Create directories
        Files.createDirectories(pipeDocOutputPath);
        Files.createDirectories(pipeStreamOutputPath);

        // Get all files in the test-documents directory
        List<String> fileNames = getTestDocumentFileNames();
        LOG.info("Found {} files in test-documents directory", fileNames.size());

        List<PipeDoc> pipeDocs = new ArrayList<>();
        List<PipeStream> pipeStreams = new ArrayList<>();

        // Process each file
        for (String fileName : fileNames) {
            LOG.info("Processing file: {}", fileName);

            try {
                // Load the file content
                ClassLoader classLoader = getClass().getClassLoader();
                String filePath = TEST_DOCUMENTS_DIR + "/" + fileName;
                InputStream fileStream = classLoader.getResourceAsStream(filePath);

                if (fileStream == null) {
                    LOG.warn("Could not find file: {}", filePath);
                    continue;
                }

                byte[] fileContent = fileStream.readAllBytes();
                ByteString content = ByteString.copyFrom(fileContent);

                // Create parser configuration
                Map<String, String> config = new HashMap<>();
                config.put("extractMetadata", "true");
                config.put("filename", fileName);

                // Parse the document
                ParsedDocumentReply reply = DocumentParser.parseDocument(content, config);
                assertNotNull(reply, "Parsed document reply should not be null");
                ParsedDocument parsedDoc = reply.getDoc();
                assertNotNull(parsedDoc, "Parsed document should not be null");

                // Create PipeDoc
                String docId = "doc-" + UUID.randomUUID().toString().substring(0, 8);
                PipeDoc pipeDoc = PipeDoc.newBuilder()
                        .setId(docId)
                        .setTitle(parsedDoc.getTitle())
                        .setBody(parsedDoc.getBody())
                        .setSourceUri(filePath)
                        .setSourceMimeType(parsedDoc.getMetadataOrDefault("Content-Type", ""))
                        .setProcessedDate(ProtobufUtils.stamp(Instant.now().getEpochSecond()))
                        .setCustomData(com.google.protobuf.Struct.newBuilder().build())
                        .build();

                pipeDocs.add(pipeDoc);

                // Create PipeStream
                String streamId = "stream-" + UUID.randomUUID().toString().substring(0, 8);
                PipeStream pipeStream = PipeStream.newBuilder()
                        .setStreamId(streamId)
                        .setDocument(pipeDoc)
                        .setCurrentPipelineName("tika-parser")
                        .setTargetStepName("test")
                        .setCurrentHopNumber(1)
                        .build();

                pipeStreams.add(pipeStream);

                LOG.info("Successfully processed file: {}", fileName);
            } catch (Exception e) {
                LOG.error("Error processing file: {}", fileName, e);
            }
        }

        // Save PipeDocs to disk
        LOG.info("Saving {} PipeDoc objects to disk", pipeDocs.size());
        for (int i = 0; i < pipeDocs.size(); i++) {
            PipeDoc pipeDoc = pipeDocs.get(i);
            String filename = String.format("pipe_doc_%03d_%s.bin", i, pipeDoc.getId());
            Path docFile = pipeDocOutputPath.resolve(filename);

            try {
                ProtobufUtils.saveProtobufToDisk(docFile.toString(), pipeDoc);
                LOG.info("Saved PipeDoc to: {}", docFile);
            } catch (IOException e) {
                LOG.error("Error saving PipeDoc to disk", e);
            }
        }

        // Save PipeStreams to disk
        LOG.info("Saving {} PipeStream objects to disk", pipeStreams.size());
        for (int i = 0; i < pipeStreams.size(); i++) {
            PipeStream pipeStream = pipeStreams.get(i);
            String filename = String.format("pipe_stream_%03d_%s.bin", i, pipeStream.getStreamId());
            Path streamFile = pipeStreamOutputPath.resolve(filename);

            try {
                ProtobufUtils.saveProtobufToDisk(streamFile.toString(), pipeStream);
                LOG.info("Saved PipeStream to: {}", streamFile);
            } catch (IOException e) {
                LOG.error("Error saving PipeStream to disk", e);
            }
        }

        // Verify that files were created
        assertTrue(Files.list(pipeDocOutputPath).count() > 0, "No PipeDoc files were created");
        assertTrue(Files.list(pipeStreamOutputPath).count() > 0, "No PipeStream files were created");

        LOG.info("Successfully processed all documents and saved output as protobuf binaries");
    }

    /**
     * Gets all file names in the test-documents directory.
     * 
     * @return A list of file names.
     * @throws IOException If an I/O error occurs.
     */
    private List<String> getTestDocumentFileNames() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream dirStream = classLoader.getResourceAsStream(TEST_DOCUMENTS_DIR);
        if (dirStream == null) {
            LOG.error("Could not find test-documents directory");
            return List.of();
        }

        List<String> allFiles = new ArrayList<>();

        // First, get all top-level files
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(dirStream))) {
            List<String> topLevelEntries = reader.lines().collect(Collectors.toList());

            // Process each entry
            for (String entry : topLevelEntries) {
                // Skip metadata.csv
                if (entry.equals("metadata.csv")) {
                    continue;
                }

                // Check if it's a directory by looking at the name
                if (entry.contains(".")) {
                    // It has an extension, likely a file
                    allFiles.add(entry);
                } else {
                    // Might be a directory, try to process it
                    String subDirPath = TEST_DOCUMENTS_DIR + "/" + entry;
                    InputStream subDirStream = classLoader.getResourceAsStream(subDirPath);

                    if (subDirStream != null) {
                        try (BufferedReader subReader = new BufferedReader(new InputStreamReader(subDirStream))) {
                            List<String> subEntries = subReader.lines().collect(Collectors.toList());

                            // If it has entries, it's a directory
                            if (!subEntries.isEmpty()) {
                                // Add files from this subdirectory
                                for (String subEntry : subEntries) {
                                    // Skip directories within subdirectories for simplicity
                                    if (subEntry.contains(".")) {
                                        allFiles.add(entry + "/" + subEntry);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        LOG.info("Found {} files in test-documents directory and subdirectories", allFiles.size());
        return allFiles;
    }
}
