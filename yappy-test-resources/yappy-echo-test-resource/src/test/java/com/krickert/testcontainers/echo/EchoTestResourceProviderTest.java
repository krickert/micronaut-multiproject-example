package com.krickert.testcontainers.echo;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.extensions.junit5.annotation.TestResourcesScope;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test to verify the Echo test resource provider works correctly
 */
@MicronautTest
@TestResourcesScope("echo")
public class EchoTestResourceProviderTest {

    @Inject
    @TestResourcesScope("echo")
    String echoGrpcHost;

    @Inject
    @TestResourcesScope("echo")
    String echoGrpcPort;

    @Test
    void testEchoTestResource() {
        assertNotNull(echoGrpcHost, "Echo gRPC host should be provided");
        assertNotNull(echoGrpcPort, "Echo gRPC port should be provided");
        
        System.out.println("Echo gRPC endpoint: " + echoGrpcHost + ":" + echoGrpcPort);
    }
}