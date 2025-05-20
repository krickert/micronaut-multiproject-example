package com.krickert.search.pipeline.integration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.DynamicConfigurationManagerImpl;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarderClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = {"test"}, startApplication = true)
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
public class EchoFullConsulGrpcKafkaIntegrationTest {

    @Inject
    DynamicConfigurationManagerImpl dynamicConfigurationManager;

    @Value("${kafka.enabled}")
    boolean keySerializer;

    @Value("${apicurio.registry.url}")
    String schemaRegistryType;

    @Value("${kafka.bootstrap.servers}")
    String bootstrapServers;


    @Inject
    KafkaForwarderClient kafkaForwarder;

    @Test
    public void testBootstrapServersExists() {
        assertNotNull(bootstrapServers);
    }

    @Test
    public void testSchemaRegistryTypeExists() {
        assertNotNull(schemaRegistryType);
    }

    @Test
    public void testKafkaForwarderExists() {
        assertNotNull(kafkaForwarder);
    }

    @Test
    public void testKeySerializerExists() {
        assertTrue(keySerializer);
    }

    @Test
    public void testDynamicConfigManagerLoads() {
        assertInstanceOf(DynamicConfigurationManagerImpl.class, dynamicConfigurationManager);
    }



}
