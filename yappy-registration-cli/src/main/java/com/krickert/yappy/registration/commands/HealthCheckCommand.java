package com.krickert.yappy.registration.commands;

import com.krickert.yappy.registration.YappyRegistrationCli;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Command to validate health check endpoints.
 */
@Slf4j
@Command(
    name = "health",
    description = "Test health check endpoints",
    mixinStandardHelpOptions = true
)
public class HealthCheckCommand implements Callable<Integer> {
    
    @ParentCommand
    private YappyRegistrationCli parent;
    
    @Parameters(
        index = "0",
        description = "Endpoint to check (e.g., localhost:50051 or http://localhost:8080/health)"
    )
    private String endpoint;
    
    @Option(
        names = {"-t", "--type"},
        description = "Health check type: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})",
        defaultValue = "GRPC"
    )
    private HealthType healthType;
    
    @Option(
        names = {"-s", "--service"},
        description = "gRPC service name to check (for gRPC health checks)"
    )
    private String serviceName;
    
    @Option(
        names = {"--timeout"},
        description = "Timeout in seconds (default: ${DEFAULT-VALUE})",
        defaultValue = "5"
    )
    private int timeout;
    
    public enum HealthType {
        HTTP, GRPC, TCP
    }
    
    @Override
    public Integer call() {
        try {
            log.info("Checking {} health endpoint: {}", healthType, endpoint);
            
            boolean healthy = switch (healthType) {
                case GRPC -> checkGrpcHealth();
                case HTTP -> checkHttpHealth();
                case TCP -> checkTcpHealth();
            };
            
            if (healthy) {
                log.info("✓ Health check passed!");
                return 0;
            } else {
                log.error("✗ Health check failed!");
                return 1;
            }
            
        } catch (Exception e) {
            log.error("Health check error: {}", e.getMessage());
            if (parent.verbose) {
                log.error("Stack trace:", e);
            }
            return 1;
        }
    }
    
    private boolean checkGrpcHealth() {
        // Parse endpoint
        String[] parts = endpoint.split(":");
        if (parts.length != 2) {
            log.error("Invalid gRPC endpoint format. Expected host:port");
            return false;
        }
        
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            log.error("Invalid port number: {}", parts[1]);
            return false;
        }
        
        ManagedChannel channel = null;
        try {
            log.info("Connecting to gRPC endpoint {}:{}", host, port);
            
            channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build();
            
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeout, TimeUnit.SECONDS);
            
            HealthCheckRequest request = HealthCheckRequest.newBuilder()
                .setService(serviceName != null ? serviceName : "")
                .build();
            
            log.info("Sending health check request for service: '{}'", 
                serviceName != null ? serviceName : "(all services)");
            
            HealthCheckResponse response = healthStub.check(request);
            
            log.info("Health check response: {}", response.getStatus());
            
            return response.getStatus() == HealthCheckResponse.ServingStatus.SERVING;
            
        } catch (Exception e) {
            log.error("gRPC health check failed: {}", e.getMessage());
            return false;
        } finally {
            if (channel != null) {
                channel.shutdown();
                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    channel.shutdownNow();
                }
            }
        }
    }
    
    private boolean checkHttpHealth() {
        try {
            URL url;
            if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
                url = new URL(endpoint);
            } else {
                // Assume http if no protocol specified
                url = new URL("http://" + endpoint);
            }
            
            log.info("Checking HTTP endpoint: {}", url);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout * 1000);
            connection.setReadTimeout(timeout * 1000);
            
            int responseCode = connection.getResponseCode();
            log.info("HTTP response code: {}", responseCode);
            
            // Consider 2xx responses as healthy
            return responseCode >= 200 && responseCode < 300;
            
        } catch (Exception e) {
            log.error("HTTP health check failed: {}", e.getMessage());
            return false;
        }
    }
    
    private boolean checkTcpHealth() {
        // Parse endpoint
        String host;
        int port;
        
        if (endpoint.contains(":")) {
            String[] parts = endpoint.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.error("Invalid port number: {}", parts[1]);
                return false;
            }
        } else {
            log.error("TCP endpoint must include port (e.g., localhost:8080)");
            return false;
        }
        
        try {
            log.info("Checking TCP connection to {}:{}", host, port);
            
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), timeout * 1000);
                log.info("Successfully connected to {}:{}", host, port);
                return true;
            }
            
        } catch (Exception e) {
            log.error("TCP connection failed: {}", e.getMessage());
            return false;
        }
    }
}