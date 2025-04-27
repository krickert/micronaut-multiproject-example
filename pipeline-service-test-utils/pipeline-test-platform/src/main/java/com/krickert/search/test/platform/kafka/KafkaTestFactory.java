package com.krickert.search.test.platform.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating KafkaTest instances.
 * This class creates the appropriate KafkaTest implementation based on the registry type.
 */
public class KafkaTestFactory {
    private static final Logger log = LoggerFactory.getLogger(KafkaTestFactory.class);
    
    /**
     * Create a KafkaTest instance based on the registry type.
     * If no registry type is specified, the default is Apicurio.
     * 
     * @return the appropriate KafkaTest implementation
     */
    public static KafkaTest createKafkaTest() {
        return createKafkaTest(null);
    }
    
    /**
     * Create a KafkaTest instance based on the registry type.
     * If no registry type is specified, the default is Apicurio.
     * 
     * @param returnClass the return class for the deserializer
     * @return the appropriate KafkaTest implementation
     */
    public static KafkaTest createKafkaTest(String returnClass) {
        // Get the registry type from the container manager
        String registryType = TestContainerManager.getInstance().getRegistryType();
        
        log.info("Creating KafkaTest for registry type: {}", registryType);
        
        // Create the appropriate implementation based on the registry type
        return switch (registryType.toLowerCase()) {
            case "glue" -> {
                log.info("Using Glue Schema Registry");
                yield returnClass != null ? new KafkaGlueTest(returnClass) : new KafkaGlueTest();
            }
            case "apicurio", "" -> {
                log.info("Using Apicurio Registry");
                yield returnClass != null ? new KafkaApicurioTest(returnClass) : new KafkaApicurioTest();
            }
            default -> {
                log.warn("Unknown registry type: {}. Using default (Apicurio)", registryType);
                yield returnClass != null ? new KafkaApicurioTest(returnClass) : new KafkaApicurioTest();
            }
        };
    }
}