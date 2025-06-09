package com.krickert.yappy.modules.tikaparser;

import com.krickert.search.model.PipeDoc;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Generates a large dataset of Tika-processed documents for testing.
 */
public class LargeTikaDatasetGenerator {

    @TempDir
    Path tempDir;

    @Test
    void generateLargeTikaDataset() throws IOException {
        List<PipeDoc> tikaDocs = new ArrayList<>();
        
        // Create various types of documents
        String[] documentTypes = {
            "article", "report", "email", "memo", "presentation",
            "whitepaper", "manual", "guide", "tutorial", "blog"
        };
        
        String[] topics = {
            "artificial intelligence", "machine learning", "cloud computing",
            "data science", "cybersecurity", "blockchain", "IoT",
            "quantum computing", "robotics", "biotechnology"
        };
        
        // Generate 100 documents
        for (int i = 0; i < 100; i++) {
            String docType = documentTypes[i % documentTypes.length];
            String topic = topics[i % topics.length];
            String docId = String.format("doc-%03d-%s", i, UUID.randomUUID().toString().substring(0, 8));
            
            PipeDoc.Builder docBuilder = PipeDoc.newBuilder()
                .setId(docId)
                .setDocumentType(docType);
            
            // Add title
            String title = String.format("%s on %s - Document %d", 
                capitalize(docType), capitalize(topic), i);
            docBuilder.setTitle(title);
            
            // Add body content
            String body = generateDocumentBody(docType, topic, i);
            docBuilder.setBody(body);
            
            // Add source information
            docBuilder.setSourceUri(String.format("s3://test-bucket/documents/%s/%s.pdf", 
                docType, docId));
            docBuilder.setSourceMimeType("application/pdf");
            
            // Add timestamps
            Instant creationTime = Instant.now().minusSeconds(86400 * (100 - i)); // Older docs created earlier
            docBuilder.setCreationDate(timestampFromInstant(creationTime));
            docBuilder.setProcessedDate(timestampFromInstant(Instant.now()));
            
            // Add keywords
            docBuilder.addKeywords(topic);
            docBuilder.addKeywords(docType);
            docBuilder.addKeywords("test-data");
            
            // Add custom metadata as Tika would
            Struct.Builder customDataBuilder = Struct.newBuilder();
            
            // Tika-like metadata
            customDataBuilder.putFields("Content-Type", 
                Value.newBuilder().setStringValue("application/pdf").build());
            customDataBuilder.putFields("dc:creator", 
                Value.newBuilder().setStringValue("Test Author " + (i % 10)).build());
            customDataBuilder.putFields("pdf:PDFVersion", 
                Value.newBuilder().setStringValue("1.7").build());
            customDataBuilder.putFields("xmpTPg:NPages", 
                Value.newBuilder().setNumberValue(5 + (i % 10)).build());
            customDataBuilder.putFields("dcterms:created", 
                Value.newBuilder().setStringValue(creationTime.toString()).build());
            customDataBuilder.putFields("Last-Modified", 
                Value.newBuilder().setStringValue(creationTime.plusSeconds(3600).toString()).build());
            customDataBuilder.putFields("Content-Length", 
                Value.newBuilder().setNumberValue(body.length() * 2).build());
            
            docBuilder.setCustomData(customDataBuilder.build());
            
            PipeDoc doc = docBuilder.build();
            tikaDocs.add(doc);
        }
        
        // Save to test resources
        Path tikaDir = Path.of("../../yappy-models/protobuf-models-test-data-resources/src/main/resources/test-data/tika-pipe-docs-large");
        tikaDir.toFile().mkdirs();
        
        System.out.println("Saving " + tikaDocs.size() + " Tika documents to: " + tikaDir);
        
        for (int i = 0; i < tikaDocs.size(); i++) {
            PipeDoc doc = tikaDocs.get(i);
            String filename = String.format("tika_doc_%03d_%s.bin", i, doc.getId());
            Path docFile = tikaDir.resolve(filename);
            
            try (FileOutputStream fos = new FileOutputStream(docFile.toFile())) {
                doc.writeTo(fos);
            }
            
            if (i % 10 == 0) {
                System.out.println("Saved " + (i + 1) + " documents...");
            }
        }
        
        System.out.println("\n=== Tika Dataset Generation Summary ===");
        System.out.println("Total documents generated: " + tikaDocs.size());
        System.out.println("Document types: " + String.join(", ", documentTypes));
        System.out.println("Topics covered: " + String.join(", ", topics));
        
        // Calculate total content size
        long totalBodySize = tikaDocs.stream()
            .mapToLong(doc -> doc.getBody().length())
            .sum();
        System.out.println("Total body content size: " + totalBodySize + " characters");
        System.out.println("Average body size: " + (totalBodySize / tikaDocs.size()) + " characters");
        
        assertEquals(100, tikaDocs.size(), "Should have generated 100 documents");
    }
    
    private String generateDocumentBody(String docType, String topic, int index) {
        StringBuilder body = new StringBuilder();
        
        // Opening paragraph
        body.append(String.format("This %s provides a comprehensive overview of %s. ",
            docType, topic));
        body.append("In today's rapidly evolving technological landscape, understanding ");
        body.append(topic).append(" has become increasingly important for organizations ");
        body.append("seeking to maintain competitive advantage.\n\n");
        
        // Main content sections
        String[] sectionTitles = {
            "Introduction",
            "Background and Context",
            "Current State Analysis",
            "Key Challenges",
            "Proposed Solutions",
            "Implementation Strategy",
            "Expected Outcomes",
            "Conclusion"
        };
        
        for (String section : sectionTitles) {
            body.append(section).append("\n\n");
            
            // Add section content
            body.append("The ").append(section.toLowerCase()).append(" section explores ");
            body.append("various aspects of ").append(topic).append(". ");
            
            // Add some variation based on document index
            if (index % 3 == 0) {
                body.append("Recent studies have shown significant progress in this area. ");
            } else if (index % 3 == 1) {
                body.append("Industry leaders are investing heavily in these technologies. ");
            } else {
                body.append("Research indicates promising developments on the horizon. ");
            }
            
            // Add more content to make it realistic
            body.append("Organizations must carefully consider the implications of ");
            body.append(topic).append(" on their operations, strategy, and long-term goals. ");
            body.append("This requires a thorough understanding of both technical ");
            body.append("and business perspectives.\n\n");
        }
        
        // Add a data table or list
        body.append("Key Statistics:\n");
        body.append("- Market growth: ").append(15 + (index % 20)).append("% annually\n");
        body.append("- Adoption rate: ").append(30 + (index % 40)).append("% of enterprises\n");
        body.append("- ROI potential: ").append(100 + (index % 200)).append("% over 3 years\n\n");
        
        // Closing
        body.append("In conclusion, this ").append(docType).append(" has examined ");
        body.append("the critical aspects of ").append(topic).append(". ");
        body.append("As we move forward, continued innovation and strategic ");
        body.append("implementation will be key to realizing the full potential ");
        body.append("of these technologies.");
        
        return body.toString();
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    private Timestamp timestampFromInstant(Instant instant) {
        return Timestamp.newBuilder()
            .setSeconds(instant.getEpochSecond())
            .setNanos(instant.getNano())
            .build();
    }
}