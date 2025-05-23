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
}
