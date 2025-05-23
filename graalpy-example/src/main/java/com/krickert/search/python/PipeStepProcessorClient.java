package com.krickert.search.python;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A client for testing the PipeStepProcessor service.
 * This class will send a test request to the Python PipeStepProcessor service
 * after a delay to allow the service to start.
 */
@Singleton
@Context // Eager initialization
public class PipeStepProcessorClient implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStepProcessorClient.class);
    private static final int DELAY_SECONDS = 5; // Wait for the server to start

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        LOG.info("Application started, will test PipeStepProcessor service after {} seconds...", DELAY_SECONDS);

        // Wait a bit for the server to start
        new Thread(() -> {
            try {
                Thread.sleep(DELAY_SECONDS * 1000);
                testPipeStepProcessor();
            } catch (InterruptedException e) {
                LOG.error("Interrupted while waiting to test PipeStepProcessor", e);
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Tests the PipeStepProcessor service by sending a request and logging the response.
     */
    private void testPipeStepProcessor() {
        LOG.info("Testing PipeStepProcessor service...");

        // Create a channel to the server
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 50062)
                .usePlaintext()
                .build();

        try {
            // Create a blocking stub
            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub blockingStub = 
                    PipeStepProcessorGrpc.newBlockingStub(channel);

            // Create a test document
            PipeDoc testDoc = PipeDoc.newBuilder()
                    .setId(UUID.randomUUID().toString())
                    .setTitle("Test Document")
                    .setBody("This is a test document for the PipeStepProcessor service.")
                    .build();

            // Create metadata
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipelineName("test-pipeline")
                    .setPipeStepName("test-step")
                    .setStreamId(UUID.randomUUID().toString())
                    .setCurrentHopNumber(1)
                    .build();

            // Create configuration
            ProcessConfiguration config = ProcessConfiguration.newBuilder()
                    .putConfigParams("test-param", "test-value")
                    .build();

            // Create the request
            ProcessRequest request = ProcessRequest.newBuilder()
                    .setDocument(testDoc)
                    .setMetadata(metadata)
                    .setConfig(config)
                    .build();

            // Send the request
            LOG.info("Sending request to PipeStepProcessor service...");
            ProcessResponse response = blockingStub.processData(request);

            // Log the response
            LOG.info("Received response from PipeStepProcessor service:");
            LOG.info("Success: {}", response.getSuccess());
            if (response.hasOutputDoc()) {
                LOG.info("Output document ID: {}", response.getOutputDoc().getId());
                LOG.info("Output document title: {}", response.getOutputDoc().getTitle());
                LOG.info("Output document body: {}", response.getOutputDoc().getBody());
                LOG.info("Output document keywords: {}", response.getOutputDoc().getKeywordsList());
            }
            LOG.info("Processor logs: {}", response.getProcessorLogsList());

        } catch (Exception e) {
            LOG.error("Error testing PipeStepProcessor service", e);
        } finally {
            // Shutdown the channel
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.error("Error shutting down channel", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
