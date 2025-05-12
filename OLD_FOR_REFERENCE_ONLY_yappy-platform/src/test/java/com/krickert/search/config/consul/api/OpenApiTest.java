package com.krickert.search.config.consul.api;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpenApiTest {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiTest.class);

    @Inject
    @Client("/")
    private HttpClient client;

    @Test
    void testOpenApiEndpoint() {
        // Test that the OpenAPI endpoint returns a valid response
        HttpResponse<?> response = client.toBlocking().exchange(HttpRequest.GET("/swagger/consul-config-service-1.0.0.yml"));
        assertEquals(HttpStatus.OK, response.status());
    }
}
