package com.krickert.search.config.consul.integration;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.io.ResourceLoader;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SwaggerUiGeneratedTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(SwaggerUiGeneratedTest.class);

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer consulTestContainer = ConsulTestContainer.getInstance();
        ConsulContainer consulContainer = consulTestContainer.getContainer();
        LOG.info("Consul container is running at {}:{}", consulContainer.getHost(), consulContainer.getMappedPort(8500));
        Map<String, String> properties = new HashMap<>(consulTestContainer.getProperties());
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
