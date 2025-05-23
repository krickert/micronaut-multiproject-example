package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import io.micronaut.context.annotation.Property;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TikaParserService.
 * Tests the service's ability to parse different document types.
 */
@MicronautTest
@Property(name = "grpc.client.plaintext", value = "true")
@Property(name = "micronaut.test.resources.enabled", value = "false")
@Property(name = "grpc.services.tika-parser.enabled", value = "true")
class TikaParserIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserIntegrationTest.class);
    private static final String TEST_DOCUMENTS_DIR = "test-documents";

    @Inject
    @GrpcChannel(GrpcServerChannel.NAME)
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    /**
     * Creates a test ProcessRequest with the given document and configuration.
     */
    private ProcessRequest createTestRequest(PipeDoc document, Struct customConfig, Map<String, String> configParams) {
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName("test-pipeline")
                .setPipeStepName("tika-parser-step")
                .setStreamId("test-stream-id")
                .setCurrentHopNumber(1)
                .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customConfig != null) {
            configBuilder.setCustomJsonConfig(customConfig);
        }
        if (configParams != null) {
            configBuilder.putAllConfigParams(configParams);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(configBuilder.build())
                .build();
    }

    /**
     * Loads a test document from the resources directory.
     */
    private byte[] loadTestDocument(String fileName) throws IOException {
        String filePath = TEST_DOCUMENTS_DIR + "/" + fileName;
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(filePath)) {
            assertNotNull(is, "Test file not found: " + filePath);
            return is.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "TXT.txt",
            "PDF.pdf",
            "file-sample_100kB.docx",
            "HTML_3KB.html"
    })
    @DisplayName("Should parse common document types")
    void testParseCommonDocumentTypes(String fileName) throws IOException {
        // Load the test document
        byte[] fileContent = loadTestDocument(fileName);

        // Create a blob with the document content
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id")
                .setFilename(fileName)
                .setData(ByteString.copyFrom(fileContent))
                .build();

        // Create a document with the blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id")
                .setBlob(blob)
                .build();

        // Create configuration
        Map<String, String> configParams = new HashMap<>();
        configParams.put("extractMetadata", "true");

        // Create the request
        ProcessRequest request = createTestRequest(document, null, configParams);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");
        assertNotNull(response.getOutputDoc().getBody(), "Document should have a body");
        assertFalse(response.getOutputDoc().getBody().isEmpty(), "Document body should not be empty");

        // The original blob should be preserved
        assertTrue(response.getOutputDoc().hasBlob(), "Document should still have the blob");
        assertEquals(blob.getBlobId(), response.getOutputDoc().getBlob().getBlobId(), "Blob ID should be preserved");

        LOG.info("Successfully parsed {}: Title='{}', Body length={}", 
                fileName, 
                response.getOutputDoc().getTitle(), 
                response.getOutputDoc().getBody().length());
    }

    @Test
    @DisplayName("Should handle document without blob gracefully")
    void testHandleDocumentWithoutBlob() {
        // Create a document without a blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id")
                .setTitle("Document with no blob")
                .setBody("This document has no blob to parse")
                .build();

        // Create the request
        ProcessRequest request = createTestRequest(document, null, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success even without a blob");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");
        assertEquals(document.getId(), response.getOutputDoc().getId(), "Document ID should be preserved");
        assertEquals(document.getTitle(), response.getOutputDoc().getTitle(), "Document title should be preserved");
        assertEquals(document.getBody(), response.getOutputDoc().getBody(), "Document body should be preserved");
        assertFalse(response.getOutputDoc().hasBlob(), "Document should still have no blob");
    }

    @Test
    @DisplayName("Should use custom configuration options")
    void testCustomConfigurationOptions() throws IOException {
        // Load a test document
        byte[] fileContent = loadTestDocument("TXT.txt");

        // Create a blob with the document content
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id")
                .setFilename("TXT.txt")
                .setData(ByteString.copyFrom(fileContent))
                .build();

        // Create a document with the blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id")
                .setBlob(blob)
                .build();

        // Create custom configuration
        Struct customConfig = Struct.newBuilder()
                .putFields("maxContentLength", Value.newBuilder().setNumberValue(10).build()) // Limit content length
                .putFields("extractMetadata", Value.newBuilder().setBoolValue(false).build()) // Don't extract metadata
                .putFields("log_prefix", Value.newBuilder().setStringValue("[CUSTOM] ").build())
                .build();

        // Create the request
        ProcessRequest request = createTestRequest(document, customConfig, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");

        // The body should be limited to 10 characters
        assertNotNull(response.getOutputDoc().getBody(), "Document should have a body");
        assertTrue(response.getOutputDoc().getBody().length() <= 10, 
                "Document body should be limited to 10 characters, but was: " + response.getOutputDoc().getBody().length());

        // Verify that at least one log message contains the custom prefix
        boolean foundCustomPrefix = false;
        for (String log : response.getProcessorLogsList()) {
            if (log.startsWith("[CUSTOM]")) {
                foundCustomPrefix = true;
                break;
            }
        }
        assertTrue(foundCustomPrefix, "At least one log message should contain the custom prefix");
    }

    @Test
    @DisplayName("Should use GeoTopicParser to extract geographic information")
    void testGeoTopicParser() throws IOException {
        // Load a test document with geographic content
        byte[] fileContent = loadTestDocument("TXT.txt");

        // Create a blob with the document content
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id-geotopic")
                .setFilename("TXT.txt")
                .setData(ByteString.copyFrom(fileContent))
                .build();

        // Create a document with the blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id-geotopic")
                .setBlob(blob)
                .build();

        // Create custom configuration with GeoTopicParser enabled
        Struct customConfig = Struct.newBuilder()
                .putFields("enableGeoTopicParser", Value.newBuilder().setBoolValue(true).build())
                .build();

        // Create the request
        ProcessRequest request = createTestRequest(document, customConfig, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");

        // Log the response for debugging
        LOG.info("GeoTopicParser test response: {}", response);

        // The GeoTopicParser should have processed the document
        assertNotNull(response.getOutputDoc().getBody(), "Document should have a body");
        assertFalse(response.getOutputDoc().getBody().isEmpty(), "Document body should not be empty");

        LOG.info("Successfully processed document with GeoTopicParser");
    }

    @Test
    @DisplayName("Should extract metadata and store in custom_data")
    void testMetadataExtraction() throws IOException {
        // Load a test document
        byte[] fileContent = loadTestDocument("PDF.pdf");

        // Create a blob with the document content
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id-metadata")
                .setFilename("PDF.pdf")
                .setData(ByteString.copyFrom(fileContent))
                .build();

        // Create a document with the blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id-metadata")
                .setBlob(blob)
                .build();

        // Create custom configuration with metadata mapping rules
        Struct mappersStruct = Struct.newBuilder()
                // Keep Content-Type as is
                .putFields("Content-Type", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("operation", Value.newBuilder().setStringValue("KEEP").build())
                        .build()).build())
                // Copy dc:title to document_title
                .putFields("dc:title", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("operation", Value.newBuilder().setStringValue("COPY").build())
                        .putFields("destination", Value.newBuilder().setStringValue("document_title").build())
                        .build()).build())
                // Apply regex to Content-Length
                .putFields("Content-Length", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("operation", Value.newBuilder().setStringValue("REGEX").build())
                        .putFields("destination", Value.newBuilder().setStringValue("size_in_bytes").build())
                        .putFields("pattern", Value.newBuilder().setStringValue("(\\d+)").build())
                        .putFields("replacement", Value.newBuilder().setStringValue("$1").build())
                        .build()).build())
                .build();

        Struct customConfig = Struct.newBuilder()
                .putFields("extractMetadata", Value.newBuilder().setBoolValue(true).build())
                .putFields("metadata_field_name", Value.newBuilder().setStringValue("document_metadata").build())
                .putFields("mappers", Value.newBuilder().setStructValue(mappersStruct).build())
                .build();

        // Create the request
        ProcessRequest request = createTestRequest(document, customConfig, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");
        assertTrue(response.getOutputDoc().hasCustomData(), "Output document should have custom_data");

        // Verify custom_data contains the document_metadata field
        Struct customData = response.getOutputDoc().getCustomData();
        assertTrue(customData.containsFields("document_metadata"), 
                "custom_data should contain document_metadata field");

        // Verify document_metadata is a Struct
        Value metadataValue = customData.getFieldsOrDefault("document_metadata", null);
        assertNotNull(metadataValue, "document_metadata value should not be null");
        assertTrue(metadataValue.hasStructValue(), "document_metadata should be a Struct");

        // Verify the Struct contains at least one field
        Struct metadataStruct = metadataValue.getStructValue();
        assertTrue(metadataStruct.getFieldsCount() > 0, 
                "Metadata should contain at least one field");

        // Log the metadata fields for debugging
        LOG.info("Metadata fields:");
        for (Map.Entry<String, Value> entry : metadataStruct.getFieldsMap().entrySet()) {
            LOG.info("  {} = {}", entry.getKey(), 
                    entry.getValue().hasStringValue() ? entry.getValue().getStringValue() : entry.getValue());
        }

        LOG.info("Successfully extracted metadata from PDF and stored in custom_data");
    }

    @Test
    @DisplayName("Should use new schema structure with nested options")
    void testNewSchemaStructure() throws IOException {
        // Load a test document
        byte[] fileContent = loadTestDocument("PDF.pdf");

        // Create a blob with the document content
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id-new-schema")
                .setFilename("PDF.pdf")
                .setData(ByteString.copyFrom(fileContent))
                .build();

        // Create a document with the blob
        PipeDoc document = PipeDoc.newBuilder()
                .setId("test-doc-id-new-schema")
                .setBlob(blob)
                .build();

        // Create mappers configuration
        Struct mappersStruct = Struct.newBuilder()
                // Keep Content-Type as is
                .putFields("Content-Type", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("operation", Value.newBuilder().setStringValue("KEEP").build())
                        .build()).build())
                // Copy dc:title to document_title
                .putFields("dc:title", Value.newBuilder().setStructValue(Struct.newBuilder()
                        .putFields("operation", Value.newBuilder().setStringValue("COPY").build())
                        .putFields("destination", Value.newBuilder().setStringValue("document_title").build())
                        .build()).build())
                .build();

        // Create processing options
        Struct processingOptionsStruct = Struct.newBuilder()
                .putFields("metadata_field_name", Value.newBuilder().setStringValue("nested_metadata").build())
                .putFields("mappers", Value.newBuilder().setStructValue(mappersStruct).build())
                .build();

        // Create parsing options
        Struct parsingOptionsStruct = Struct.newBuilder()
                .putFields("maxContentLength", Value.newBuilder().setNumberValue(1000).build())
                .putFields("extractMetadata", Value.newBuilder().setBoolValue(true).build())
                .build();

        // Create features array
        com.google.protobuf.ListValue.Builder featuresListBuilder = com.google.protobuf.ListValue.newBuilder();
        featuresListBuilder.addValues(Value.newBuilder().setStringValue("GEO_TOPIC_PARSER").build());

        // Create custom configuration with the new schema structure
        Struct customConfig = Struct.newBuilder()
                .putFields("parsingOptions", Value.newBuilder().setStructValue(parsingOptionsStruct).build())
                .putFields("processingOptions", Value.newBuilder().setStructValue(processingOptionsStruct).build())
                .putFields("features", Value.newBuilder().setListValue(featuresListBuilder.build()).build())
                .putFields("log_prefix", Value.newBuilder().setStringValue("[NEW_SCHEMA] ").build())
                .build();

        // Create the request
        ProcessRequest request = createTestRequest(document, customConfig, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify the response
        assertTrue(response.getSuccess(), "Response should indicate success");
        assertNotNull(response.getOutputDoc(), "Response should contain a document");

        // Verify the body is limited to 1000 characters
        assertNotNull(response.getOutputDoc().getBody(), "Document should have a body");
        assertTrue(response.getOutputDoc().getBody().length() <= 1000, 
                "Document body should be limited to 1000 characters, but was: " + response.getOutputDoc().getBody().length());

        // Verify custom_data contains the nested_metadata field
        assertTrue(response.getOutputDoc().hasCustomData(), "Output document should have custom_data");
        Struct customData = response.getOutputDoc().getCustomData();
        assertTrue(customData.containsFields("nested_metadata"), 
                "custom_data should contain nested_metadata field");

        // Verify that at least one log message contains the custom prefix
        boolean foundCustomPrefix = false;
        for (String log : response.getProcessorLogsList()) {
            if (log.startsWith("[NEW_SCHEMA]")) {
                foundCustomPrefix = true;
                break;
            }
        }
        assertTrue(foundCustomPrefix, "At least one log message should contain the custom prefix");

        LOG.info("Successfully processed document with new schema structure");
    }
}
