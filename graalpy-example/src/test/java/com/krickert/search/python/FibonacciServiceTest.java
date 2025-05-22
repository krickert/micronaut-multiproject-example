package com.krickert.search.python;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.Rule;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MicronautTest
public class FibonacciServiceTest {

    @Inject
    ApplicationContext applicationContext;

    @Inject
    FibonacciServiceImpl fibonacciService;

    private FibonacciServiceGrpc.FibonacciServiceBlockingStub blockingStub;

    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    @BeforeEach
    public void setup() throws IOException {
        // Generate a unique in-process server name.
        String serverName = InProcessServerBuilder.generateName();

        // Create a server, add service, start, and register for automatic graceful shutdown.
        grpcCleanup.register(InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fibonacciService)
                .build()
                .start());

        // Create a client channel and register for automatic graceful shutdown.
        ManagedChannel channel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());

        // Create a blocking stub with the channel.
        blockingStub = FibonacciServiceGrpc.newBlockingStub(channel);
    }

    @Test
    public void testCalculateFibonacci() {
        // Test Fibonacci(0) = 0
        FibonacciRequest request0 = FibonacciRequest.newBuilder().setPosition(0).build();
        FibonacciResponse response0 = blockingStub.calculateFibonacci(request0);
        Assertions.assertEquals(0, response0.getResult());

        // Test Fibonacci(1) = 1
        FibonacciRequest request1 = FibonacciRequest.newBuilder().setPosition(1).build();
        FibonacciResponse response1 = blockingStub.calculateFibonacci(request1);
        Assertions.assertEquals(1, response1.getResult());

        // Test Fibonacci(10) = 55
        FibonacciRequest request10 = FibonacciRequest.newBuilder().setPosition(10).build();
        FibonacciResponse response10 = blockingStub.calculateFibonacci(request10);
        Assertions.assertEquals(55, response10.getResult());
    }

    @Test
    public void testCalculateFibonacciSequence() {
        // Test Fibonacci sequence of length 10
        FibonacciSequenceRequest request = FibonacciSequenceRequest.newBuilder().setLength(10).build();
        FibonacciSequenceResponse response = blockingStub.calculateFibonacciSequence(request);
        
        // Expected Fibonacci sequence: 0, 1, 1, 2, 3, 5, 8, 13, 21, 34
        long[] expected = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34};
        
        Assertions.assertEquals(10, response.getSequenceCount());
        for (int i = 0; i < expected.length; i++) {
            Assertions.assertEquals(expected[i], response.getSequence(i));
        }
    }
}