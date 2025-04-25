package com.krickert.search.pipeline.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceConfigurationTest {

    @Test
    void testServiceConfigurationConstructors() {
        // Test default constructor
        ServiceConfiguration config1 = new ServiceConfiguration();
        assertNull(config1.getName());
        assertNull(config1.getKafkaListenTopics());
        assertNull(config1.getKafkaPublishTopics());
        assertNull(config1.getGrpcForwardTo());

        // Test constructor with name
        String serviceName = "chunker";
        ServiceConfiguration config2 = new ServiceConfiguration(serviceName);
        assertEquals(serviceName, config2.getName());
        assertNull(config2.getKafkaListenTopics());
        assertNull(config2.getKafkaPublishTopics());
        assertNull(config2.getGrpcForwardTo());
    }

    @Test
    void testServiceConfigurationSettersAndGetters() {
        // Setup
        ServiceConfiguration config = new ServiceConfiguration("embedder");
        List<String> listenTopics = List.of("chunker-results");
        List<String> publishTopics = List.of("enhanced-documents");
        List<String> forwardTo = List.of("solr-indexer");

        // Test setters
        config.setKafkaListenTopics(listenTopics);
        config.setKafkaPublishTopics(publishTopics);
        config.setGrpcForwardTo(forwardTo);
        config.setName("new-embedder");

        // Test getters
        assertEquals("new-embedder", config.getName());
        assertEquals(listenTopics, config.getKafkaListenTopics());
        assertEquals(publishTopics, config.getKafkaPublishTopics());
        assertEquals(forwardTo, config.getGrpcForwardTo());
    }

    @Test
    void testServiceConfigurationWithMultipleTopics() {
        // Setup
        ServiceConfiguration config = new ServiceConfiguration("chunker");
        List<String> listenTopics = Arrays.asList("solr-documents", "input-documents", "tika-documents");
        
        // Test
        config.setKafkaListenTopics(listenTopics);
        
        // Verify
        assertEquals(3, config.getKafkaListenTopics().size());
        assertTrue(config.getKafkaListenTopics().contains("solr-documents"));
        assertTrue(config.getKafkaListenTopics().contains("input-documents"));
        assertTrue(config.getKafkaListenTopics().contains("tika-documents"));
    }
}