package com.krickert.search.pipeline.integration;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.pipeline.integration.util.TestDocumentGenerator;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.yappy.modules.echo.EchoService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Echo service.
 * This test verifies that the Echo service works correctly in an integration environment.
 * It manually creates and manages its gRPC client channel to connect directly to the
 * {@code EchoService} running within this test's own Micronaut application context.
 * This approach enhances test isolation and avoids flakiness that can arise from
 * shared service discovery mechanisms (like Consul) when running in a larger test suite.
 */
@MicronautTest(environments = {"test-echo-grpc-working-standalone"}, startApplication = true)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class EchoServiceIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(EchoServiceIntegrationTest.class);

    @Inject
    EchoService echoService;

    @Inject
    @Named("echoClientStub")
    private PipeStepProcessorGrpc.PipeStepProcessorBlockingStub localBlockingClient;

    @BeforeEach
    void setup() {
        checkNotNull(echoService, "echoService cannot be null");
    }


    /**
     * Test that the Echo service correctly echoes back a document.
     * This test creates a sample document, sends it to the Echo service,
     * and verifies that the service returns the same document.
     */
    @Test
    @DisplayName("Echo service should echo back the document")
    public void testEchoService() {
        // Create a sample document
        String docId = "test-doc-" + UUID.randomUUID();
        String title = "Test Document";
        String body = "This is a test document for the Echo service integration test.";
        Map<String, String> customData = new HashMap<>();
        customData.put("test-key", "test-value");
        PipeDoc document = TestDocumentGenerator.createSampleDocument(docId, title, body, customData, true);

        // Create a process request
        String pipelineName = "test-pipeline";
        String stepName = "echo-step";
        String streamId = "test-stream-" + UUID.randomUUID();
        ProcessRequest request = createProcessRequest(document, pipelineName, stepName, streamId);

        // Send the request to the Echo service
        LOG.info("Sending request to Echo service: {}", request);
        ProcessResponse response = localBlockingClient.processData(request);
        LOG.info("Received response from Echo service: {}", response);

        // Verify the response
        assertNotNull(response, "Response should not be null");
        assertTrue(response.getSuccess(), "Processing should be successful");
        assertTrue(response.hasOutputDoc(), "Response should have an output document");

        // Verify that the output document is the same as the input document
        PipeDoc outputDoc = response.getOutputDoc();
        assertEquals(document.getId(), outputDoc.getId(), "Document ID should match");
        assertEquals(document.getTitle(), outputDoc.getTitle(), "Document title should match");
        assertEquals(document.getBody(), outputDoc.getBody(), "Document body should match");
        assertEquals(document.getCustomData(), outputDoc.getCustomData(), "Document custom data should match");

        // Verify that the blob is present and correct
        assertTrue(outputDoc.hasBlob(), "Output document should have a blob");
        assertEquals(document.getBlob().getBlobId(), outputDoc.getBlob().getBlobId(), "Blob ID should match");
        assertEquals(document.getBlob().getFilename(), outputDoc.getBlob().getFilename(), "Blob filename should match");
        assertEquals(document.getBlob().getData(), outputDoc.getBlob().getData(), "Blob data should match");
        assertEquals(document.getBlob().getMimeType(), outputDoc.getBlob().getMimeType(), "Blob MIME type should match");

        // Verify that the processor logs are not empty
        assertFalse(response.getProcessorLogsList().isEmpty(), "Processor logs should not be empty");

        // Verify that the processor log contains the expected message
        String expectedLogMessagePart = String.format("EchoService (Unary) successfully processed step '%s' for pipeline '%s'", 
                stepName, pipelineName);
        assertTrue(response.getProcessorLogs(0).contains(expectedLogMessagePart),
                "Processor log should contain: " + expectedLogMessagePart);
    }

    /**
     * Helper method to create a ProcessRequest for the specified document and metadata.
     */
    private ProcessRequest createProcessRequest(PipeDoc document, String pipelineName, String stepName, String streamId) {
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(streamId)
                .setCurrentHopNumber(0)
                .build();

        ProcessConfiguration config = ProcessConfiguration.newBuilder().build();

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(config)
                .build();
    }
}
