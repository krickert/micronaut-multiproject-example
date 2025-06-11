package com.krickert.yappy.modules.testmodule;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class TestModuleServiceTest {

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingClient;

    @Inject
    PipeStepProcessorGrpc.PipeStepProcessorStub asyncClient;

    @TempDir
    Path tempDir;

    private ProcessRequest createTestProcessRequest(
            String pipelineName, String stepName, String streamId, String docId, 
            PipeDoc document, Struct customJsonConfig) {

        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(1)
                .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customJsonConfig != null) {
            configBuilder.setCustomJsonConfig(customJsonConfig);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setConfig(configBuilder.build())
                .setMetadata(metadata)
                .build();
    }

    @Test
    @DisplayName("Should output to console by default")
    void testConsoleOutput() {
        // Prepare test data
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc-console")
                .setTitle("Console Test Document")
                .setBody("This should be output to console")
                .build();

        ProcessRequest request = createTestProcessRequest(
                "test-pipeline", "test-step", "stream-console", 
                inputDoc.getId(), inputDoc, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc());
        assertFalse(response.getProcessorLogsList().isEmpty());
        assertTrue(response.getProcessorLogs(0).contains("console"));
    }

    @Test
    @DisplayName("Should output to file when configured")
    void testFileOutput() {
        // Prepare test data with file output configuration
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc-file")
                .setTitle("File Test Document")
                .setBody("This should be output to a file")
                .build();

        Struct customConfig = Struct.newBuilder()
                .putFields("output_type", Value.newBuilder().setStringValue("FILE").build())
                .putFields("file_path", Value.newBuilder().setStringValue(tempDir.toString()).build())
                .build();

        ProcessRequest request = createTestProcessRequest(
                "test-pipeline", "test-step", "stream-file", 
                inputDoc.getId(), inputDoc, customConfig);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertEquals(inputDoc, response.getOutputDoc());
        assertFalse(response.getProcessorLogsList().isEmpty());
        
        String logMessage = response.getProcessorLogs(0);
        assertTrue(logMessage.contains("file"));
        assertTrue(logMessage.contains(tempDir.toString()));
    }

    @Test
    @DisplayName("Should handle Kafka output configuration")
    void testKafkaOutputConfiguration() {
        // Note: This test verifies configuration handling, not actual Kafka sending
        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc-kafka")
                .setTitle("Kafka Test Document")
                .build();

        Struct customConfig = Struct.newBuilder()
                .putFields("output_type", Value.newBuilder().setStringValue("KAFKA").build())
                .putFields("kafka_topic", Value.newBuilder().setStringValue("test-topic").build())
                .build();

        ProcessRequest request = createTestProcessRequest(
                "test-pipeline", "test-step", "stream-kafka", 
                inputDoc.getId(), inputDoc, customConfig);

        // Call the service - should fail gracefully if Kafka not configured
        ProcessResponse response = blockingClient.processData(request);

        assertNotNull(response);
        // Response may fail if Kafka is not configured, but should handle gracefully
        if (!response.getSuccess()) {
            assertTrue(response.hasErrorDetails());
            assertTrue(response.getErrorDetails().toString().contains("Kafka"));
        }
    }

    @Test
    @DisplayName("Should handle documents with blobs")
    void testDocumentWithBlob() {
        // Prepare test data with blob
        Blob blob = Blob.newBuilder()
                .setBlobId("test-blob-id")
                .setFilename("test.txt")
                .setData(ByteString.copyFromUtf8("Test blob content"))
                .setMimeType("text/plain")
                .build();

        PipeDoc inputDoc = PipeDoc.newBuilder()
                .setId("test-doc-blob")
                .setTitle("Document with Blob")
                .setBlob(blob)
                .build();

        ProcessRequest request = createTestProcessRequest(
                "test-pipeline", "test-step", "stream-blob", 
                inputDoc.getId(), inputDoc, null);

        // Call the service
        ProcessResponse response = blockingClient.processData(request);

        // Verify response
        assertNotNull(response);
        assertTrue(response.getSuccess());
        assertTrue(response.hasOutputDoc());
        assertTrue(response.getOutputDoc().hasBlob());
        assertEquals(blob, response.getOutputDoc().getBlob());
    }

    @Test
    @DisplayName("Should return valid service registration")
    void testGetServiceRegistration() {
        ServiceRegistrationData registration = blockingClient.getServiceRegistration(Empty.getDefaultInstance());
        
        assertNotNull(registration);
        assertEquals("test-module", registration.getModuleName());
        assertTrue(registration.hasJsonConfigSchema());
        
        String schema = registration.getJsonConfigSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("output_type"));
        assertTrue(schema.contains("CONSOLE"));
        assertTrue(schema.contains("KAFKA"));
        assertTrue(schema.contains("FILE"));
    }
}