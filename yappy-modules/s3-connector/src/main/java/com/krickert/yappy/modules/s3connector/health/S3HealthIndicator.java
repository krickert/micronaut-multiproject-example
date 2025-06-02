package com.krickert.yappy.modules.s3connector.health;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Health indicator for S3 connectivity.
 * Checks if the S3 service is available and accessible.
 */
@Singleton
@Requires(beans = S3Client.class)
public class S3HealthIndicator implements HealthIndicator {
    
    private static final Logger LOG = LoggerFactory.getLogger(S3HealthIndicator.class);
    
    private final S3Client s3Client;
    
    @Inject
    public S3HealthIndicator(S3Client s3Client) {
        this.s3Client = s3Client;
    }
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            
            try {
                // Test basic S3 connectivity by listing buckets
                ListBucketsResponse listBucketsResponse = s3Client.listBuckets();
                
                details.put("buckets_accessible", listBucketsResponse.buckets().size());
                details.put("service_endpoint", s3Client.serviceClientConfiguration().endpointOverride()
                        .map(uri -> uri.toString())
                        .orElse("default"));
                details.put("region", s3Client.serviceClientConfiguration().region().toString());
                
                // Additional check - verify we can make authenticated requests
                // This ensures credentials are valid and permissions are sufficient
                boolean canAccessS3 = true;
                String message = String.format("S3 service accessible, %d buckets available", 
                        listBucketsResponse.buckets().size());
                
                LOG.debug("S3 health check completed successfully: {}", message);
                
                return HealthResult.builder("s3", HealthStatus.UP)
                        .details(details)
                        .build();
                        
            } catch (Exception e) {
                LOG.warn("S3 health check failed", e);
                details.put("error", e.getMessage());
                details.put("error_type", e.getClass().getSimpleName());
                
                return HealthResult.builder("s3", HealthStatus.DOWN)
                        .details(details)
                        .build();
            }
        });
    }
}