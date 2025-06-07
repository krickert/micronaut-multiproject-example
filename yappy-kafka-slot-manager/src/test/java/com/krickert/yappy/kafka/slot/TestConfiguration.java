package com.krickert.yappy.kafka.slot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.*;
import io.micronaut.discovery.consul.ConsulConfiguration;
import io.micronaut.discovery.consul.client.v1.ConsulClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.kiwiproject.consul.Consul;

import java.util.Properties;

/**
 * Test configuration for integration tests.
 */
@Factory
@Requires(env = "test")
public class TestConfiguration {
    
    @Singleton
    @Bean
    @Requires(bean = ConsulClient.class)
    public Consul consul(ConsulConfiguration consulConfiguration) {
        // Create Consul client from Micronaut's configuration
        return Consul.builder()
                .withHostAndPort(com.google.common.net.HostAndPort.fromParts(
                        consulConfiguration.getHost(), 
                        consulConfiguration.getPort()))
                .build();
    }
    
    @Singleton
    @Bean
    @Named("kafkaAdmin")
    public AdminClient kafkaAdminClient(@Value("${kafka.bootstrap.servers}") String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "kafka-slot-manager-test-admin");
        return AdminClient.create(props);
    }
    
}