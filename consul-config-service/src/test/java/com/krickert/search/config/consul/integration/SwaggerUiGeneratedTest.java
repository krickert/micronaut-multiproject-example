package com.krickert.search.config.consul.integration;

import com.ecwid.consul.v1.ConsulClient;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(startApplication = false, environments = {"test"})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SwaggerUiGeneratedTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SwaggerUiGeneratedTest.class);

    @Factory
    static class TestBeanFactory {
        @Bean
        @Singleton
        public ConsulClient consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return new ConsulClient(consulContainer.getHost(), consulContainer.getMappedPort(8500));
        }
    }

    @Container
    private static final ConsulContainer consulContainer = new ConsulContainer("hashicorp/consul:latest")
            .withExposedPorts(8500);

    static {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
    }

    @Override
    public Map<String, String> getProperties() {
        // Ensure the container is started before getting host and port
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }

        LOG.info("Consul container is running at {}:{}", consulContainer.getHost(), consulContainer.getMappedPort(8500));

        Map<String, String> properties = new HashMap<>();
        // Set both consul.host and consul.client.host properties
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", String.valueOf(consulContainer.getMappedPort(8500)));

        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", String.valueOf(consulContainer.getMappedPort(8500)));
        properties.put("consul.client.config.enabled", "true");
        properties.put("consul.client.config.format", "yaml");
        properties.put("consul.client.config.path", "config/pipeline");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");
        return properties;
    }

    @Test
    void buildGeneratesOpenApi(ResourceLoader resourceLoader) {
        // Check for the resource at the path specified in the test application.yml
        assertTrue(resourceLoader.getResource("META-INF/resources/webjars/swagger-ui/4.18.2/index.html").isPresent() ||
                  resourceLoader.getResource("META-INF/swagger/views/swagger-ui/index.html").isPresent(),
                  "Swagger UI index.html should be present at one of the expected locations");
    }
}
