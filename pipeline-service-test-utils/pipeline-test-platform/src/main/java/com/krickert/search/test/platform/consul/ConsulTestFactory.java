package com.krickert.search.test.platform.consul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating ConsulTest instances.
 * This class creates the appropriate ConsulTest implementation.
 */
public class ConsulTestFactory {
    private static final Logger log = LoggerFactory.getLogger(ConsulTestFactory.class);
    
    /**
     * Create a ConsulTest instance.
     * 
     * @return the ConsulTest implementation
     */
    public static ConsulTest createConsulTest() {
        log.info("Creating ConsulTest instance");
        return new DefaultConsulTest();
    }
    
    /**
     * Default implementation of ConsulTest.
     * This class extends AbstractConsulTest and provides a concrete implementation.
     */
    private static class DefaultConsulTest extends AbstractConsulTest {
        // No additional functionality needed
    }
}