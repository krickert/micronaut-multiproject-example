package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.service.ConfigurationService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@Testcontainers
public class ConfigurationTest implements TestPropertyProvider {

    @Container
    private static final ConsulContainer consulContainer = new ConsulContainer(DockerImageName.parse("consul:1.15"))
            .withExposedPorts(8500);

    @Inject
    private ApplicationConfig applicationConfig;

    @Inject
    private PipelineConfig pipelineConfig;

    @Inject
    private ConfigurationService configurationService;

    @Override
    public Map<String, String> getProperties() {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }

        Map<String, String> properties = new HashMap<>();
        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", String.valueOf(consulContainer.getMappedPort(8500)));
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.config.format", "yaml");
        properties.put("consul.client.config.path", "config/pipeline");
        properties.put("consul.data.seeding.enabled", "true");
        properties.put("consul.data.seeding.file", "seed-data.yaml");
        properties.put("consul.data.seeding.skip-if-exists", "false");
        return properties;
    }

    @Test
    void testApplicationConfigInjected() {
        assertNotNull(applicationConfig);
        assertEquals("consul-config-service", applicationConfig.getApplicationName());
    }

    @Test
    void testPipelineConfigInjected() {
        assertNotNull(pipelineConfig);
    }

    @Test
    void testConfigurationServiceInjected() {
        assertNotNull(configurationService);
    }
}