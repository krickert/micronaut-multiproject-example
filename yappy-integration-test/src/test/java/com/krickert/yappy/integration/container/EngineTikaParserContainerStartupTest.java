package com.krickert.yappy.integration.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify the Engine-Tika-Parser container starts via test resources.
 * This test doesn't inject any services that depend on external resources.
 */
@MicronautTest(environments = {"test"}, startApplication = false)
public class EngineTikaParserContainerStartupTest {

    private static final Logger LOG = LoggerFactory.getLogger(EngineTikaParserContainerStartupTest.class);

    @Inject
    ApplicationContext applicationContext;

    @Test
    @DisplayName("Verify test resource provider properties are injected")
    void testPropertiesInjected() {
        LOG.info("Verifying test resource provider properties");

        // Check if our custom test resource provider is being used
        String engineHttpUrl = applicationContext.getProperty("yappy.engine.http.url", String.class)
                .orElse(null);
        
        if (engineHttpUrl != null) {
            LOG.info("Engine HTTP URL provided by test resource: {}", engineHttpUrl);
            assertTrue(engineHttpUrl.startsWith("http://"), "Engine URL should start with http://");
            assertTrue(engineHttpUrl.contains(":"), "Engine URL should contain a port");
        } else {
            LOG.warn("Engine HTTP URL not provided - test resource provider may not be active");
        }

        // Check other properties that would be set by our provider
        String engineGrpcEndpoint = applicationContext.getProperty("yappy.engine.grpc.endpoint", String.class)
                .orElse(null);
        if (engineGrpcEndpoint != null) {
            LOG.info("Engine gRPC endpoint: {}", engineGrpcEndpoint);
        }

        String tikaGrpcEndpoint = applicationContext.getProperty("yappy.tika-parser.grpc.endpoint", String.class)
                .orElse(null);
        if (tikaGrpcEndpoint != null) {
            LOG.info("Tika Parser gRPC endpoint: {}", tikaGrpcEndpoint);
        }

        // At least one property should be set if the provider is working
        assertTrue(
            engineHttpUrl != null || engineGrpcEndpoint != null || tikaGrpcEndpoint != null,
            "At least one property should be set by the test resource provider"
        );
    }
}