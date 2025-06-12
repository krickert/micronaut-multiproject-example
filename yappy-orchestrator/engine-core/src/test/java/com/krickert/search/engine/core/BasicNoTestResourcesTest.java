package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic test without test resources to verify test setup
 */
@MicronautTest(startApplication = false, environments = {"test"})
public class BasicNoTestResourcesTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BasicNoTestResourcesTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void testApplicationContextStarts() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.isRunning()).isTrue();
        
        logger.info("Application context started successfully");
        logger.info("Active environments: {}", applicationContext.getEnvironment().getActiveNames());
    }
}