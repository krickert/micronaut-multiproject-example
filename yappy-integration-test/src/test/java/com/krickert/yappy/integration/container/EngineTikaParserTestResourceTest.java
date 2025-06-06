package com.krickert.yappy.integration.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// This is the ideal test format now. No more manual container setup!
@MicronautTest(environments = {"test"})
public class EngineTikaParserTestResourceTest {

    private static final Logger LOG = LoggerFactory.getLogger(EngineTikaParserTestResourceTest.class);

    @Inject
    ApplicationContext applicationContext;

    // The 'yappy.engine.http.url' property is resolved by your new provider.
    @Inject
    @Client("${yappy.engine.http.url}")
    HttpClient httpClient;

    @Test
    @DisplayName("Verify Engine health endpoint is accessible")
    void testEngineHealthEndpoint() {
        LOG.info("Checking Engine health endpoint via test resource provider...");
        try {
            // This request now goes to the container started automatically by your provider
            String healthResponse = httpClient.toBlocking().retrieve(HttpRequest.GET("/health"));
            assertNotNull(healthResponse, "Health endpoint should return a response");
            LOG.info("Engine health response: {}", healthResponse);
            assertTrue(healthResponse.contains("status"), "Health response should contain status");
        } catch (Exception e) {
            fail("Failed to access Engine health endpoint: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Verify provider injected all properties correctly")
    void testPropertiesAreInjected() {
        // Your provider is responsible for setting this URL
        String engineHttpUrl = applicationContext.getProperty("yappy.engine.http.url", String.class)
                .orElse(null);
        assertNotNull(engineHttpUrl, "yappy.engine.http.url property must be set by test resource");

        // And this one...
        String engineGrpcEndpoint = applicationContext.getProperty("yappy.engine.grpc.endpoint", String.class)
                .orElse(null);
        assertNotNull(engineGrpcEndpoint, "yappy.engine.grpc.endpoint property must be set by test resource");

        // And this one!
        String tikaGrpcEndpoint = applicationContext.getProperty("yappy.tika-parser.grpc.endpoint", String.class)
                .orElse(null);
        assertNotNull(tikaGrpcEndpoint, "yappy.tika-parser.grpc.endpoint property must be set by test resource");

        LOG.info("All properties injected successfully!");
    }
}