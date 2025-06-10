package com.krickert.search.pipeline.integration.util;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ServiceMetadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility class for generating test documents and PipeStream objects for integration tests.
 */
public class TestDocumentGenerator {

    private static final String METADATA_CSV_PATH = "/sample_documents/metadata.csv";
    private static final String SAMPLE_DOCUMENTS_BASE_PATH = "/sample_documents/";

    /**
     * Reads the content of a text file from the classpath.
     *
     * @param filePath The path to the file, relative to the classpath
     * @return The content of the file as a string
     * @throws IOException If the file cannot be read
     */
    private static String readFileFromClasspath(String filePath) throws IOException {
        try (InputStream inputStream = TestDocumentGenerator.class.getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("File not found: " + filePath);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    /**
     * Reads metadata from the CSV file and creates documents based on it.
     *
     * @return A list of documents created from the metadata CSV
     */
    private static List<PipeDoc> loadDocumentsFromCsv() {
        List<PipeDoc> documents = new ArrayList<>();
        try {
            String csvContent = readFileFromClasspath(METADATA_CSV_PATH);
            String[] lines = csvContent.split("\n");

            // Skip header line
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length < 4) {
                    System.err.println("Invalid CSV line: " + line);
                    continue;
                }

                String filePath = parts[0].trim();
                String title = parts[1].trim();
                String author = parts[2].trim();
                String date = parts[3].trim();

                try {
                    String documentContent = readFileFromClasspath(SAMPLE_DOCUMENTS_BASE_PATH + filePath);

                    Map<String, String> customData = new TreeMap<>();
                    customData.put("author", author);
                    customData.put("date", date);
                    customData.put("source", "csv");

                    String docId = "doc-" + filePath.replaceAll("[^a-zA-Z0-9]", "-");

                    PipeDoc document = createSampleDocument(
                            docId,
                            title,
                            documentContent,
                            customData,
                            false // No blob for CSV-loaded documents
                    );

                    documents.add(document);
                } catch (IOException e) {
                    System.err.println("Error reading document file: " + filePath + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading metadata CSV: " + e.getMessage());
        }

        return documents;
    }

    /**
     * Creates a sample PipeDoc with the specified content.
     *
     * @param id The document ID
     * @param title The document title
     * @param body The document body
     * @param customData Custom data for the document
     * @param includeBlob Whether to include a blob in the document
     * @return A PipeDoc with the specified content
     */
    public static PipeDoc createSampleDocument(String id, String title, String body, 
                                              Map<String, String> customData, boolean includeBlob) {
        PipeDoc.Builder docBuilder = PipeDoc.newBuilder()
                .setId(id)
                .setTitle(title)
                .setBody(body);

        // Add custom data if provided
        if (customData != null && !customData.isEmpty()) {
            Struct.Builder structBuilder = Struct.newBuilder();
            for (Map.Entry<String, String> entry : customData.entrySet()) {
                structBuilder.putFields(
                        entry.getKey(),
                        Value.newBuilder().setStringValue(entry.getValue()).build()
                );
            }
            docBuilder.setCustomData(structBuilder.build());
        }

        // Add blob if requested
        if (includeBlob) {
            Blob blob = Blob.newBuilder()
                    .setBlobId("blob-" + UUID.randomUUID())
                    .setFilename("sample.txt")
                    .setData(ByteString.copyFromUtf8("Sample blob content for " + title))
                    .setMimeType("text/plain")
                    .build();
            docBuilder.setBlob(blob);
        }

        return docBuilder.build();
    }

    /**
     * Creates a PipeStream object with the specified document.
     *
     * @param document The document to include in the PipeStream
     * @param pipelineName The pipeline name
     * @param targetStepName The target step name
     * @param contextParams Context parameters
     * @return A PipeStream with the specified content
     */
    public static PipeStream createPipeStream(PipeDoc document, String pipelineName, 
                                             String targetStepName, Map<String, String> contextParams) {
        PipeStream.Builder builder = PipeStream.newBuilder()
                .setStreamId("stream-" + UUID.randomUUID())
                .setDocument(document)
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(targetStepName)
                .setCurrentHopNumber(0);

        // Add context params if provided
        if (contextParams != null && !contextParams.isEmpty()) {
            builder.putAllContextParams(contextParams);
        }

        return builder.build();
    }

    /**
     * Creates a ProcessRequest for the specified document and metadata.
     *
     * @param document The document to include in the request
     * @param pipelineName The pipeline name
     * @param stepName The step name
     * @param streamId The stream ID
     * @param hopNumber The hop number
     * @param customConfig Custom configuration
     * @param configParams Configuration parameters
     * @return A ProcessRequest with the specified content
     */
    public static ProcessRequest createProcessRequest(PipeDoc document, String pipelineName, 
                                                    String stepName, String streamId, 
                                                    long hopNumber, Struct customConfig, 
                                                    Map<String, String> configParams) {
        ServiceMetadata.Builder metadataBuilder = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(hopNumber);

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customConfig != null) {
            configBuilder.setCustomJsonConfig(customConfig);
        }
        if (configParams != null) {
            configBuilder.putAllConfigParams(configParams);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setConfig(configBuilder.build())
                .setMetadata(metadataBuilder.build())
                .build();
    }

    /**
     * Creates a list of sample documents with various content.
     *
     * @return A list of sample documents
     */
    public static List<PipeDoc> createSampleDocuments() {
        List<PipeDoc> documents = new ArrayList<>();

        // Document 1: About the team and integration tests
        Map<String, String> customData1 = new TreeMap<>();
        customData1.put("author", "Test Team");
        customData1.put("category", "Humor");
        documents.add(createSampleDocument(
                "doc-integration",
                "The team's Integration Test Skills",
                "The team is absolutely incredible at creating integrated tests. Our attention to detail and " +
                "ability to think through complex scenarios is unmatched. The team often jokes that our team " +
                "doesn't just find bugs, but also predicts them before they even happen! When asked about our " +
                "secret, we all just smile and say, 'Good tests are like good jokes - timing is everything, " +
                "and you need to expect the unexpected.' The entire development team agrees: if your code " +
                "passes our integration tests, it's ready for anything!",
                customData1,
                true
        ));

        // Document 2: About VIM and the old days of fun memory management
        Map<String, String> customData2 = new TreeMap<>();
        customData2.put("author", "Developer");
        customData2.put("category", "VIM 4 life");
        documents.add(createSampleDocument(
                "developers-developers-developers",
                "Developers! Developers! Developers! (Parody - humor)",
                "Developers! Developers! Developers! Appreciate the art of debugging without stack traces " +
                "or the joy of compiling code for hours just to fix a missing semicolon. Back in my day, we " +
                "had to walk uphill both ways in the snow just to get to our terminals, and we liked it! " +
                "I am the fancy code creator!  Give me VIM and I will code a storm. All these " +
                "new programming languages with their automatic memory management are easy, but it's so much more fun to make your own. " +
                "In my day, we managed our own memory and we were bowed down when segmentation faults happened. " +
                "Everyone should feel that joy and satisfaction of spending three days tracking down a pointer error.",
                customData2,
                true
        ));

        // Document 3: Technical article
        Map<String, String> customData3 = new TreeMap<>();
        customData3.put("author", "Tech Writer");
        customData3.put("category", "Technical");
        documents.add(createSampleDocument(
                "doc-microservices",
                "Understanding Microservices Architecture",
                "Microservices architecture is an approach to software development where applications are " +
                "built as a collection of small, independent services that communicate over well-defined APIs. " +
                "Each microservice is focused on a specific business capability and can be developed, deployed, " +
                "and scaled independently. This approach offers benefits like improved scalability, resilience, " +
                "and development velocity, but also introduces challenges in terms of distributed system complexity, " +
                "data consistency, and operational overhead. Successful microservices implementations require " +
                "careful consideration of service boundaries, communication patterns, and deployment strategies.",
                customData3,
                false
        ));

        // Document 4: Short story
        Map<String, String> customData4 = new TreeMap<>();
        customData4.put("author", "Creative Writer");
        customData4.put("category", "Fiction");
        documents.add(createSampleDocument(
                "doc-short-story",
                "The Bug That Couldn't Be Fixed",
                "Once upon a time, there was a mysterious bug that appeared in production every third Tuesday " +
                "of the month, but only when the moon was waxing gibbous. Developers came and went, each one " +
                "claiming they had fixed it, only for it to return again. Some said it was a ghost in the machine, " +
                "others blamed cosmic rays. The bug became legendary, with its own Slack channel and fan club. " +
                "Years passed, and one day, a junior developer accidentally spilled coffee on the server. " +
                "Miraculously, the bug was never seen again. The moral of the story? Sometimes the best debugging " +
                "tool is a cup of coffee.",
                customData4,
                true
        ));

        // Document 5: Product documentation
        Map<String, String> customData5 = new TreeMap<>();
        customData5.put("author", "Documentation Team");
        customData5.put("category", "Documentation");
        documents.add(createSampleDocument(
                "doc-product-docs",
                "YAPPY Pipeline System Documentation",
                "YAPPY (Yet Another Pipeline Processor: YAPPY) is a multi-project Micronaut application that " +
                "serves as a configurable pipeline platform for creating multiple indexes through a scalable " +
                "container-based microservice architecture. The system is designed for processing data pipelines " +
                "with a decentralized approach where information is shared between components in a control plane " +
                "and configuration changes happen in near real-time. The goal is to provide a low-cost, free, " +
                "enterprise-grade secure document index with easy installation, streaming orchestration, and " +
                "rapid pipeline development in any language.",
                customData5,
                false
        ));

        // Documents 6+: Load from CSV file
        documents.addAll(loadDocumentsFromCsv());

        return documents;
    }
}
