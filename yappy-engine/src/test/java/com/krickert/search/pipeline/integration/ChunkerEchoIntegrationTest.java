package com.krickert.search.pipeline.integration;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
// Ensure TestGrpcClientFactory is correctly imported or in the same package structure for tests
// import com.krickert.search.pipeline.integration.config.TestGrpcClientFactory;
import com.krickert.search.pipeline.integration.util.TestDocumentGenerator;
import com.krickert.search.sdk.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.Environment; // Micronaut Environment
import io.micronaut.context.env.PropertySource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Crucial for non-static @BeforeAll/@AfterAll with @Inject
@MicronautTest(
        environments = {"combined-test-manual-contexts"},
        startApplication = true,
        transactional = false
)
public class ChunkerEchoIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerEchoIntegrationTest.class);

    // ApplicationContexts for the services, managed by the test class instance
    private ApplicationContext chunkerServiceContext;
    private ApplicationContext echoServiceContext;

    @Inject
    @Named("chunkerClientStub")
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient;

    @Inject
    @Named("echoClientStub")
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClient;

    @Inject
    Environment mainTestEnvironment; // Now an instance field, accessible in non-static @BeforeAll

    private String pipelineName;
    private String chunkerStepName;
    private String echoStepName;
    private String streamIdBase;

    private List<PipeDoc> sampleDocuments; // Made non-static as @BeforeAll is non-static

    @BeforeAll
    void startServicesAndLoadDocuments() throws InterruptedException { // Non-static
        // Get Consul details from the main test environment (populated by Test Resources)
        String consulHost = this.mainTestEnvironment.getProperty("consul.client.host", String.class)
                .orElseThrow(() -> new IllegalStateException("Consul host not resolved from main test environment. Ensure Test Resources is providing it."));
        Integer consulPort = this.mainTestEnvironment.getProperty("consul.client.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Consul port not resolved from main test environment. Ensure Test Resources is providing it."));

        LOG.info("Using Test Resources-provided Consul for service registration at: {}:{}", consulHost, consulPort);

        // --- Start Chunker Service ---
        LOG.info("Starting Chunker service context...");
        Map<String, Object> chunkerServiceProps = new HashMap<>();
        chunkerServiceProps.put("micronaut.application.name", "chunker-service-instance");
        chunkerServiceProps.put("grpc.server.port", 0);
        chunkerServiceProps.put("grpc.services.chunker.enabled", true);
        chunkerServiceProps.put("grpc.services.echo.enabled", false);
        chunkerServiceProps.put("consul.client.host", consulHost);
        chunkerServiceProps.put("consul.client.port", consulPort);
        chunkerServiceProps.put("consul.client.registration.enabled", true);
        chunkerServiceProps.put("consul.client.registration.service-id", "chunker");
        chunkerServiceProps.put("consul.client.registration.name", "chunker");
        chunkerServiceProps.put("consul.client.registration.port", "${grpc.server.port}");
        chunkerServiceProps.put("consul.client.registration.check.enabled", false);

        ApplicationContextBuilder chunkerBuilder = ApplicationContext.builder()
                .environments("chunker-service-env")
                .propertySources(PropertySource.of(
                        "chunker-test-config",
                        chunkerServiceProps
                ));
        this.chunkerServiceContext = chunkerBuilder.build().start(); // Assign to instance field
        Integer chunkerGrpcPortResolved = this.chunkerServiceContext.getEnvironment()
                .getProperty("grpc.server.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Chunker gRPC port not resolved after startup"));
        LOG.info("Chunker service started on gRPC port: {}", chunkerGrpcPortResolved);

        // --- Start Echo Service ---
        LOG.info("Starting Echo service context...");
        Map<String, Object> echoServiceProps = new HashMap<>();
        echoServiceProps.put("micronaut.application.name", "echo-service-instance");
        echoServiceProps.put("grpc.server.port", 0);
        echoServiceProps.put("grpc.services.chunker.enabled", false);
        echoServiceProps.put("grpc.services.echo.enabled", true);
        echoServiceProps.put("consul.client.host", consulHost);
        echoServiceProps.put("consul.client.port", consulPort);
        echoServiceProps.put("consul.client.registration.enabled", true);
        echoServiceProps.put("consul.client.registration.service-id", "echo");
        echoServiceProps.put("consul.client.registration.name", "echo");
        echoServiceProps.put("consul.client.registration.port", "${grpc.server.port}");
        echoServiceProps.put("consul.client.registration.check.enabled", false);

        ApplicationContextBuilder echoBuilder = ApplicationContext.builder()
                .environments("echo-service-env")
                .propertySources(PropertySource.of(
                        "echo-test-config",
                        echoServiceProps
                ));
        this.echoServiceContext = echoBuilder.build().start(); // Assign to instance field
        Integer echoGrpcPortResolved = this.echoServiceContext.getEnvironment()
                .getProperty("grpc.server.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Echo gRPC port not resolved after startup"));
        LOG.info("Echo service started on gRPC port: {}", echoGrpcPortResolved);

        // Load documents
        LOG.info("Loading sample documents for ChunkerEchoIntegrationTest...");
        this.sampleDocuments = TestDocumentGenerator.createSampleDocuments(); // Assign to instance field
        assertTrue(this.sampleDocuments.size() > 0, "Should load at least one sample document.");
        LOG.info("Loaded {} sample documents.", this.sampleDocuments.size());

        LOG.info("Pausing for 15 seconds to allow services to fully start, register with Consul, and for discovery to pick them up...");
        TimeUnit.SECONDS.sleep(15);
    }

    @AfterAll
    void stopServices() { // Non-static
        if (this.echoServiceContext != null && this.echoServiceContext.isRunning()) {
            LOG.info("Stopping Echo service context...");
            this.echoServiceContext.stop();
        }
        if (this.chunkerServiceContext != null && this.chunkerServiceContext.isRunning()) {
            LOG.info("Stopping Chunker service context...");
            this.chunkerServiceContext.stop();
        }
        // Consul is managed by Test Resources, so we don't explicitly stop it here.
    }

    @BeforeEach
    void setUp() {
        pipelineName = "test-pipeline-combined-manual";
        chunkerStepName = "chunker-service-step";
        echoStepName = "echo-service-step";
        streamIdBase = "stream-" + UUID.randomUUID().toString();
        LOG.info("Test setup complete. Clients are injected. StreamId base: {}", streamIdBase);
    }

    // createProcessRequest method remains the same
    private ProcessRequest createProcessRequest(PipeDoc document, String stepName, Struct customConfig, String uniqueStreamIdSuffix, long hop) {
        String currentStreamId = streamIdBase + "_" + uniqueStreamIdSuffix;
        ServiceMetadata metadata = ServiceMetadata.newBuilder()
                .setPipelineName(pipelineName)
                .setPipeStepName(stepName)
                .setStreamId(currentStreamId)
                .setCurrentHopNumber(hop)
                .build();

        ProcessConfiguration.Builder configBuilder = ProcessConfiguration.newBuilder();
        if (customConfig != null) {
            configBuilder.setCustomJsonConfig(customConfig);
        }

        return ProcessRequest.newBuilder()
                .setDocument(document)
                .setMetadata(metadata)
                .setConfig(configBuilder.build())
                .build();
    }

    // testChunkerThenEcho() and testEchoServiceIndependently() methods remain the same
    // They will use the instance-injected chunkerClient and echoClient
    @Test
    @DisplayName("Test Chunker and Echo services sequentially")
    void testChunkerThenEcho() {
        PipeDoc testDoc = this.sampleDocuments.get(0); // Use instance field
        assertNotNull(testDoc, "Sample document should not be null");
        LOG.info("Testing with document ID: {}", testDoc.getId());

        LOG.info("Sending request to Chunker service...");
        Struct chunkerCustomConfig = Struct.newBuilder()
                .putFields("source_field", Value.newBuilder().setStringValue("body").build())
                .putFields("chunk_size", Value.newBuilder().setNumberValue(100).build())
                .putFields("chunk_overlap", Value.newBuilder().setNumberValue(10).build())
                .putFields("chunk_config_id", Value.newBuilder().setStringValue("test_config_100_10").build())
                .putFields("result_set_name_template", Value.newBuilder().setStringValue(chunkerStepName + "_chunks_%s").build())
                .build();

        ProcessRequest chunkerRequest = createProcessRequest(testDoc, chunkerStepName, chunkerCustomConfig, testDoc.getId() + "_chunker", 1);
        ProcessResponse chunkerResponse = null;
        try {
            chunkerResponse = chunkerClient.processData(chunkerRequest);
        } catch (Exception e) {
            fail("Call to Chunker service failed: " + e.getMessage(), e);
        }

        assertNotNull(chunkerResponse, "Chunker response should not be null");
        assertTrue(chunkerResponse.getSuccess(), "Chunker processing should be successful: " + chunkerResponse.getProcessorLogsList());
        assertTrue(chunkerResponse.hasOutputDoc(), "Chunker response should have an output document");

        PipeDoc chunkedDoc = chunkerResponse.getOutputDoc();
        if (testDoc.getBody() != null && !testDoc.getBody().isEmpty()) {
            assertTrue(chunkedDoc.getSemanticResultsCount() > 0, "Chunked document should have semantic results");
            SemanticProcessingResult chunkResult = chunkedDoc.getSemanticResults(0);
            assertTrue(chunkResult.getChunksCount() > 0, "Chunker should produce at least one chunk");
            LOG.info("Chunker produced {} chunks for doc ID: {}", chunkResult.getChunksCount(), testDoc.getId());
        } else {
            LOG.info("Input document body was empty, expecting no semantic results from chunker.");
            assertEquals(0, chunkedDoc.getSemanticResultsCount(), "Chunked document should have no semantic results for empty body");
        }

        LOG.info("Sending request to Echo service with document from Chunker...");
        ProcessRequest echoRequest = createProcessRequest(chunkedDoc, echoStepName, null, testDoc.getId() + "_echo", 2);
        ProcessResponse echoResponse = null;
        try {
            echoResponse = echoClient.processData(echoRequest);
        } catch (Exception e) {
            fail("Call to Echo service failed: " + e.getMessage(), e);
        }

        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful: " + echoResponse.getProcessorLogsList());
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        PipeDoc echoedDoc = echoResponse.getOutputDoc();
        assertEquals(chunkedDoc.getId(), echoedDoc.getId(), "Echoed document ID should match");
        assertEquals(chunkedDoc.getTitle(), echoedDoc.getTitle(), "Echoed document title should match");
        assertEquals(chunkedDoc.getBody(), echoedDoc.getBody(), "Echoed document body should match");
        assertEquals(chunkedDoc.getSemanticResultsList(), echoedDoc.getSemanticResultsList(), "Echoed document should retain semantic results from chunker");
        if (chunkedDoc.hasBlob()) {
            assertTrue(echoedDoc.hasBlob(), "Echoed document should retain blob if present");
            assertEquals(chunkedDoc.getBlob(), echoedDoc.getBlob());
        }
    }

    @Test
    @DisplayName("Test Echo service independently")
    void testEchoServiceIndependently() {
        PipeDoc testDoc = this.sampleDocuments.get(1); // Use instance field
        assertNotNull(testDoc, "Sample document for echo test should not be null");
        LOG.info("Testing Echo service independently with document ID: {}", testDoc.getId());

        ProcessRequest echoRequest = createProcessRequest(testDoc, echoStepName, null, testDoc.getId() + "_echo_ind", 1);
        ProcessResponse echoResponse = null;
        try {
            echoResponse = echoClient.processData(echoRequest);
        } catch (Exception e) {
            fail("Call to Echo service (independent) failed: " + e.getMessage(), e);
        }

        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful: " + echoResponse.getProcessorLogsList());
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        PipeDoc echoedDoc = echoResponse.getOutputDoc();
        assertEquals(testDoc.getId(), echoedDoc.getId());
        assertEquals(testDoc.getTitle(), echoedDoc.getTitle());
    }
}