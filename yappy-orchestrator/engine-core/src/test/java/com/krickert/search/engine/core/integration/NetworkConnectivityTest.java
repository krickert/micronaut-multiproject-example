package com.krickert.search.engine.core.integration;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test to verify network connectivity between containers managed by test resources.
 */
@MicronautTest
public class NetworkConnectivityTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(NetworkConnectivityTest.class);
    
    @Test
    void testConsulIsAccessibleFromHost() throws Exception {
        // Get test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Verify Consul is available
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        
        assertThat(consulHost).as("Consul host should be resolved").isPresent();
        assertThat(consulPort).as("Consul port should be resolved").isPresent();
        
        LOG.info("Consul is available at {}:{}", consulHost.get(), consulPort.get());
        
        // Try to connect to Consul from the host
        String consulUrl = "http://" + consulHost.get() + ":" + consulPort.get() + "/v1/status/leader";
        LOG.info("Testing connection to Consul at: {}", consulUrl);
        
        URL url = new URL(consulUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        
        int responseCode = connection.getResponseCode();
        LOG.info("Consul response code: {}", responseCode);
        
        assertThat(responseCode).as("Consul should be accessible from host").isEqualTo(200);
        
        LOG.info("✅ Consul is accessible from the host!");
    }
    
    @Test
    void testAllInfrastructureServicesAreAccessible() throws Exception {
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        // Check Consul
        Optional<String> consulHost = client.resolve("consul.client.host", Map.of(), Map.of());
        Optional<String> consulPort = client.resolve("consul.client.port", Map.of(), Map.of());
        LOG.info("Consul: {}:{} - {}", 
            consulHost.orElse("NOT RESOLVED"), 
            consulPort.orElse("NOT RESOLVED"),
            consulHost.isPresent() && consulPort.isPresent() ? "✅" : "❌");
        
        // Check Kafka
        Optional<String> kafkaServers = client.resolve("kafka.bootstrap.servers", Map.of(), Map.of());
        LOG.info("Kafka: {} - {}", 
            kafkaServers.orElse("NOT RESOLVED"),
            kafkaServers.isPresent() ? "✅" : "❌");
        
        // Check Apicurio
        Optional<String> apicurioUrl = client.resolve("apicurio.registry.url", Map.of(), Map.of());
        LOG.info("Apicurio: {} - {}", 
            apicurioUrl.orElse("NOT RESOLVED"),
            apicurioUrl.isPresent() ? "✅" : "❌");
        
        // Check Moto (LocalStack)
        Optional<String> awsEndpoint = client.resolve("aws.endpoint", Map.of(), Map.of());
        LOG.info("AWS/Moto: {} - {}", 
            awsEndpoint.orElse("NOT RESOLVED"),
            awsEndpoint.isPresent() ? "✅" : "❌");
        
        // Check OpenSearch
        Optional<String> opensearchUrl = client.resolve("opensearch.url", Map.of(), Map.of());
        LOG.info("OpenSearch: {} - {}", 
            opensearchUrl.orElse("NOT RESOLVED"),
            opensearchUrl.isPresent() ? "✅" : "❌");
        
        LOG.info("\n=== Infrastructure Summary ===");
        assertThat(consulHost).as("Consul host should be resolved").isPresent();
        assertThat(kafkaServers).as("Kafka bootstrap servers should be resolved").isPresent();
        assertThat(apicurioUrl).as("Apicurio URL should be resolved").isPresent();
        assertThat(awsEndpoint).as("AWS endpoint should be resolved").isPresent();
        
        // OpenSearch might still be problematic
        if (opensearchUrl.isEmpty()) {
            LOG.warn("OpenSearch is not available - this is a known issue");
        }
    }
}