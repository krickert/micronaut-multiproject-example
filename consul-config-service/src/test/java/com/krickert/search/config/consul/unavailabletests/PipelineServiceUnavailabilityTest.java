package com.krickert.search.config.consul.unavailabletests;

import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.*;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests how the API handles errors when Consul is unavailable.
 * This test uses mock implementations of ConsulKvService and PipelineConfig that throw exceptions
 * to simulate Consul being unavailable.
 */
@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.config.enabled", value = "false")
@Property(name = "consul.client.registration.enabled", value = "false")
@Property(name = "consul.client.watch.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "false")
@Property(name = "consul.data.seeding.enabled", value = "false")
@Property(name = "test.consul.unavailable", value = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineServiceUnavailabilityTest {

    /**
     * Factory for creating mock beans for unavailability tests.
     */
    @Factory
    @Requires(property = "test.consul.unavailable", value = "true")
    public static class MockBeansFactory {

        /**
         * Creates a mock ConsulKvService that throws exceptions to simulate Consul being unavailable.
         *
         * @return a mock ConsulKvService
         */
        @Bean
        @Primary
        @Singleton
        @Replaces(ConsulKvService.class)
        ConsulKvService consulKvService() {
            return new UnavailableConsulKvService();
        }

        /**
         * Creates a mock PipelineConfig that throws exceptions to simulate Consul being unavailable.
         *
         * @return a mock PipelineConfig
         */
        @Bean
        @Primary
        @Singleton
        @Replaces(PipelineConfig.class)
        PipelineConfig pipelineConfig() {
            return new UnavailablePipelineConfig();
        }
    }

    /**
     * Mock implementation of ConsulKvService that throws exceptions for all methods.
     */
    private static class UnavailableConsulKvService extends ConsulKvService {

        public UnavailableConsulKvService() {
            // Pass null for keyValueClient and a dummy path
            super(null, "config/pipeline");
        }

        @Override
        public Mono<Optional<String>> getValue(String key) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public Mono<Boolean> putValue(String key, String value) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public Mono<Boolean> putValues(Map<String, String> keyValueMap) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public Mono<Boolean> deleteKey(String key) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public Mono<Boolean> deleteKeys(List<String> keys) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public String getFullPath(String key) {
            return "config/pipeline/" + key;
        }
    }

    /**
     * Mock implementation of PipelineConfig that throws exceptions for all methods.
     */
    private static class UnavailablePipelineConfig extends PipelineConfig {

        public UnavailablePipelineConfig() {
            // Pass null for consulKvService
            super(null);
        }

        @Override
        public Map<String, PipelineConfigDto> getPipelines() {
            throw new RuntimeException("Consul is unavailable");
        }

        @Override
        public PipelineConfigDto getPipeline(String pipelineName) {
            throw new RuntimeException("Consul is unavailable");
        }

        @Override
        public Mono<Boolean> addOrUpdatePipeline(PipelineConfigDto pipeline) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public Mono<Boolean> setActivePipeline(String pipelineName) {
            return Mono.error(new RuntimeException("Consul is unavailable"));
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void testListPipelinesWhenConsulUnavailable() {
        // When Consul is unavailable, the API should return a 500 error
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines"),
                    List.class);
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    @Test
    void testCreatePipelineWhenConsulUnavailable() {
        // When Consul is unavailable, the API should return a 500 error
        CreatePipelineRequest requestBody = new CreatePipelineRequest("test-pipeline");

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/pipelines", requestBody),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    @Test
    void testGetPipelineWhenConsulUnavailable() {
        // When Consul is unavailable, the API should return a 500 error
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/test-pipeline"),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    @Test
    void testDeletePipelineWhenConsulUnavailable() {
        // When Consul is unavailable, the API should return a 500 error
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/test-pipeline"));
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }

    @Test
    void testUpdatePipelineWhenConsulUnavailable() {
        // When Consul is unavailable, the API should return a 500 error
        PipelineConfigDto pipeline = new PipelineConfigDto("test-pipeline");
        pipeline.setPipelineVersion(1);

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.PUT("/api/pipelines/test-pipeline", pipeline),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatus());
    }
}
