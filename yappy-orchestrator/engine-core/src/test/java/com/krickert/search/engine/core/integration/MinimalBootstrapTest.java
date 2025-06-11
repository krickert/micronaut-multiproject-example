package com.krickert.search.engine.core.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test to verify just one container starts through test resources
 */
@MicronautTest(startApplication = false)
public class MinimalBootstrapTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(MinimalBootstrapTest.class);
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Test 
    void testJustConsulStarts() {
        LOG.info("Testing minimal container startup");
        assertNotNull(consulHost, "Consul host should be available");
        LOG.info("✓ Consul available at {}", consulHost);
    }
}