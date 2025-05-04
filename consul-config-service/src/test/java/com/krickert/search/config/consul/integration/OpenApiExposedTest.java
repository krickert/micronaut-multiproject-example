package com.krickert.search.config.consul.integration;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.Consul;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiExposedTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiExposedTest.class);

    private static final ConsulTestContainer consulContainer = ConsulTestContainer.getInstance();

    @Override
    public Map<String, String> getProperties() {
        // Ensure the container is started before getting host and port

        LOG.info("Consul container is running at {}:{}", consulContainer.getContainer().getHost(),
                consulContainer.getContainer().getMappedPort(8500));

        Map<String, String> properties = new HashMap<>(ConsulTestContainer.getInstance().getProperties());
        // Set both consul.host and consul.client.host properties
        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "true");
        return properties;
    }

    @Test
    void openApi(@Client("/") HttpClient httpClient) { 
        BlockingHttpClient client = httpClient.toBlocking();
        assertDoesNotThrow(() -> client.exchange("/swagger/consul-config-service-1.0.0.yml"));
    }
}
