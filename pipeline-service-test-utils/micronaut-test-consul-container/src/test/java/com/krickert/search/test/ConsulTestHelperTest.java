package com.krickert.search.test;

import com.krickert.search.test.consul.ConsulContainer;
import com.krickert.search.test.consul.ConsulTestHelper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Test class for {@link ConsulTestHelper}.
 * This test demonstrates how to use the ConsulTestHelper to load configuration into Consul.
 */
@MicronautTest(environments = {"test"})
public class ConsulTestHelperTest {
    private static final Logger log = LoggerFactory.getLogger(ConsulTestHelperTest.class);

    @Inject
    private ConsulTestHelper consulTestHelper;

    @Inject
    private ConsulContainer consulContainer;

    /**
     * Test that the ConsulTestHelper can be injected.
     */
    @Test
    public void testConsulTestHelperInjection() {
        Assertions.assertNotNull(consulTestHelper, "ConsulTestHelper should be injected");
    }

    /**
     * Test that the ConsulContainer is running.
     */
    @Test
    public void testConsulContainerIsRunning() {
        Assertions.assertTrue(consulContainer.isRunning(), "Consul container should be running");
    }

    /**
     * Test that the test-pipeline1.properties file exists and can be loaded.
     */
    @Test
    public void testPipelineConfigFileExists() {
        // Verify that the test-pipeline1.properties file exists
        InputStream input = getClass().getClassLoader().getResourceAsStream("test-pipeline1.properties");
        Assertions.assertNotNull(input, "test-pipeline1.properties file should exist");

        try {
            input.close();
        } catch (IOException e) {
            log.error("Error closing input stream", e);
        }

        log.info("test-pipeline1.properties file exists");
    }

    /**
     * Test that the configuration-test.properties file exists and can be loaded.
     */
    @Test
    public void testConfigurationFileExists() {
        // Verify that the configuration-test.properties file exists
        InputStream input = getClass().getClassLoader().getResourceAsStream("configuration-test.properties");
        Assertions.assertNotNull(input, "configuration-test.properties file should exist");

        try {
            input.close();
        } catch (IOException e) {
            log.error("Error closing input stream", e);
        }

        log.info("configuration-test.properties file exists");
    }

    /**
     * Test that the ConsulTestHelper can load configuration into Consul.
     */
    @Test
    public void testLoadPipelineConfig() {
        // Load the test-pipeline1.properties file into Consul
        boolean result = consulTestHelper.loadPipelineConfig("test-pipeline1.properties");
        Assertions.assertTrue(result, "Loading pipeline config should succeed");

        log.info("Successfully loaded pipeline configuration into Consul");

        // Verify that the configuration was loaded correctly by retrieving it from Consul
        Map<String, String> keyValues = consulTestHelper.getKeyValues("pipeline.configs.pipeline1");
        Assertions.assertFalse(keyValues.isEmpty(), "Should find key-value pairs with prefix pipeline.configs.pipeline1");

        // Verify specific values
        String value = consulTestHelper.getKeyValue("pipeline.configs.pipeline1.service.importer.kafka-publish-topics");
        Assertions.assertEquals("test-input-documents", value, "Should find correct value for importer kafka-publish-topics");

        value = consulTestHelper.getKeyValue("pipeline.configs.pipeline1.service.chunker.kafka-listen-topics");
        Assertions.assertEquals("test-input-documents", value, "Should find correct value for chunker kafka-listen-topics");
    }

    /**
     * Test that the ConsulTestHelper can retrieve key-value pairs from Consul.
     */
    @Test
    public void testGetKeyValues() {
        // Load configuration into Consul
        consulTestHelper.loadPropertiesFile("configuration-test.properties", "config/test");

        // Retrieve key-value pairs with prefix
        Map<String, String> keyValues = consulTestHelper.getKeyValues("logging.level");
        Assertions.assertFalse(keyValues.isEmpty(), "Should find key-value pairs with prefix logging.level");

        // Verify specific value
        String value = consulTestHelper.getKeyValue("logging.level.com.krickert");
        Assertions.assertEquals("DEBUG", value, "Should find correct value for logging.level.com.krickert");
    }
}
