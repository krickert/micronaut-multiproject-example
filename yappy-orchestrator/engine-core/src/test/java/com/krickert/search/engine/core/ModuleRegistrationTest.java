package com.krickert.search.engine.core;

import com.krickert.yappy.registration.RegistrationService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test that demonstrates registering multiple modules using the RegistrationService.
 * This simulates what the CLI tool would do in production.
 */
@MicronautTest(environments = "multiprocessor")
public class ModuleRegistrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ModuleRegistrationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Property(name = "grpc.server.port")
    Integer engineGrpcPort;
    
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testRegisterAllModules() {
        // Create registration service
        RegistrationService registrationService = new RegistrationService();
        
        // Engine endpoint - the engine's gRPC registration service
        String engineEndpoint = "localhost:" + engineGrpcPort;
        logger.info("Engine gRPC endpoint: {}", engineEndpoint);
        
        // Register tika-parser
        String tikaHost = applicationContext.getProperty("tika-parser.host", String.class).orElse("localhost");
        Integer tikaPort = applicationContext.getProperty("tika-parser.grpc.port", Integer.class).orElse(50053);
        logger.info("Registering tika-parser at {}:{}", tikaHost, tikaPort);
        registrationService.registerModule(
            tikaHost, 
            tikaPort, 
            engineEndpoint,
            "tika-parser-1",  // instance name
            "GRPC",           // health check type
            "grpc.health.v1.Health/Check",  // health check endpoint
            "1.0.0"           // version
        );
        
        // Register chunker-small
        String chunkerSmallHost = applicationContext.getProperty("chunker-small.host", String.class).orElse("localhost");
        Integer chunkerSmallPort = applicationContext.getProperty("chunker-small.grpc.port", Integer.class).orElse(50054);
        logger.info("Registering chunker-small at {}:{}", chunkerSmallHost, chunkerSmallPort);
        registrationService.registerModule(
            chunkerSmallHost, 
            chunkerSmallPort, 
            engineEndpoint,
            "chunker-small-1",
            "GRPC",
            "grpc.health.v1.Health/Check",
            "1.0.0"
        );
        
        // Register chunker-large
        String chunkerLargeHost = applicationContext.getProperty("chunker-large.host", String.class).orElse("localhost");
        Integer chunkerLargePort = applicationContext.getProperty("chunker-large.grpc.port", Integer.class).orElse(50055);
        logger.info("Registering chunker-large at {}:{}", chunkerLargeHost, chunkerLargePort);
        registrationService.registerModule(
            chunkerLargeHost, 
            chunkerLargePort, 
            engineEndpoint,
            "chunker-large-1",
            "GRPC",
            "grpc.health.v1.Health/Check",
            "1.0.0"
        );
        
        // Register embedder-minilm
        String embedderMinilmHost = applicationContext.getProperty("embedder-minilm.host", String.class).orElse("localhost");
        Integer embedderMinilmPort = applicationContext.getProperty("embedder-minilm.grpc.port", Integer.class).orElse(50056);
        logger.info("Registering embedder-minilm at {}:{}", embedderMinilmHost, embedderMinilmPort);
        registrationService.registerModule(
            embedderMinilmHost, 
            embedderMinilmPort, 
            engineEndpoint,
            "embedder-minilm-1",
            "GRPC",
            "grpc.health.v1.Health/Check",
            "1.0.0"
        );
        
        // Register embedder-multilingual
        String embedderMultilingualHost = applicationContext.getProperty("embedder-multilingual.host", String.class).orElse("localhost");
        Integer embedderMultilingualPort = applicationContext.getProperty("embedder-multilingual.grpc.port", Integer.class).orElse(50057);
        logger.info("Registering embedder-multilingual at {}:{}", embedderMultilingualHost, embedderMultilingualPort);
        registrationService.registerModule(
            embedderMultilingualHost, 
            embedderMultilingualPort, 
            engineEndpoint,
            "embedder-multilingual-1",
            "GRPC",
            "grpc.health.v1.Health/Check",
            "1.0.0"
        );
        
        logger.info("All modules registered successfully!");
    }
}