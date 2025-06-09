package com.krickert.search.model.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ProtobufUtils;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * Test class for the DocumentAnalyzer.
 */
public class DocumentAnalyzerTest {

    /**
     * Test that the document analysis runs successfully and returns valid results.
     */
    @Test
    public void testDocumentAnalysis() {
        // Run the analysis
        DocumentAnalyzer.AnalysisResult result = DocumentAnalyzer.analyzeDocuments();

        // Verify the results
        assertNotNull(result, "Analysis result should not be null");
        assertTrue(result.getTotalDocuments() > 0, "Total documents should be greater than 0");

        // Verify that the counts are consistent
        int totalDocuments = result.getTotalDocuments();
        int documentsWithTitle = result.getDocumentsWithTitle();
        int documentsWithBody = result.getDocumentsWithBody();
        int documentsMissingBoth = result.getDocumentsMissingBoth();

        // The number of documents missing both title and body should be less than or equal to the total
        assertTrue(documentsMissingBoth <= totalDocuments, 
                "Documents missing both should be less than or equal to total documents");

        // The number of documents with title should be less than or equal to the total
        assertTrue(documentsWithTitle <= totalDocuments, 
                "Documents with title should be less than or equal to total documents");

        // The number of documents with body should be less than or equal to the total
        assertTrue(documentsWithBody <= totalDocuments, 
                "Documents with body should be less than or equal to total documents");

        // The number of files missing both should match the count
        assertEquals(documentsMissingBoth, result.getFilesMissingBoth().size(), 
                "Number of files missing both should match the count");

        // Verify that the percentages are calculated correctly
        assertNotNull(result.getPercentages(), "Percentages should not be null");
        assertEquals(3, result.getPercentages().size(), "There should be 3 percentage values");

        // Print the results for manual verification
        System.out.println("=== Document Analysis Results ===");
        System.out.println("Total documents: " + totalDocuments);
        System.out.println("Documents with title: " + documentsWithTitle + 
                " (" + result.getPercentages().get("withTitle") + "%)");
        System.out.println("Documents with body: " + documentsWithBody + 
                " (" + result.getPercentages().get("withBody") + "%)");
        System.out.println("Documents missing both: " + documentsMissingBoth + 
                " (" + result.getPercentages().get("missingBoth") + "%)");

        if (documentsMissingBoth > 0) {
            System.out.println("\nFiles missing both title and body:");
            for (String file : result.getFilesMissingBoth()) {
                System.out.println("  " + file);
            }
        }

        // Print the results as JSON
        System.out.println("\n=== JSON Output ===");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(result);
        System.out.println(json);
    }

    /**
     * Test that the writeJsonToFile method correctly writes JSON to a file.
     * 
     * @param tempDir A temporary directory provided by JUnit
     * @throws IOException If an I/O error occurs
     */
    @Test
    public void testWriteJsonToFile(@TempDir Path tempDir) throws IOException {
        // Create a test JSON string
        String testJson = "{\"test\":\"value\"}";

        // Create a file path in the temporary directory
        Path testFile = tempDir.resolve("test-output.json");

        // Call the method to write the JSON to the file
        DocumentAnalyzer.writeJsonToFile(testJson, testFile.toString());

        // Verify that the file exists
        assertTrue(Files.exists(testFile), "The output file should exist");

        // Verify that the file contains the expected content
        String fileContent = Files.readString(testFile);
        assertEquals(testJson, fileContent, "The file content should match the input JSON");

        // Print confirmation
        System.out.println("\n=== File Write Test ===");
        System.out.println("Successfully wrote and verified JSON to: " + testFile);
    }

    /**
     * Test that the analyzeDirectory method correctly analyzes a directory of protobuf files.
     * 
     * @param tempDir A temporary directory provided by JUnit
     * @throws IOException If an I/O error occurs
     */
    @Test
    public void testAnalyzeDirectory(@TempDir Path tempDir) throws IOException {
        // Create a test directory for protobuf files
        Path protoDir = tempDir.resolve("proto-files");
        Files.createDirectories(protoDir);

        // Get some sample documents to copy to the test directory
        Collection<PipeDoc> sampleDocs = ProtobufTestDataHelper.getSamplePipeDocuments();
        assertTrue(sampleDocs.size() > 0, "Should have at least one sample document");

        // Copy the first few sample documents to the test directory with the required naming pattern
        int count = 0;
        for (PipeDoc doc : sampleDocs) {
            if (count >= 5) break; // Just use a few documents for the test

            String fileName = "test-doc-" + count + ".bin";
            Path filePath = protoDir.resolve(fileName);

            // Save the document to the test directory
            ProtobufUtils.saveProtobufToDisk(filePath.toString(), doc);
            count++;
        }

        System.out.println("Created " + count + " test files in: " + protoDir);

        // Call the analyzeDirectory method
        DocumentAnalyzer.AnalysisResult result = DocumentAnalyzer.analyzeDirectory(protoDir.toString());

        // Verify the results
        assertNotNull(result, "Analysis result should not be null");
        assertEquals(count, result.getTotalDocuments(), "Total documents should match the number of files created");

        // Print the results
        System.out.println("\n=== Directory Analysis Results ===");
        System.out.println("Total documents: " + result.getTotalDocuments());
        System.out.println("Documents with title: " + result.getDocumentsWithTitle());
        System.out.println("Documents with body: " + result.getDocumentsWithBody());
        System.out.println("Documents missing both: " + result.getDocumentsMissingBoth());
    }
}
