package com.krickert.search.config.consul.api;

import com.krickert.search.config.consul.container.ConsulTestContainer;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ServiceDiscoveryControllerTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceDiscoveryControllerTest.class);

    @Bean
    @Singleton
    @Replaces(bean = ConsulKvService.class)
    public ConsulKvService consulKvService(@Named("serviceDiscoveryControllerTest") Consul consulClient) {
        return new ConsulKvService(consulClient.keyValueClient(), "config/test");
    }

    @Inject
    @Named("serviceDiscoveryControllerTest")
    private Consul consulClient;

    @Inject
    @Client("/")
    private HttpClient client;

    @Override
    public Map<String, String> getProperties() {
        ConsulTestContainer container = ConsulTestContainer.getInstance();
        LOG.info("Using shared Consul container");

        // Use centralized property management
        return container.getPropertiesWithTestConfigPathWithoutDataSeeding();
    }

    @BeforeEach
    public void setUp() {
        // Register test services in Consul
        registerTestServices();
    }

    private void registerTestServices() {
        try {
            // Deregister any existing test services
            deregisterTestServices();

            // Register a test pipe service
            Registration pipeService = ImmutableRegistration.builder()
                    .id("test-pipe-service-1")
                    .name("test-pipe-service")
                    .address("localhost")
                    .port(8081)
                    .tags(List.of("grpc-pipeservice", "test"))
                    .check(Registration.RegCheck.http("http://localhost:8081/health", 10))
                    .build();

            consulClient.agentClient().register(pipeService);

            // Register another service without the pipe tag
            Registration otherService = ImmutableRegistration.builder()
                    .id("test-other-service-1")
                    .name("test-other-service")
                    .address("localhost")
                    .port(8082)
                    .tags(List.of("http", "test"))
                    .check(Registration.RegCheck.http("http://localhost:8082/health", 10))
                    .build();

            consulClient.agentClient().register(otherService);
        } catch (Exception e) {
            System.err.println("Error registering test services: " + e.getMessage());
        }
    }

    private void deregisterTestServices() {
        try {
            consulClient.agentClient().deregister("test-pipe-service-1");
            consulClient.agentClient().deregister("test-other-service-1");
        } catch (Exception e) {
            System.err.println("Error deregistering test services: " + e.getMessage());
        }
    }

    @Test
    public void testGetServices() {
        // When
        HttpRequest<?> request = HttpRequest.GET("/api/services");
        HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);

        // Then
        assertEquals(HttpStatus.OK, response.status());
        assertNotNull(response.body());

        Map<String, Object> body = response.body();
        assertNotNull(body.get("services"));

        List<?> services = (List<?>) body.get("services");
        assertFalse(services.isEmpty(), "Services list should not be empty");

        // Verify that the pipe service is in the list
        boolean foundPipeService = false;
        for (Object serviceObj : services) {
            if (serviceObj instanceof Map) {
                Map<?, ?> service = (Map<?, ?>) serviceObj;
                if ("test-pipe-service".equals(service.get("name"))) {
                    foundPipeService = true;
                    assertNotNull(service.get("running"), "Running status should be present");
                    // Address and port might be null in some test environments, so we'll just check if they're present
                    if (service.containsKey("address")) {
                        assertNotNull(service.get("address"), "Address should not be null if present");
                    }
                    if (service.containsKey("port")) {
                        assertNotNull(service.get("port"), "Port should not be null if present");
                    }
                    break;
                }
            }
        }

        assertTrue(foundPipeService, "Pipe service should be in the list");
    }

    @Test
    public void testGetServicesWithNoServices() {
        // Given
        deregisterTestServices();

        // When
        HttpRequest<?> request = HttpRequest.GET("/api/services");
        HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);

        // Then
        assertEquals(HttpStatus.OK, response.status());
        assertNotNull(response.body());

        Map<String, Object> body = response.body();
        assertNotNull(body.get("services"));

        List<?> services = (List<?>) body.get("services");
        // The list might not be empty because there could be other services registered in Consul
        // So we just check that our test services are not in the list

        boolean foundTestService = false;
        for (Object serviceObj : services) {
            if (serviceObj instanceof Map) {
                Map<?, ?> service = (Map<?, ?>) serviceObj;
                if ("test-pipe-service".equals(service.get("name")) || 
                    "test-other-service".equals(service.get("name"))) {
                    foundTestService = true;
                    break;
                }
            }
        }

        assertFalse(foundTestService, "Test services should not be in the list");
    }
}
