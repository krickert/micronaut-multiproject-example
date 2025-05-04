package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.service.ConfigurationService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigurationTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationTest.class);

    @Inject
    private ApplicationConfig applicationConfig;

    @Inject
    private PipelineConfig pipelineConfig;

    @Inject
    private ConfigurationService configurationService;

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        // Use centralized property management
        return container.getPropertiesWithDataSeeding();
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
