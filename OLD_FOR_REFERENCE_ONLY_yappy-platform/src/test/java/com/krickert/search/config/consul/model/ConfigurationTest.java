package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.service.ConfigurationService;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigurationTest {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationTest.class);

    @Inject
    private ApplicationConfig applicationConfig;

    @Inject
    private PipelineConfig pipelineConfig;

    @Inject
    private ConfigurationService configurationService;

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
