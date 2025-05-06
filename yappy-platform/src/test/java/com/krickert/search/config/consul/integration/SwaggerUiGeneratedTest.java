package com.krickert.search.config.consul.integration;

import io.micronaut.core.io.ResourceLoader;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SwaggerUiGeneratedTest {
    private static final Logger LOG = LoggerFactory.getLogger(SwaggerUiGeneratedTest.class);

    @Test
    void buildGeneratesOpenApi(ResourceLoader resourceLoader) {
        // Check for the resource at the path specified in the test application.yml
        assertTrue(resourceLoader.getResource("META-INF/resources/webjars/swagger-ui/4.18.2/index.html").isPresent() ||
                  resourceLoader.getResource("META-INF/swagger/views/swagger-ui/index.html").isPresent(),
                  "Swagger UI index.html should be present at one of the expected locations");
    }
}
