package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.consul.util.ConsulTestUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.KeyValueClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
public class PipelineConcurrencyTest implements TestPropertyProvider {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ConsulKvService consulKvService;

    @Inject
    KeyValueClient keyValueClient;

    @Inject
    ConsulTestUtils consulTestUtils;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");
        properties.put("consul.client.registration.enabled", "true");
        // Disable data seeding for tests
        properties.put("consul.data.seeding.enabled", "false");
        properties.put("consul.client.watch.enabled", "false");
        return properties;
    }

    private String configPath;

    @BeforeEach
    void setUp() {
        // Clear any existing pipeline configurations
        configPath = consulKvService.getFullPath("pipeline.configs");
        System.out.println("[DEBUG_LOG] Setting up test - clearing Consul state at: " + configPath);
        keyValueClient.deleteKeys(configPath);

        // Verify that keys were actually deleted
        try {
            java.util.List<String> remainingKeys = keyValueClient.getKeys(configPath);
            if (remainingKeys != null && !remainingKeys.isEmpty()) {
                System.out.println("[DEBUG_LOG] WARNING: Keys still exist after initial cleanup: " + remainingKeys);
                // Try one more time
                keyValueClient.deleteKeys(configPath);
            } else {
                System.out.println("[DEBUG_LOG] Verified no keys exist before test starts");
            }
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] Error checking keys before test: " + e.getMessage());
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up after the test to ensure a clean state for the next run
        if (configPath != null) {
            System.out.println("[DEBUG_LOG] Cleaning up Consul state after test");
            boolean success = consulTestUtils.resetConsulState(configPath);
            System.out.println("[DEBUG_LOG] Consul cleanup success: " + success);

            // Double-check that cleanup was successful
            try {
                java.util.List<String> remainingKeys = keyValueClient.getKeys(configPath);
                if (remainingKeys != null && !remainingKeys.isEmpty()) {
                    System.out.println("[DEBUG_LOG] WARNING: Keys still exist after cleanup: " + remainingKeys);
                    // Force delete one more time
                    keyValueClient.deleteKeys(configPath);
                } else {
                    System.out.println("[DEBUG_LOG] Verified no keys remain after cleanup");
                }
            } catch (Exception e) {
                System.err.println("[DEBUG_LOG] Error checking remaining keys: " + e.getMessage());
            }
        }
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        // Create a new pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("concurrent-test-pipeline");

        System.out.println("[DEBUG_LOG] Creating new pipeline: concurrent-test-pipeline");
        HttpResponse<PipelineConfigDto> createResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, createResponse.status());
        PipelineConfigDto pipeline = createResponse.body();
        assertNotNull(pipeline);
        assertEquals("concurrent-test-pipeline", pipeline.getName());
        assertEquals(1, pipeline.getPipelineVersion());
        System.out.println("[DEBUG_LOG] Pipeline created successfully with version: " + pipeline.getPipelineVersion());

        // Get the pipeline to ensure we have the latest version
        System.out.println("[DEBUG_LOG] Getting pipeline to ensure latest version");
        HttpResponse<PipelineConfigDto> getResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/concurrent-test-pipeline"),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, getResponse.status());
        PipelineConfigDto latestPipeline = getResponse.body();
        assertNotNull(latestPipeline);
        System.out.println("[DEBUG_LOG] Retrieved pipeline with version: " + latestPipeline.getPipelineVersion());

        // Create multiple threads to update the pipeline concurrently
        int numThreads = 5;
        System.out.println("[DEBUG_LOG] Starting " + numThreads + " concurrent update threads");
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadNum = i;
            executorService.submit(() -> {
                try {
                    // Each thread tries to update the pipeline with the same version
                    PipelineConfigDto threadPipeline = new PipelineConfigDto(latestPipeline);
                    System.out.println("[DEBUG_LOG] Thread " + threadNum + " attempting update with version: " + threadPipeline.getPipelineVersion());

                    try {
                        HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(
                                HttpRequest.PUT("/api/pipelines/concurrent-test-pipeline", threadPipeline)
                                        .contentType(MediaType.APPLICATION_JSON),
                                PipelineConfigDto.class);

                        // If we get here, the update was successful
                        successCount.incrementAndGet();
                        System.out.println("[DEBUG_LOG] Thread " + threadNum + " successfully updated pipeline to version: " + 
                                          updateResponse.body().getPipelineVersion());
                    } catch (HttpClientResponseException e) {
                        if (e.getStatus() == HttpStatus.CONFLICT) {
                            // Expected for all but one thread
                            conflictCount.incrementAndGet();
                            System.out.println("[DEBUG_LOG] Thread " + threadNum + " got version conflict: " + e.getMessage());
                        } else {
                            // Unexpected error
                            otherErrorCount.incrementAndGet();
                            System.err.println("[DEBUG_LOG] Thread " + threadNum + " got unexpected error: " + e.getStatus() + " - " + e.getMessage());
                            e.printStackTrace();
                        }
                    } catch (Exception e) {
                        otherErrorCount.incrementAndGet();
                        System.err.println("[DEBUG_LOG] Thread " + threadNum + " got unexpected exception: " + e.getClass().getName() + " - " + e.getMessage());
                        e.printStackTrace();
                    }
                } finally {
                    System.out.println("[DEBUG_LOG] Thread " + threadNum + " completed");
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        System.out.println("[DEBUG_LOG] Waiting for all threads to complete");
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
        executorService.shutdown();

        // Verify that only one thread succeeded and the rest got conflicts
        System.out.println("[DEBUG_LOG] Test summary: " + successCount.get() + " successful updates, " + 
                          conflictCount.get() + " version conflicts, " + 
                          otherErrorCount.get() + " other errors");

        // Print detailed information about the actual values
        System.out.println("[DEBUG_LOG] DETAILED COMPARISON:");
        System.out.println("[DEBUG_LOG] successCount: expected=1, actual=" + successCount.get());
        System.out.println("[DEBUG_LOG] conflictCount: expected=" + (numThreads - 1) + ", actual=" + conflictCount.get());
        System.out.println("[DEBUG_LOG] otherErrorCount: expected=0, actual=" + otherErrorCount.get());

        // Re-enable the assertions
        assertEquals(1, successCount.get(), "Expected exactly one successful update");
        assertEquals(numThreads - 1, conflictCount.get(), "Expected " + (numThreads - 1) + " version conflicts");
        assertEquals(0, otherErrorCount.get(), "Expected no other errors");

        // Verify the pipeline version was incremented exactly once
        System.out.println("[DEBUG_LOG] Getting final pipeline state");
        HttpResponse<PipelineConfigDto> finalResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/concurrent-test-pipeline"),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, finalResponse.status());
        PipelineConfigDto finalPipeline = finalResponse.body();
        assertNotNull(finalPipeline);

        System.out.println("[DEBUG_LOG] Final pipeline state: name=" + finalPipeline.getName() + 
                           ", version=" + finalPipeline.getPipelineVersion() + 
                           ", lastUpdated=" + finalPipeline.getPipelineLastUpdated());

        // Check Consul directly to verify state
        try {
            String versionKey = consulKvService.getFullPath("pipeline.configs.concurrent-test-pipeline.version");
            java.util.Optional<String> versionValue = consulKvService.getValue(versionKey).block();
            System.out.println("[DEBUG_LOG] Direct Consul check - version key: " + versionKey + 
                               ", value: " + (versionValue.isPresent() ? versionValue.get() : "not found"));
        } catch (Exception e) {
            System.err.println("[DEBUG_LOG] Error checking version in Consul: " + e.getMessage());
            e.printStackTrace();
        }

        assertEquals(2, finalPipeline.getPipelineVersion(), "Pipeline version should be incremented exactly once");
    }
}
