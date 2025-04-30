package com.krickert.search.config.consul.model;

import com.ecwid.consul.v1.ConsulClient;
import com.krickert.search.config.consul.service.ConfigurationService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigurationTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationTest.class);

    @Factory
    static class TestBeanFactory {
        @Bean
        @Singleton
        public ConsulClient consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return new ConsulClient(consulContainer.getHost(), consulContainer.getMappedPort(8500));
        }
    }

    @Container
    private static final ConsulContainer consulContainer = new ConsulContainer(DockerImageName.parse("consul:1.15"))
            .withExposedPorts(8500);

    static {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
    }

    @Inject
    private ApplicationConfig applicationConfig;

    @Inject
    private PipelineConfig pipelineConfig;

    @Inject
    private ConfigurationService configurationService;

    @Override
    public Map<String, String> getProperties() {
        // Ensure the container is started before getting host and port
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }

        LOG.info("Consul container is running at {}:{}", consulContainer.getHost(), consulContainer.getMappedPort(8500));

        Map<String, String> properties = new HashMap<>();
        // Set both consul.host and consul.client.host properties
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", String.valueOf(consulContainer.getMappedPort(8500)));

        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", String.valueOf(consulContainer.getMappedPort(8500)));
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.config.format", "yaml");
        properties.put("consul.client.config.path", "config/pipeline");

        // Enable data seeding for tests
        properties.put("consul.data.seeding.enabled", "true");
        properties.put("consul.data.seeding.file", "seed-data.yaml");
        properties.put("consul.data.seeding.skip-if-exists", "false");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

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
