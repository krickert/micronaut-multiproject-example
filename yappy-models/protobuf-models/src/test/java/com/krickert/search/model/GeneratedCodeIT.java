package com.krickert.search.model;

// Import the generated classes. If this line has an error, code generation failed.
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test validates that the gRPC health check classes have been
 * successfully generated from the .proto file inside the grpc-services JAR.
 * If this test fails to compile, it means the build process is broken.
 */
public class GeneratedCodeIT {

    @Test
    void healthCheckClassesAreAvailable() {
        // 1. Prove the Request class exists and can be built.
        HealthCheckRequest request = HealthCheckRequest.newBuilder()
                .setService("my-service")
                .build();

        assertNotNull(request, "HealthCheckRequest should not be null.");
        assertEquals("my-service", request.getService(), "The service name should be set correctly.");

        // 2. Prove the Response class and its enums exist.
        HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.SERVING)
                .build();

        assertNotNull(response, "HealthCheckResponse should not be null.");
        assertEquals(HealthCheckResponse.ServingStatus.SERVING, response.getStatus(), "The status should be set correctly.");

        // 3. Prove the gRPC Service descriptor class exists.
        // This is a simple, static way to confirm the HealthGrpc class was generated.
        assertNotNull(HealthGrpc.getServiceDescriptor(), "The service descriptor for HealthGrpc should exist.");

        System.out.println("Successfully verified that gRPC Health Check classes were generated.");
    }
}