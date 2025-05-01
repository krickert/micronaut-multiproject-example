package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConsulKvServiceTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvServiceTest.class);

    @Inject
    private ConsulKvService consulKvService;

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        // Use centralized property management
        return container.getPropertiesWithTestConfigPath();
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
