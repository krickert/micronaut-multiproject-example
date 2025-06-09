package com.krickert.search.model.test;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ProtobufUtils;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class for analyzing document metadata in the sample documents.
 * This class analyzes the sample documents to determine how many have titles,
 * how many have bodies, and how many are missing both.
 */
public class DocumentAnalyzer {

    /**
     * Main method to run the analysis and print the results.
     * 
     * @param args Command line arguments (optional directory path)
     */
    public static void main(String[] args) {
        System.out.println("=== Document Analysis ===\n");

        AnalysisResult result;
        String outputFileName = "document-analysis-results.json";

        try {
            // Check if a directory path was provided
            if (args.length > 0) {
                String directoryPath = args[0];
                System.out.println("Analyzing directory: " + directoryPath);

                // If a second argument is provided, use it as the output file name
                if (args.length > 1) {
                    outputFileName = args[1];
                }

                // Analyze the specified directory
                result = analyzeDirectory(directoryPath);
            } else {
                System.out.println("No directory specified. Analyzing sample documents.");
                // Analyze the sample documents
                result = analyzeDocuments();
            }

            // Print the results as JSON
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(result);
            System.out.println(json);

            // Write the results to a file
            writeJsonToFile(json, outputFileName);
            System.out.println("\nResults written to " + outputFileName);

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Writes the JSON output to a file.
     * 
     * @param json The JSON string to write
     * @param fileName The name of the file to write to
     * @throws IOException If an I/O error occurs
     */
    public static void writeJsonToFile(String json, String fileName) throws IOException {
        Path filePath = Paths.get(fileName);
        try (FileWriter writer = new FileWriter(filePath.toFile())) {
            writer.write(json);
        }
    }

    /**
     * Analyzes the sample documents and returns the results.
     * 
     * @return The analysis results
     */
    public static AnalysisResult analyzeDocuments() {
        // Get the sample documents
        Collection<PipeDoc> documents = ProtobufTestDataHelper.getSamplePipeDocuments();
        return analyzeDocumentCollection(documents);
    }

    /**
     * Analyzes documents in any directory that match the pattern *-[DOC_NUM].bin.
     * 
     * @param directoryPath The path to the directory containing the protobuf files
     * @return The analysis results
     * @throws IOException If an I/O error occurs
     */
    public static AnalysisResult analyzeDirectory(String directoryPath) throws IOException {
        // Get all files in the directory that match the pattern *-[0-9]+.bin
        Path dirPath = Paths.get(directoryPath);
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
        }

        // Define the pattern for files ending with -[DOC_NUM].bin
        Pattern pattern = Pattern.compile(".*-\\d+\\.bin$");

        // Find all matching files
        List<Path> matchingFiles = Files.list(dirPath)
            .filter(Files::isRegularFile)
            .filter(path -> pattern.matcher(path.getFileName().toString()).matches())
            .collect(Collectors.toList());

        System.out.println("Found " + matchingFiles.size() + " matching files in directory: " + directoryPath);

        // Load each file as a PipeDoc
        List<PipeDoc> documents = new ArrayList<>();
        for (Path file : matchingFiles) {
            try {
                PipeDoc doc = ProtobufUtils.loadPipeDocFromDisk(file.toString());
                documents.add(doc);
            } catch (IOException e) {
                System.err.println("Error loading file: " + file + " - " + e.getMessage());
            }
        }

        System.out.println("Successfully loaded " + documents.size() + " documents");

        // Analyze the documents
        return analyzeDocumentCollection(documents);
    }

    /**
     * Analyzes a collection of PipeDoc objects and returns the results.
     * 
     * @param documents The collection of PipeDoc objects to analyze
     * @return The analysis results
     */
    private static AnalysisResult analyzeDocumentCollection(Collection<PipeDoc> documents) {
        // Initialize counters
        int totalDocuments = documents.size();
        int documentsWithTitle = 0;
        int documentsWithBody = 0;
        int documentsMissingBoth = 0;
        List<String> filesMissingBoth = new ArrayList<>();

        // Analyze each document
        for (PipeDoc doc : documents) {
            boolean hasTitle = doc.hasTitle() && !doc.getTitle().isEmpty();
            boolean hasBody = doc.hasBody() && !doc.getBody().isEmpty();

            if (hasTitle) {
                documentsWithTitle++;
            }

            if (hasBody) {
                documentsWithBody++;
            }

            if (!hasTitle && !hasBody) {
                documentsMissingBoth++;
                filesMissingBoth.add(doc.getId());
            }
        }

        // Create and return the result
        return new AnalysisResult(
            totalDocuments,
            documentsWithTitle,
            documentsWithBody,
            documentsMissingBoth,
            filesMissingBoth
        );
    }

    /**
     * Class representing the results of the document analysis.
     */
    public static class AnalysisResult {
        private final int totalDocuments;
        private final int documentsWithTitle;
        private final int documentsWithBody;
        private final int documentsMissingBoth;
        private final List<String> filesMissingBoth;
        private final Map<String, Object> percentages;

        /**
         * Constructor for the AnalysisResult class.
         * 
         * @param totalDocuments The total number of documents analyzed
         * @param documentsWithTitle The number of documents with a title
         * @param documentsWithBody The number of documents with a body
         * @param documentsMissingBoth The number of documents missing both title and body
         * @param filesMissingBoth The list of files missing both title and body
         */
        public AnalysisResult(
                int totalDocuments,
                int documentsWithTitle,
                int documentsWithBody,
                int documentsMissingBoth,
                List<String> filesMissingBoth) {
            this.totalDocuments = totalDocuments;
            this.documentsWithTitle = documentsWithTitle;
            this.documentsWithBody = documentsWithBody;
            this.documentsMissingBoth = documentsMissingBoth;
            this.filesMissingBoth = filesMissingBoth;

            // Calculate percentages
            this.percentages = new HashMap<>();
            if (totalDocuments > 0) {
                this.percentages.put("withTitle", (double) documentsWithTitle / totalDocuments * 100);
                this.percentages.put("withBody", (double) documentsWithBody / totalDocuments * 100);
                this.percentages.put("missingBoth", (double) documentsMissingBoth / totalDocuments * 100);
            }
        }

        // Getters
        public int getTotalDocuments() {
            return totalDocuments;
        }

        public int getDocumentsWithTitle() {
            return documentsWithTitle;
        }

        public int getDocumentsWithBody() {
            return documentsWithBody;
        }

        public int getDocumentsMissingBoth() {
            return documentsMissingBoth;
        }

        public List<String> getFilesMissingBoth() {
            return filesMissingBoth;
        }

        public Map<String, Object> getPercentages() {
            return percentages;
        }
    }
}
