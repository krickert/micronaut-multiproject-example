package com.krickert.yappy.integration.container;

import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.model.PipeDoc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test individual module containers to verify they work correctly.
 */
@Testcontainers
public class ModuleContainerTest {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleContainerTest.class);
    
    @Container
    static GenericContainer<?> tikaParserContainer = new GenericContainer<>(DockerImageName.parse("yappy-tika-parser:1.0.0-SNAPSHOT"))
            .withExposedPorts(50051)
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("TIKA"))
            .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    @Container
    static GenericContainer<?> chunkerContainer = new GenericContainer<>(DockerImageName.parse("yappy-chunker:1.0.0-SNAPSHOT"))
            .withExposedPorts(50051)
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("CHUNKER"))
            .waitingFor(Wait.forLogMessage(".*Server Running.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));
    
    @Test
    void testTikaParserGrpcService() throws Exception {
        // Create gRPC channel to Tika parser
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(tikaParserContainer.getHost(), tikaParserContainer.getMappedPort(50051))
                .usePlaintext()
                .build();
        
        try {
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = PipeStepProcessorGrpc.newBlockingStub(channel);
            
            // Test with a simple document
            PipeDoc pipeDoc = PipeDoc.newBuilder()
                    .setId("test-doc-1")
                    .setBody("This is a test document")
                    .setSourceMimeType("text/plain")
                    .setDocumentType("test")
                    .build();
            
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(pipeDoc)
                    .build();
            
            ProcessResponse response = stub.processData(request);
            
            assertNotNull(response);
            assertTrue(response.getSuccess());
            assertTrue(response.hasOutputDoc());
            assertEquals("test-doc-1", response.getOutputDoc().getId());
            log.info("Successfully parsed document with Tika parser");
            
        } finally {
            channel.shutdown();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testChunkerContainer() {
        // For now, just verify it starts correctly
        assertTrue(chunkerContainer.isRunning(), "Chunker container should be running");
        
        // Check that it's listening on the gRPC port
        Integer mappedPort = chunkerContainer.getMappedPort(50051);
        assertNotNull(mappedPort);
        assertTrue(mappedPort > 0);
        
        log.info("Chunker container is running on port: {}", mappedPort);
    }
}