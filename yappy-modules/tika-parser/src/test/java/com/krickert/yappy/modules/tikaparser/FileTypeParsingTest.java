package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.ByteString;
import com.krickert.search.model.Blob;
import com.krickert.search.model.ParsedDocument;
import com.krickert.search.model.ParsedDocumentReply;
import org.apache.tika.exception.TikaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the DocumentParser with various file types.
 */
class FileTypeParsingTest {

    private static final Logger LOG = LoggerFactory.getLogger(FileTypeParsingTest.class);
    private static final String TEST_DOCUMENTS_DIR = "test-documents";
    private static final String METADATA_CSV = "metadata.csv";

    /**
     * Provides test cases for different file types.
     * Each test case includes the file path and expected metadata.
     */
    static Stream<TestCase> fileTypeTestCases() {
        List<TestCase> testCases = new ArrayList<>();
        Map<String, Map<String, String>> metadataMap = loadMetadataFromCsv();

        // Get all files in the test-documents directory
        try {
            ClassLoader classLoader = FileTypeParsingTest.class.getClassLoader();
            InputStream dirStream = classLoader.getResourceAsStream(TEST_DOCUMENTS_DIR);
            if (dirStream == null) {
                LOG.error("Could not find test-documents directory");
                return Stream.empty();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(dirStream))) {
                List<String> fileNames = reader.lines()
                        .filter(name -> !name.equals(METADATA_CSV)) // Skip the metadata CSV file
                        .collect(Collectors.toList());

                for (String fileName : fileNames) {
                    Map<String, String> metadata = metadataMap.getOrDefault(fileName, new HashMap<>());
                    String filePath = TEST_DOCUMENTS_DIR + "/" + fileName;
                    testCases.add(new TestCase(filePath, fileName, metadata));
                }
            }
        } catch (IOException e) {
            LOG.error("Error reading test documents", e);
        }

        return testCases.stream();
    }

    /**
     * Loads metadata from the CSV file.
     */
    private static Map<String, Map<String, String>> loadMetadataFromCsv() {
        Map<String, Map<String, String>> metadataMap = new HashMap<>();
        ClassLoader classLoader = FileTypeParsingTest.class.getClassLoader();
        String csvPath = TEST_DOCUMENTS_DIR + "/" + METADATA_CSV;

        try (InputStream is = classLoader.getResourceAsStream(csvPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            // Read header
            String headerLine = reader.readLine();
            if (headerLine == null) {
                LOG.error("Metadata CSV file is empty");
                return metadataMap;
            }

            String[] headers = headerLine.split(",");

            // Read data rows
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length >= headers.length) {
                    String fileName = values[0];
                    Map<String, String> fileMetadata = new HashMap<>();

                    for (int i = 1; i < headers.length; i++) {
                        fileMetadata.put(headers[i], values[i]);
                    }

                    metadataMap.put(fileName, fileMetadata);
                }
            }
        } catch (IOException e) {
            LOG.error("Error reading metadata CSV", e);
        }

        return metadataMap;
    }

    @ParameterizedTest
    @MethodSource("fileTypeTestCases")
    @DisplayName("Should parse different file types correctly")
    void testParseDocument(TestCase testCase) throws IOException, SAXException, TikaException {
        LOG.info("Testing file: {}", testCase.fileName);

        // Load the file content
        ClassLoader classLoader = getClass().getClassLoader();
        InputStream fileStream = classLoader.getResourceAsStream(testCase.filePath);
        assertNotNull(fileStream, "Test file not found: " + testCase.filePath);

        byte[] fileContent = fileStream.readAllBytes();
        ByteString content = ByteString.copyFrom(fileContent);

        // Create parser configuration
        Map<String, String> config = new HashMap<>();
        config.put("extractMetadata", "true");

        // Parse the document
        ParsedDocumentReply reply = DocumentParser.parseDocument(content, config);
        
        // Verify the result
        assertNotNull(reply, "Parsed document reply should not be null");
        ParsedDocument parsedDoc = reply.getDoc();
        assertNotNull(parsedDoc, "Parsed document should not be null");
        
        // For text-based documents, we should have some content
        if (testCase.metadata.getOrDefault("content_type", "").startsWith("text/") ||
            testCase.metadata.getOrDefault("content_type", "").contains("document") ||
            testCase.metadata.getOrDefault("content_type", "").contains("pdf")) {
            
            assertFalse(parsedDoc.getBody().isEmpty(), "Parsed body should not be empty for text-based documents");
        }
        
        // Check if metadata was extracted
        assertFalse(parsedDoc.getMetadataMap().isEmpty(), "Metadata should be extracted");
        
        LOG.info("Successfully parsed file: {}", testCase.fileName);
        LOG.info("Extracted title: {}", parsedDoc.getTitle());
        LOG.info("Body length: {}", parsedDoc.getBody().length());
        LOG.info("Metadata entries: {}", parsedDoc.getMetadataMap().size());
    }

    /**
     * Test case class for parameterized tests.
     */
    static class TestCase {
        final String filePath;
        final String fileName;
        final Map<String, String> metadata;

        TestCase(String filePath, String fileName, Map<String, String> metadata) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.metadata = metadata;
        }

        @Override
        public String toString() {
            return fileName;
        }
    }
}