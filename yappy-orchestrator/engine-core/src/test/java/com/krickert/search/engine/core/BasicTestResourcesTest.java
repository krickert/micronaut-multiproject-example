package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic test to verify test resources are working
 */
@MicronautTest
public class BasicTestResourcesTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BasicTestResourcesTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void testConsulIsAvailable() {
        // Check if Consul properties are injected by test resources
        var consulHost = applicationContext.getProperty("consul.client.host", String.class);
        var consulPort = applicationContext.getProperty("consul.client.port", Integer.class);
        
        logger.info("Consul host: {}", consulHost.orElse("not set"));
        logger.info("Consul port: {}", consulPort.orElse(-1));
        
        assertThat(consulHost).isPresent();
        assertThat(consulPort).isPresent();
        assertThat(consulHost.get()).isNotBlank();
        assertThat(consulPort.get()).isPositive();
    }
    
    @Test
    void testKafkaIsAvailable() {
        // Check if Kafka properties are injected by test resources
        var kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class);
        
        logger.info("Kafka bootstrap servers: {}", kafkaServers.orElse("not set"));
        
        assertThat(kafkaServers).isPresent();
        assertThat(kafkaServers.get()).isNotBlank();
        assertThat(kafkaServers.get()).doesNotContain("${");
    }
}