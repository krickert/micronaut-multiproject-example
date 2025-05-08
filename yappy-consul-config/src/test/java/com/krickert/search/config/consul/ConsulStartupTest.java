package com.krickert.search.config.consul;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Test to verify that Consul is running at startup.
 * This test ensures that the Consul container is properly started
 * and accessible when loaded from test resources.
 */
@MicronautTest
public class ConsulStartupTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulStartupTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    /**
     * Test that Consul is running and accessible.
     * This test verifies that:
     * 1. The Consul client configuration is available in the application context
     * 2. The Consul HTTP API is accessible
     */
    @Test
    void testConsulIsRunning() throws Exception {
        // Verify that Consul client configuration is available
        String consulHost = applicationContext.getProperty("consul.client.host", String.class)
                .orElseThrow(() -> new AssertionError("consul.client.host property not found"));
        
        String consulPortStr = applicationContext.getProperty("consul.client.port", String.class)
                .orElseThrow(() -> new AssertionError("consul.client.port property not found"));
        
        int consulPort = Integer.parseInt(consulPortStr);
        
        LOG.info("Consul is configured at {}:{}", consulHost, consulPort);
        
        // Verify that Consul HTTP API is accessible by making a request to the health endpoint
        URL url = new URL("http://" + consulHost + ":" + consulPort + "/v1/status/leader");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        
        int responseCode = connection.getResponseCode();
        LOG.info("Consul health check response code: {}", responseCode);
        
        // Assert that the response is successful (200 OK)
        Assertions.assertEquals(200, responseCode, "Consul HTTP API should be accessible");
        
        // Verify that we get a response (leader address)
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        
        String leaderResponse = response.toString();
        LOG.info("Consul leader response: {}", leaderResponse);
        
        // Assert that the response is not empty (contains leader address)
        Assertions.assertFalse(leaderResponse.isEmpty(), "Consul leader response should not be empty");
        
        LOG.info("Consul is running and accessible");
    }
}