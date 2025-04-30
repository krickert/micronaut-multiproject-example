package com.krickert.search.config.consul.api;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.agent.model.NewService;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ServiceDiscoveryControllerTest implements TestPropertyProvider {

    @Factory
    static class TestBeanFactory {
        @Bean
        @Singleton
        @Replaces(bean = ConsulClient.class)
        public ConsulClient consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return new ConsulClient(consulContainer.getHost(), consulContainer.getMappedPort(8500));
        }
    }

    @Bean
    @Singleton
    @Replaces(bean = ConsulKvService.class)
    public ConsulKvService consulKvService(ConsulClient consulClient) {
        return new ConsulKvService(consulClient, "config/test");
    }

    @Container
    public static ConsulContainer consulContainer = new ConsulContainer("hashicorp/consul:latest")
            .withExposedPorts(8500);
    static {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
    }

    @Inject
    private ConsulClient consulClient;

    @Inject
    @Client("/")
    private HttpClient client;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();

        // Ensure the container is started before getting host and port
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", consulContainer.getMappedPort(8500).toString());

        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", consulContainer.getMappedPort(8500).toString());
        properties.put("consul.client.config.path", "config/test");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

        // Disable data seeding for tests
        properties.put("consul.data.seeding.enabled", "false");

        return properties;
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
            NewService pipeService = new NewService();
            pipeService.setId("test-pipe-service-1");
            pipeService.setName("test-pipe-service");
            pipeService.setAddress("localhost");
            pipeService.setPort(8081);
            pipeService.setTags(List.of("grpc-pipeservice", "test"));

            // Add a health check
            NewService.Check check = new NewService.Check();
            check.setHttp("http://localhost:8081/health");
            check.setInterval("10s");
            pipeService.setCheck(check);

            consulClient.agentServiceRegister(pipeService);

            // Register another service without the pipe tag
            NewService otherService = new NewService();
            otherService.setId("test-other-service-1");
            otherService.setName("test-other-service");
            otherService.setAddress("localhost");
            otherService.setPort(8082);
            otherService.setTags(List.of("http", "test"));

            NewService.Check otherCheck = new NewService.Check();
            otherCheck.setHttp("http://localhost:8082/health");
            otherCheck.setInterval("10s");
            otherService.setCheck(otherCheck);

            consulClient.agentServiceRegister(otherService);
        } catch (Exception e) {
            System.err.println("Error registering test services: " + e.getMessage());
        }
    }

    private void deregisterTestServices() {
        try {
            consulClient.agentServiceDeregister("test-pipe-service-1");
            consulClient.agentServiceDeregister("test-other-service-1");
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
                    assertNotNull(service.get("address"), "Address should be present");
                    assertNotNull(service.get("port"), "Port should be present");
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
