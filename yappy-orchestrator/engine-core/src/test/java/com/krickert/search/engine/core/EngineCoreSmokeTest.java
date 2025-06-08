package com.krickert.search.engine.core;

import com.krickert.testcontainers.consul.ConsulTestResourceProvider;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple smoke test to verify test resources are working correctly.
 * This test should pass if Consul and Kafka containers start properly.
 */
@MicronautTest
public class EngineCoreSmokeTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineCoreSmokeTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.isRunning()).isTrue();
        LOG.info("Application context started successfully");
    }
    
    @Test
    void consulTestResourcesConfigured() {
        // Verify Consul properties are injected by test resources
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse(null);
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(null);
        
        assertThat(consulHost)
                .as("Consul host should be injected by test resources")
                .isNotNull()
                .isNotEmpty();
        
        assertThat(consulPort)
                .as("Consul port should be injected by test resources")
                .isNotNull()
                .isPositive();
        
        LOG.info("Consul test resources configured: {}:{}", consulHost, consulPort);
    }
    
    @Test
    void kafkaTestResourcesConfigured() {
        // Verify Kafka properties are injected by test resources
        String kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
        
        assertThat(kafkaServers)
                .as("Kafka bootstrap servers should be injected by test resources")
                .isNotNull()
                .isNotEmpty();
        
        LOG.info("Kafka test resources configured: {}", kafkaServers);
    }
}