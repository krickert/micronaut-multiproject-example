package com.krickert.search.pipeline.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ServiceConfigurationDtoTest {

    @Test
    void testServiceConfigurationDtoSettersAndGetters() {
        // Setup
        ServiceConfigurationDto dto = new ServiceConfigurationDto();
        String name = "embedder";
        List<String> listenTopics = Arrays.asList("chunker-results");
        List<String> publishTopics = Arrays.asList("enhanced-documents");
        List<String> forwardTo = Arrays.asList("solr-indexer");

        // Test setters
        dto.setName(name);
        dto.setKafkaListenTopics(listenTopics);
        dto.setKafkaPublishTopics(publishTopics);
        dto.setGrpcForwardTo(forwardTo);

        // Test getters
        assertEquals(name, dto.getName());
        assertEquals(listenTopics, dto.getKafkaListenTopics());
        assertEquals(publishTopics, dto.getKafkaPublishTopics());
        assertEquals(forwardTo, dto.getGrpcForwardTo());
    }

    @Test
    void testServiceConfigurationDtoWithMultipleTopics() {
        // Setup
        ServiceConfigurationDto dto = new ServiceConfigurationDto();
        dto.setName("chunker");
        List<String> listenTopics = Arrays.asList("solr-documents", "input-documents", "tika-documents");
        
        // Test
        dto.setKafkaListenTopics(listenTopics);
        
        // Verify
        assertEquals(3, dto.getKafkaListenTopics().size());
        assertTrue(dto.getKafkaListenTopics().contains("solr-documents"));
        assertTrue(dto.getKafkaListenTopics().contains("input-documents"));
        assertTrue(dto.getKafkaListenTopics().contains("tika-documents"));
    }

    @Test
    void testServiceConfigurationDtoWithNullValues() {
        // Setup
        ServiceConfigurationDto dto = new ServiceConfigurationDto();
        
        // Verify initial state
        assertNull(dto.getName());
        assertNull(dto.getKafkaListenTopics());
        assertNull(dto.getKafkaPublishTopics());
        assertNull(dto.getGrpcForwardTo());
        
        // Set some values
        dto.setName("test-service");
        dto.setKafkaListenTopics(Arrays.asList("topic1"));
        
        // Set null values
        dto.setKafkaListenTopics(null);
        
        // Verify
        assertEquals("test-service", dto.getName());
        assertNull(dto.getKafkaListenTopics());
        assertNull(dto.getKafkaPublishTopics());
        assertNull(dto.getGrpcForwardTo());
    }
}