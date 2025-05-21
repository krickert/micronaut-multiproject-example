package com.krickert.search.pipeline.integration;

import com.google.common.collect.Maps;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.integration.util.TestDocumentGenerator;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(
        environments = {"combined-test-manual-contexts"},
        startApplication = true,
        transactional = false
)
public class ChunkerEchoIntegrationTest implements TestPropertyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ChunkerEchoIntegrationTest.class);

    private ApplicationContext chunkerServiceContext;
    private ApplicationContext echoServiceContext;
    private final Map<String, String> resolvedModulePorts = new HashMap<>();

    @Inject
    GrpcChannelManager grpcChannelManager;

    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub chunkerClient;
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub echoClient;

    private String pipelineName;
    private String chunkerStepName;
    private String echoStepName;
    private String streamIdBase;
    private List<PipeDoc> sampleDocuments;

    @Override
    public @NonNull Map<String, String> getProperties() {
        LOG.info("================ TestPropertyProvider.getProperties() called - START ================");
        stopModuleContexts();

        Map<String, String> providedProperties = Maps.newHashMap();

        // --- Start Chunker Service ---
        String chunkerAppName = "chunker-module-" + UUID.randomUUID().toString().substring(0, 8);
        LOG.info("TestPropertyProvider: Starting Chunker service context (App Name: {})...", chunkerAppName);
        Map<String, Object> chunkerServiceProps = new HashMap<>();
        chunkerServiceProps.put("micronaut.application.name", chunkerAppName);
        chunkerServiceProps.put("grpc.server.port", "${random.port}");
        chunkerServiceProps.put("grpc.services.chunker.enabled", true);
        chunkerServiceProps.put("grpc.services.echo.enabled", false);

        // Explicitly disable ALL Consul client interactions for this module context
        chunkerServiceProps.put("consul.client.enabled", false); // Disables the Consul client entirely for this context
        // The following are more specific but consul.client.enabled=false should cover them
        // chunkerServiceProps.put("consul.client.registration.enabled", false);
        // chunkerServiceProps.put("consul.client.discovery.enabled", false);
        // chunkerServiceProps.put("consul.client.config.enabled", false);


        ApplicationContextBuilder chunkerBuilder = ApplicationContext.builder()
                .environments("chunker-module-env", "test")
                .propertySources(PropertySource.of("chunker-module-config", chunkerServiceProps));
        try {
            this.chunkerServiceContext = chunkerBuilder.build().start();
            LOG.info("TestPropertyProvider: Chunker module context (App: {}) started successfully.", chunkerAppName);

            // Verify gRPC server bean within the module context
            try {
                io.micronaut.grpc.server.GrpcEmbeddedServer chunkerGrpcServer =
                        this.chunkerServiceContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
                LOG.info("TestPropertyProvider: Chunker Module's Internal GrpcEmbeddedServer: isRunning={}, Port={}",
                        chunkerGrpcServer.isRunning(), chunkerGrpcServer.getPort());
            } catch (Exception e) {
                LOG.warn("TestPropertyProvider: Could not get GrpcEmbeddedServer bean from Chunker module context or check its status.", e);
            }

        } catch (Exception e) {
            LOG.error("!!! EXCEPTION during ChunkerServiceContext startup in getProperties for App: {} !!!", chunkerAppName, e);
            throw new RuntimeException("Failed to start Chunker module context", e);
        }

        Integer chunkerGrpcPortResolved = this.chunkerServiceContext.getEnvironment()
                .getProperty("grpc.server.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Chunker gRPC port not resolved after startup"));
        assertNotNull(chunkerGrpcPortResolved, "Resolved Chunker port must not be null");
        assertTrue(chunkerGrpcPortResolved > 0, "Resolved Chunker port must be > 0, was: " + chunkerGrpcPortResolved);
        LOG.info("TestPropertyProvider: Chunker module (App: {}) resolved gRPC port: {}", chunkerAppName, chunkerGrpcPortResolved);
        providedProperties.put("local.services.ports.chunker", chunkerGrpcPortResolved.toString());
        resolvedModulePorts.put("chunker", chunkerGrpcPortResolved.toString());


        // --- Start Echo Service ---
        String echoAppName = "echo-module-" + UUID.randomUUID().toString().substring(0, 8);
        LOG.info("TestPropertyProvider: Starting Echo service context (App Name: {})...", echoAppName);
        Map<String, Object> echoServiceProps = new HashMap<>();
        echoServiceProps.put("micronaut.application.name", echoAppName);
        echoServiceProps.put("grpc.server.port", "${random.port}");
        echoServiceProps.put("grpc.services.chunker.enabled", false);
        echoServiceProps.put("grpc.services.echo.enabled", true);

        // Explicitly disable ALL Consul client interactions for this module context
        echoServiceProps.put("consul.client.enabled", false); // Disables the Consul client entirely for this context

        ApplicationContextBuilder echoBuilder = ApplicationContext.builder()
                .environments("echo-module-env", "test")
                .propertySources(PropertySource.of("echo-module-config", echoServiceProps));
        try {
            this.echoServiceContext = echoBuilder.build().start();
            LOG.info("TestPropertyProvider: Echo module context (App: {}) started successfully.", echoAppName);

            // Verify gRPC server bean within the module context
            try {
                io.micronaut.grpc.server.GrpcEmbeddedServer echoGrpcServer =
                        this.echoServiceContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
                LOG.info("TestPropertyProvider: Echo Module's Internal GrpcEmbeddedServer: isRunning={}, Port={}",
                        echoGrpcServer.isRunning(), echoGrpcServer.getPort());
            } catch (Exception e) {
                LOG.warn("TestPropertyProvider: Could not get GrpcEmbeddedServer bean from Echo module context or check its status.", e);
            }

        } catch (Exception e) {
            LOG.error("!!! EXCEPTION during EchoServiceContext startup in getProperties for App: {} !!!", echoAppName, e);
            throw new RuntimeException("Failed to start Echo module context", e);
        }

        Integer echoGrpcPortResolved = this.echoServiceContext.getEnvironment()
                .getProperty("grpc.server.port", Integer.class)
                .orElseThrow(() -> new IllegalStateException("Echo gRPC port not resolved after startup"));
        assertNotNull(echoGrpcPortResolved, "Resolved Echo port must not be null");
        assertTrue(echoGrpcPortResolved > 0, "Resolved Echo port must be > 0, was: " + echoGrpcPortResolved);
        LOG.info("TestPropertyProvider: Echo module (App: {}) resolved gRPC port: {}", echoAppName, echoGrpcPortResolved);
        providedProperties.put("local.services.ports.echo", echoGrpcPortResolved.toString());
        resolvedModulePorts.put("echo", echoGrpcPortResolved.toString());

        LOG.info("TestPropertyProvider: Providing properties to main context: {}", providedProperties);
        LOG.info("================ TestPropertyProvider.getProperties() finished ================");
        return providedProperties;
    }

    @BeforeAll
    void setupTestDataAndPause(TestInfo testInfo) {
        LOG.info("================ @BeforeAll for {} - START ================", testInfo.getDisplayName());
        LOG.info("@BeforeAll: Loading sample documents...");
        this.sampleDocuments = TestDocumentGenerator.createSampleDocuments();
        assertTrue(this.sampleDocuments.size() > 0, "Should load at least one sample document.");
        LOG.info("@BeforeAll: Loaded {} sample documents.", this.sampleDocuments.size());

        LOG.info("@BeforeAll: Ports resolved by TestPropertyProvider: Chunker={}, Echo={}",
                resolvedModulePorts.get("chunker"), resolvedModulePorts.get("echo"));
        LOG.info("@BeforeAll: Main test context GrpcChannelManager instance hash: {}", System.identityHashCode(grpcChannelManager));

        logModuleContextStatus("@BeforeAll (after main context start, before pause)");

        try {
            LOG.info("@BeforeAll: Pausing for 3 seconds to ensure all components are stable...");
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during pause", e);
        }
        logModuleContextStatus("@BeforeAll (after pause)");
        LOG.info("================ @BeforeAll for {} - FINISHED ================", testInfo.getDisplayName());
    }

    @BeforeEach
    void setUpClientsAndTestParams(TestInfo testInfo) {
        LOG.info("---------------- @BeforeEach for {} - START ----------------", testInfo.getDisplayName());
        LOG.info("@BeforeEach: Main test context GrpcChannelManager instance hash: {}", System.identityHashCode(grpcChannelManager));

        pipelineName = "test-pipeline-combined-manual";
        chunkerStepName = "chunker-service-step";
        echoStepName = "echo-service-step";
        streamIdBase = "stream-" + UUID.randomUUID().toString();

        logModuleContextStatus("@BeforeEach (before getting channels)");

        LOG.info("@BeforeEach: Getting channel for 'chunker' from GrpcChannelManager (expected port: {})...", resolvedModulePorts.get("chunker"));
        ManagedChannel chunkerModuleChannel = grpcChannelManager.getChannel("chunker");
        LOG.info("@BeforeEach: Got chunker channel: {}, authority: {}",
                chunkerModuleChannel,
                chunkerModuleChannel != null ? chunkerModuleChannel.authority() : "null channel");
        assertNotNull(chunkerModuleChannel, "Chunker module channel should not be null in setUp");
        this.chunkerClient = PipeStepProcessorGrpc.newBlockingStub(chunkerModuleChannel);

        LOG.info("@BeforeEach: Getting channel for 'echo' from GrpcChannelManager (expected port: {})...", resolvedModulePorts.get("echo"));
        ManagedChannel echoModuleChannel = grpcChannelManager.getChannel("echo");
        LOG.info("@BeforeEach: Got echo channel: {}, authority: {}",
                echoModuleChannel,
                echoModuleChannel != null ? echoModuleChannel.authority() : "null channel");
        assertNotNull(echoModuleChannel, "Echo module channel should not be null in setUp");
        this.echoClient = PipeStepProcessorGrpc.newBlockingStub(echoModuleChannel);

        LOG.info("@BeforeEach: Test setup complete. Clients initialized. StreamId base: {}", streamIdBase);
        assertNotNull(chunkerClient, "Chunker client should be initialized");
        assertNotNull(echoClient, "Echo client should be initialized");
        assertNotNull(sampleDocuments, "Sample documents should be loaded");
        LOG.info("---------------- @BeforeEach for {} - FINISHED ----------------", testInfo.getDisplayName());
    }

    @AfterEach
    void logAfterEach(TestInfo testInfo) {
        LOG.info("---------------- @AfterEach for {} - START ----------------", testInfo.getDisplayName());
        logModuleContextStatus("@AfterEach");
        LOG.info("---------------- @AfterEach for {} - FINISHED ----------------", testInfo.getDisplayName());
    }

    @AfterAll
    void stopServicesAndContexts(TestInfo testInfo) {
        LOG.info("================ @AfterAll for {} - START ================", testInfo.getDisplayName());
        stopModuleContexts();
        LOG.info("@AfterAll: Main test application context will be stopped by Micronaut Test framework.");
        LOG.info("================ @AfterAll for {} - FINISHED ================", testInfo.getDisplayName());
    }

    private void stopModuleContexts() {
        LOG.info("Attempting to stop module contexts...");
        if (this.echoServiceContext != null) {
            String port = this.echoServiceContext.getEnvironment().getProperty("grpc.server.port", String.class, "N/A");
            String appName = this.echoServiceContext.getEnvironment().getProperty("micronaut.application.name", String.class, "N/A");
            if (this.echoServiceContext.isRunning()) {
                LOG.info("Stopping Echo module service context (App: {}, Port: {})...", appName, port);
                this.echoServiceContext.stop();
                LOG.info("Echo module service context (App: {}, Port: {}) stopped.", appName, port);
            } else {
                LOG.info("Echo module service context (App: {}, Port: {}) was already stopped or not running.", appName, port);
            }
            this.echoServiceContext = null;
        } else {
            LOG.info("Echo module service context was already null.");
        }

        if (this.chunkerServiceContext != null) {
            String port = this.chunkerServiceContext.getEnvironment().getProperty("grpc.server.port", String.class, "N/A");
            String appName = this.chunkerServiceContext.getEnvironment().getProperty("micronaut.application.name", String.class, "N/A");
            if (this.chunkerServiceContext.isRunning()) {
                LOG.info("Stopping Chunker module service context (App: {}, Port: {})...", appName, port);
                this.chunkerServiceContext.stop();
                LOG.info("Chunker module service context (App: {}, Port: {}) stopped.", appName, port);
            } else {
                LOG.info("Chunker module service context (App: {}, Port: {}) was already stopped or not running.", appName, port);
            }
            this.chunkerServiceContext = null;
        } else {
            LOG.info("Chunker module service context was already null.");
        }
        LOG.info("Finished attempting to stop module contexts.");
    }

    private void logModuleContextStatus(String phase) {
        LOG.info("--- Module Context Status Check at Phase: {} ---", phase);
        if (chunkerServiceContext != null) {
            LOG.info("  Chunker Context ({}): isRunning={}, Port={}, AppName={}",
                    System.identityHashCode(chunkerServiceContext),
                    chunkerServiceContext.isRunning(),
                    chunkerServiceContext.getEnvironment().getProperty("grpc.server.port", Integer.class).orElse(-999),
                    chunkerServiceContext.getEnvironment().getProperty("micronaut.application.name", String.class).orElse("N/A"));
            // Attempt to log internal gRPC server status if context is running
            if (chunkerServiceContext.isRunning()) {
                try {
                    io.micronaut.grpc.server.GrpcEmbeddedServer server = chunkerServiceContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
                    LOG.info("    Chunker Internal GrpcEmbeddedServer: isRunning={}, Port={}", server.isRunning(), server.getPort());
                } catch (Exception e) {
                    LOG.warn("    Chunker Internal GrpcEmbeddedServer: Could not retrieve or check status.", e.getMessage());
                }
            }
        } else {
            LOG.warn("  {}: Chunker service context is NULL!", phase);
        }
        if (echoServiceContext != null) {
            LOG.info("  Echo Context    ({}): isRunning={}, Port={}, AppName={}",
                    System.identityHashCode(echoServiceContext),
                    echoServiceContext.isRunning(),
                    echoServiceContext.getEnvironment().getProperty("grpc.server.port", Integer.class).orElse(-999),
                    echoServiceContext.getEnvironment().getProperty("micronaut.application.name", String.class).orElse("N/A"));
            if (echoServiceContext.isRunning()) {
                try {
                    io.micronaut.grpc.server.GrpcEmbeddedServer server = echoServiceContext.getBean(io.micronaut.grpc.server.GrpcEmbeddedServer.class);
                    LOG.info("    Echo Internal GrpcEmbeddedServer: isRunning={}, Port={}", server.isRunning(), server.getPort());
                } catch (Exception e) {
                    LOG.warn("    Echo Internal GrpcEmbeddedServer: Could not retrieve or check status.", e.getMessage());
                }
            }
        } else {
            LOG.warn("  {}: Echo service context is NULL!", phase);
        }
        LOG.info("--- End Module Context Status Check ---");
    }

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

    @Test
    @DisplayName("Test Chunker and Echo services sequentially")
    void testChunkerThenEcho(TestInfo testInfo) {
        LOG.info(">>>>>>>>>> TEST: {} - START >>>>>>>>>>", testInfo.getDisplayName());
        PipeDoc testDoc = this.sampleDocuments.getFirst();
        assertNotNull(testDoc, "Sample document should not be null");
        LOG.info("Testing with document ID: {}", testDoc.getId());

        logModuleContextStatus("testChunkerThenEcho (before Chunker call)");
        assertNotNull(chunkerServiceContext, "Chunker context should not be null before call");
        assertTrue(chunkerServiceContext.isRunning(), "Chunker context should be running before call. Port: " + resolvedModulePorts.get("chunker"));

        LOG.info("Sending request to Chunker service (module directly) via step name for metadata: {}", chunkerStepName);
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
            LOG.info("testChunkerThenEcho: About to call chunkerClient.processData() targeting authority: {}",
                    chunkerClient.getChannel().authority());
            chunkerResponse = chunkerClient.processData(chunkerRequest);
            LOG.info("testChunkerThenEcho: Successfully called chunkerClient.processData()");
        } catch (Exception e) {
            LOG.error("testChunkerThenEcho: Error calling Chunker service step", e);
            fail("Call to Chunker service step failed: " + e.getMessage(), e);
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

        logModuleContextStatus("testChunkerThenEcho (before Echo call)");
        assertNotNull(echoServiceContext, "Echo context should not be null before call");
        assertTrue(echoServiceContext.isRunning(), "Echo context should be running before call. Port: " + resolvedModulePorts.get("echo"));

        LOG.info("Sending request to Echo service (module directly) via step name for metadata: {} with document from Chunker...", echoStepName);
        ProcessRequest echoRequest = createProcessRequest(chunkedDoc, echoStepName, null, testDoc.getId() + "_echo", 2);
        ProcessResponse echoResponse = null;
        try {
            LOG.info("testChunkerThenEcho: About to call echoClient.processData() targeting authority: {}",
                     echoClient.getChannel().authority());
            echoResponse = echoClient.processData(echoRequest);
            LOG.info("testChunkerThenEcho: Successfully called echoClient.processData()");
        } catch (Exception e) {
            LOG.error("testChunkerThenEcho: Error calling Echo service step", e);
            fail("Call to Echo service step failed: " + e.getMessage(), e);
        }

        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful: " + echoResponse.getProcessorLogsList());
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");

        PipeDoc echoedDoc = echoResponse.getOutputDoc();
        assertEquals(chunkedDoc.getId(), echoedDoc.getId(), "Echoed document ID should match");
        LOG.info("<<<<<<<<<< TEST: {} - FINISHED <<<<<<<<<<", testInfo.getDisplayName());
    }

    @Test
    @DisplayName("Test Echo service independently")
    void testEchoServiceIndependently(TestInfo testInfo) {
        LOG.info(">>>>>>>>>> TEST: {} - START >>>>>>>>>>", testInfo.getDisplayName());
        PipeDoc testDoc = this.sampleDocuments.get(1);
        assertNotNull(testDoc, "Sample document for echo test should not be null");

        logModuleContextStatus("testEchoServiceIndependently (before Echo call)");
        assertNotNull(echoServiceContext, "Echo context should not be null before call");
        assertTrue(echoServiceContext.isRunning(), "Echo context should be running before call. Port: " + resolvedModulePorts.get("echo"));

        LOG.info("Testing Echo service independently (module directly) with document ID: {}", testDoc.getId());

        ProcessRequest echoRequest = createProcessRequest(testDoc, echoStepName, null, testDoc.getId() + "_echo_ind", 1);
        ProcessResponse echoResponse = null;
        try {
            LOG.info("testEchoServiceIndependently: About to call echoClient.processData() targeting authority: {}",
                    echoClient.getChannel().authority());
            echoResponse = echoClient.processData(echoRequest);
            LOG.info("testEchoServiceIndependently: Successfully called echoClient.processData()");
        } catch (Exception e) {
            LOG.error("testEchoServiceIndependently: Error calling Echo service (independent)", e);
            fail("Call to Echo service (independent) failed: " + e.getMessage(), e);
        }

        assertNotNull(echoResponse, "Echo response should not be null");
        assertTrue(echoResponse.getSuccess(), "Echo processing should be successful: " + echoResponse.getProcessorLogsList());
        assertTrue(echoResponse.hasOutputDoc(), "Echo response should have an output document");
        assertEquals(testDoc.getId(), echoResponse.getOutputDoc().getId(), "Echoed document ID should match input");
        LOG.info("<<<<<<<<<< TEST: {} - FINISHED <<<<<<<<<<", testInfo.getDisplayName());
    }
}