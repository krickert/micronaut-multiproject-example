package com.krickert.search.config.consul.integration;

import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiExposedTest {
    private static final Logger LOG = LoggerFactory.getLogger(OpenApiExposedTest.class);
    @Test
    void openApi(@Client("/") HttpClient httpClient) { 
        BlockingHttpClient client = httpClient.toBlocking();
        assertDoesNotThrow(() -> client.exchange("/swagger/consul-config-service-1.0.0.yml"));
    }
}
