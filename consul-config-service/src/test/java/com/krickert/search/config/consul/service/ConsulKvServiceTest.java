package com.krickert.search.config.consul.service;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulKvServiceTest implements TestPropertyProvider {

    @Factory
    static class ConsulClientFactory {
        @Bean
        @Singleton
        @jakarta.inject.Named("consulKvServiceTest")
        public Consul consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return Consul.builder()
                    .withUrl("http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(8500))
                    .build();
        }

        @Bean
        @Singleton
        public KeyValueClient keyValueClient(Consul consulClient) {
            return consulClient.keyValueClient();
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
    private ConsulKvService consulKvService;

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
        properties.put("consul.client.config.path", "config/test");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

        return properties;
    }

    @Test
    public void testPutAndGetValue() {
        // Given
        String key = "test-key";
        String value = "test-value";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // Then get the value and verify it
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return value.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }

    @Test
    public void testDeleteKey() {
        // Given
        String key = "test-delete-key";
        String value = "test-value";
        String fullPath = consulKvService.getFullPath(key);

        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // When & Then
        // Delete the key
        StepVerifier.create(consulKvService.deleteKey(fullPath))
            .expectNext(true)
            .verifyComplete();

        // Verify the key is deleted
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(Optional::isEmpty)
            .verifyComplete();
    }

    @Test
    public void testGetNonExistentKey() {
        // Given
        String key = "non-existent-key";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(Optional::isEmpty)
            .verifyComplete();
    }

    @Test
    public void testGetFullPath() {
        // Given
        String configPath = "config/test";
        String key = "test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(configPath + "/" + key, fullPath, "Full path should be correctly constructed");
    }

    @Test
    public void testGetFullPathWithExistingPrefix() {
        // Given
        String configPath = "config/test";
        String key = configPath + "/test-key";

        // When
        String fullPath = consulKvService.getFullPath(key);

        // Then
        assertEquals(key, fullPath, "Full path should not duplicate the prefix");
    }

    @Test
    public void testPutAndGetValueWithSpecialCharacters() {
        // Given
        String key = "test/pipeline.configs.pipeline1.service.chunker.kafka-listen-topics[0]";
        String value = "test-topic";
        String fullPath = consulKvService.getFullPath(key);

        // When & Then
        // First put the value
        StepVerifier.create(consulKvService.putValue(fullPath, value))
            .expectNext(true)
            .verifyComplete();

        // Then get the value and verify it
        StepVerifier.create(consulKvService.getValue(fullPath))
            .expectNextMatches(optional -> {
                if (optional.isPresent()) {
                    return value.equals(optional.get());
                }
                return false;
            })
            .verifyComplete();
    }
}
