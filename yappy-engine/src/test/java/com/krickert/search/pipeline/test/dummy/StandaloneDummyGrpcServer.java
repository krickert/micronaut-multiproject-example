package com.krickert.search.pipeline.test.dummy;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Standalone gRPC server for the DummyPipeStepProcessor that runs independently
 * of Micronaut's service discovery and management. This ensures test isolation.
 */
public class StandaloneDummyGrpcServer {
    private static final Logger LOG = LoggerFactory.getLogger(StandaloneDummyGrpcServer.class);
    
    private final int port;
    private final DummyPipeStepProcessor processor;
    private Server server;
    
    public StandaloneDummyGrpcServer(int port, DummyPipeStepProcessor processor) {
        this.port = port;
        this.processor = processor;
    }
    
    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(processor)
                .build()
                .start();
        
        LOG.info("Standalone Dummy gRPC Server started on port {}", port);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("*** shutting down gRPC server since JVM is shutting down");
            try {
                StandaloneDummyGrpcServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            LOG.info("*** server shut down");
        }));
    }
    
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }
    
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}