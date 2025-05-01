package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
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
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineConcurrencyTest implements TestPropertyProvider {

    @Factory
    static class TestBeanFactory {
        @Bean
        @Singleton
        @jakarta.inject.Named("pipelineConcurrencyTest")
        public Consul consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return Consul.builder()
                    .withUrl("http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(8500))
                    .build();
        }
    }

    @Container
    public static ConsulContainer consulContainer = new ConsulContainer("hashicorp/consul:latest")
            .withExposedPorts(8500);
    static {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ConsulKvService consulKvService;

    @Inject
    KeyValueClient keyValueClient;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();

        // Ensure the container is started before getting host and port
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", consulContainer.getMappedPort(8500).toString());

        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", consulContainer.getMappedPort(8500).toString());
        properties.put("consul.client.config.path", "config/pipeline");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

        // Disable data seeding for tests
        properties.put("consul.data.seeding.enabled", "false");
        properties.put("consul.client.registration.enabled", "false");
        properties.put("consul.client.watch.enabled", "false");

        return properties;
    }

    @BeforeEach
    void setUp() {
        // Clear any existing pipeline configurations
        String configPath = consulKvService.getFullPath("pipeline.configs");
        keyValueClient.deleteKeys(configPath);
    }

    @Test
    void testConcurrentUpdates() throws InterruptedException {
        // Create a new pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("concurrent-test-pipeline");

        HttpResponse<PipelineConfigDto> createResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, createResponse.status());
        PipelineConfigDto pipeline = createResponse.body();
        assertNotNull(pipeline);
        assertEquals("concurrent-test-pipeline", pipeline.getName());
        assertEquals(1, pipeline.getPipelineVersion());

        // Get the pipeline to ensure we have the latest version
        HttpResponse<PipelineConfigDto> getResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/concurrent-test-pipeline"),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, getResponse.status());
        PipelineConfigDto latestPipeline = getResponse.body();
        assertNotNull(latestPipeline);

        // Create multiple threads to update the pipeline concurrently
        int numThreads = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            final int threadNum = i;
            executorService.submit(() -> {
                try {
                    // Each thread tries to update the pipeline with the same version
                    PipelineConfigDto threadPipeline = new PipelineConfigDto(latestPipeline);
                    
                    try {
                        HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(
                                HttpRequest.PUT("/api/pipelines/concurrent-test-pipeline", threadPipeline)
                                        .contentType(MediaType.APPLICATION_JSON),
                                PipelineConfigDto.class);
                        
                        // If we get here, the update was successful
                        successCount.incrementAndGet();
                        System.out.println("[DEBUG_LOG] Thread " + threadNum + " successfully updated pipeline");
                    } catch (HttpClientResponseException e) {
                        if (e.getStatus() == HttpStatus.CONFLICT) {
                            // Expected for all but one thread
                            conflictCount.incrementAndGet();
                            System.out.println("[DEBUG_LOG] Thread " + threadNum + " got version conflict");
                        } else {
                            // Unexpected error
                            System.err.println("[DEBUG_LOG] Thread " + threadNum + " got unexpected error: " + e.getMessage());
                            throw e;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for threads to complete");
        executorService.shutdown();

        // Verify that only one thread succeeded and the rest got conflicts
        assertEquals(1, successCount.get(), "Expected exactly one successful update");
        assertEquals(numThreads - 1, conflictCount.get(), "Expected " + (numThreads - 1) + " version conflicts");

        // Verify the pipeline version was incremented exactly once
        HttpResponse<PipelineConfigDto> finalResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/concurrent-test-pipeline"),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, finalResponse.status());
        PipelineConfigDto finalPipeline = finalResponse.body();
        assertNotNull(finalPipeline);
        assertEquals(2, finalPipeline.getPipelineVersion(), "Pipeline version should be incremented exactly once");
    }
}