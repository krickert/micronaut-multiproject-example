package com.krickert.yappy.modules.opensearchsink.health;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cluster.HealthRequest;
import org.opensearch.client.opensearch.cluster.HealthResponse;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Health indicator for OpenSearch connectivity.
 * Checks if the OpenSearch cluster is available and responsive.
 */
@Singleton
@Requires(beans = OpenSearchClient.class)
public class OpenSearchHealthIndicator implements HealthIndicator {
    
    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchHealthIndicator.class);
    
    private final OpenSearchClient openSearchClient;
    
    @Inject
    public OpenSearchHealthIndicator(OpenSearchClient openSearchClient) {
        this.openSearchClient = openSearchClient;
    }
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            
            try {
                // Check cluster health
                HealthRequest healthRequest = HealthRequest.of(builder -> builder);
                HealthResponse healthResponse = openSearchClient.cluster().health(healthRequest);
                
                details.put("cluster_name", healthResponse.clusterName());
                details.put("status", healthResponse.status().toString());
                details.put("number_of_nodes", healthResponse.numberOfNodes());
                details.put("number_of_data_nodes", healthResponse.numberOfDataNodes());
                details.put("active_primary_shards", healthResponse.activePrimaryShards());
                details.put("active_shards", healthResponse.activeShards());
                details.put("initializing_shards", healthResponse.initializingShards());
                details.put("unassigned_shards", healthResponse.unassignedShards());
                
                // Determine health based on cluster status
                HealthStatus healthStatus;
                String message;
                
                switch (healthResponse.status()) {
                    case Green:
                        healthStatus = HealthStatus.UP;
                        message = "OpenSearch cluster is healthy (green)";
                        break;
                    case Yellow:
                        healthStatus = HealthStatus.UP;
                        message = "OpenSearch cluster is functional but degraded (yellow)";
                        break;
                    case Red:
                        healthStatus = HealthStatus.DOWN;
                        message = "OpenSearch cluster has critical issues (red)";
                        break;
                    default:
                        healthStatus = HealthStatus.UNKNOWN;
                        message = "OpenSearch cluster status unknown";
                        break;
                }
                
                LOG.debug("OpenSearch health check completed: {} - {}", healthStatus, message);
                
                return HealthResult.builder("opensearch", healthStatus)
                        .details(details)
                        .build();
                        
            } catch (Exception e) {
                LOG.warn("OpenSearch health check failed", e);
                details.put("error", e.getMessage());
                details.put("error_type", e.getClass().getSimpleName());
                
                return HealthResult.builder("opensearch", HealthStatus.DOWN)
                        .details(details)
                        .build();
            }
        });
    }
}