package com.krickert.search.orchestrator.kafka;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@MicronautTest
@Property(name = "micronaut.test-resources.enabled", value = "true")
class ConsulTestResourcesVerificationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestResourcesVerificationTest.class);
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    String consulPort;
    
    @Test
    void testConsulTestResourcesProvided() {
        LOG.info("Consul test resources verification:");
        LOG.info("Consul host: {}", consulHost);
        LOG.info("Consul port: {}", consulPort);
        
        assertNotNull(consulHost, "Consul host should be provided by test resources");
        assertNotNull(consulPort, "Consul port should be provided by test resources");
        assertFalse(consulHost.isEmpty(), "Consul host should not be empty");
        assertFalse(consulPort.isEmpty(), "Consul port should not be empty");
    }
}