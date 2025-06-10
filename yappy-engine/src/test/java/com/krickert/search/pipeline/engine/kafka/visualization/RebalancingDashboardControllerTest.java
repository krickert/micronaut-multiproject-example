package com.krickert.search.pipeline.engine.kafka.visualization;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RebalancingDashboardController
 */
@MicronautTest
@Property(name = "app.kafka.visualization.enabled", value = "true")
public class RebalancingDashboardControllerTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(RebalancingDashboardControllerTest.class);
    
    @Inject
    @Client("/")
    HttpClient client;
    
    @Test
    void testDashboardEndpoint() {
        String engineId = "test-engine-123";
        HttpRequest<Object> request = HttpRequest.GET("/visualization/rebalancing/" + engineId);
        
        HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        Map<String, Object> model = response.body();
        assertNotNull(model);
        assertEquals(engineId, model.get("engineId"));
        assertEquals("/ws/rebalancing/" + engineId, model.get("wsUrl"));
        assertEquals("/metrics", model.get("metricsUrl"));
    }
    
    @Test
    void testOverviewEndpoint() {
        HttpRequest<Object> request = HttpRequest.GET("/visualization/rebalancing/");
        
        HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        Map<String, Object> model = response.body();
        assertNotNull(model);
        assertEquals("/ws/rebalancing/global", model.get("wsUrl"));
        assertEquals("/metrics", model.get("metricsUrl"));
    }
    
    @Test
    @Disabled("TODO: Test HTML rendering when views are fully configured")
    void testDashboardHtmlRendering() {
        fail("TODO: Implement HTML view rendering test");
        // This would test that the HTML is properly rendered with the model data
    }
    
    @Test
    @Disabled("TODO: Test with disabled visualization")
    void testDisabledVisualization() {
        fail("TODO: Test that endpoints return 404 when visualization is disabled");
        // Would need to restart context with app.kafka.visualization.enabled=false
    }
}