package com.krickert.search.pipeline.engine.kafka.visualization;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.HttpResponse;
import io.micronaut.views.View;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for serving the rebalancing visualization dashboard.
 */
@Controller("/visualization/rebalancing")
@Requires(property = "app.kafka.visualization.enabled", value = "true", defaultValue = "false")
public class RebalancingDashboardController {
    
    private static final Logger LOG = LoggerFactory.getLogger(RebalancingDashboardController.class);
    
    private final RebalancingVisualizer visualizer;
    
    @Inject
    public RebalancingDashboardController(RebalancingVisualizer visualizer) {
        this.visualizer = visualizer;
    }
    
    @Get("/{engineId}")
    @View("rebalancing-dashboard")
    public HttpResponse<Map<String, Object>> dashboard(@PathVariable String engineId) {
        LOG.info("Serving dashboard for engine: {}", engineId);
        
        Map<String, Object> model = new HashMap<>();
        model.put("engineId", engineId);
        model.put("wsUrl", "/ws/rebalancing/" + engineId);
        model.put("metricsUrl", "/metrics");
        
        return HttpResponse.ok(model);
    }
    
    @Get("/")
    @View("rebalancing-overview")
    public HttpResponse<Map<String, Object>> overview() {
        LOG.info("Serving rebalancing overview dashboard");
        
        Map<String, Object> model = new HashMap<>();
        model.put("wsUrl", "/ws/rebalancing/global");
        model.put("metricsUrl", "/metrics");
        
        return HttpResponse.ok(model);
    }
}