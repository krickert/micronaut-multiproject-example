package com.krickert.search.engine.registration.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.krickert.search.engine.registration.ModuleRegistrationValidator;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of ModuleRegistrationValidator that performs comprehensive validation
 * including connectivity checks and health endpoint verification.
 */
@Singleton
public class ModuleRegistrationValidatorImpl implements ModuleRegistrationValidator {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationValidatorImpl.class);
    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    
    private final ObjectMapper objectMapper;
    private final SchemaValidationService schemaValidationService;
    
    @Inject
    public ModuleRegistrationValidatorImpl(ObjectMapper objectMapper, SchemaValidationService schemaValidationService) {
        this.objectMapper = objectMapper;
        this.schemaValidationService = schemaValidationService;
    }
    
    @Override
    public Mono<ValidationResult> validate(RegisterModuleRequest request) {
        return Mono.fromCallable(() -> {
            // Basic field validation
            ValidationResult basicValidation = validateBasicFields(request);
            if (!basicValidation.isValid()) {
                return basicValidation;
            }
            
            // JSON validation
            ValidationResult jsonValidation = validateJson(request);
            if (!jsonValidation.isValid()) {
                return jsonValidation;
            }
            
            // Connectivity validation based on health check type
            ValidationResult connectivityValidation = validateConnectivity(request);
            if (!connectivityValidation.isValid()) {
                return connectivityValidation;
            }
            
            // Health check validation
            ValidationResult healthCheckValidation = validateHealthCheck(request);
            if (!healthCheckValidation.isValid()) {
                return healthCheckValidation;
            }
            
            return ValidationResult.valid();
        });
    }
    
    private ValidationResult validateBasicFields(RegisterModuleRequest request) {
        if (request.getImplementationId() == null || request.getImplementationId().isBlank()) {
            return ValidationResult.error("Implementation ID is required");
        }
        
        if (request.getInstanceServiceName() == null || request.getInstanceServiceName().isBlank()) {
            return ValidationResult.error("Instance service name is required");
        }
        
        if (request.getHost() == null || request.getHost().isBlank()) {
            return ValidationResult.error("Host is required");
        }
        
        if (request.getPort() <= 0 || request.getPort() > 65535) {
            return ValidationResult.error("Port must be between 1 and 65535");
        }
        
        if (request.getHealthCheckType() == HealthCheckType.HEALTH_CHECK_TYPE_UNKNOWN) {
            return ValidationResult.error("Valid health check type is required");
        }
        
        if (request.getInstanceCustomConfigJson() == null || request.getInstanceCustomConfigJson().isBlank()) {
            return ValidationResult.error("Instance custom config JSON is required");
        }
        
        return ValidationResult.valid();
    }
    
    private ValidationResult validateJson(RegisterModuleRequest request) {
        try {
            // First validate that the JSON is well-formed using SchemaValidationService
            Boolean isValid = schemaValidationService.isValidJson(request.getInstanceCustomConfigJson()).block();
            
            if (!isValid) {
                return ValidationResult.error("Invalid JSON format");
            }
            
            // TODO: If we have a schema for this module's custom config, validate against it
            // For now, just ensure it's valid JSON
            return ValidationResult.valid();
        } catch (Exception e) {
            return ValidationResult.error("Error validating JSON: " + e.getMessage());
        }
    }
    
    private ValidationResult validateConnectivity(RegisterModuleRequest request) {
        // Skip connectivity check for TTL health checks since they don't require the service to be running
        if (request.getHealthCheckType() == HealthCheckType.TTL) {
            LOG.debug("Skipping connectivity check for TTL health check type");
            return ValidationResult.valid();
        }
        
        String host = request.getHost();
        int port = request.getPort();
        
        // Basic TCP connectivity check
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS);
            LOG.debug("Successfully connected to {}:{}", host, port);
            return ValidationResult.valid();
        } catch (Exception e) {
            return ValidationResult.error(
                String.format("Cannot connect to %s:%d - %s", host, port, e.getMessage())
            );
        }
    }
    
    private ValidationResult validateHealthCheck(RegisterModuleRequest request) {
        switch (request.getHealthCheckType()) {
            case HTTP:
                return validateHttpHealthCheck(request);
            case GRPC:
                return validateGrpcHealthCheck(request);
            case TCP:
                // TCP connectivity already checked in validateConnectivity
                return ValidationResult.valid();
            case TTL:
                // TTL doesn't require endpoint validation
                return ValidationResult.valid();
            default:
                return ValidationResult.error("Unsupported health check type: " + request.getHealthCheckType());
        }
    }
    
    private ValidationResult validateHttpHealthCheck(RegisterModuleRequest request) {
        String healthCheckEndpoint = request.getHealthCheckEndpoint();
        if (healthCheckEndpoint == null || healthCheckEndpoint.isBlank()) {
            healthCheckEndpoint = "/health";
        }
        
        String url = String.format("http://%s:%d%s", request.getHost(), request.getPort(), healthCheckEndpoint);
        
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            
            if (responseCode >= 200 && responseCode < 300) {
                LOG.debug("HTTP health check successful for {}", url);
                return ValidationResult.valid();
            } else {
                return ValidationResult.warning(
                    String.format("HTTP health check returned non-2xx status: %d for %s", responseCode, url)
                );
            }
        } catch (Exception e) {
            return ValidationResult.warning(
                String.format("HTTP health check failed for %s: %s", url, e.getMessage())
            );
        }
    }
    
    private ValidationResult validateGrpcHealthCheck(RegisterModuleRequest request) {
        ManagedChannel channel = null;
        try {
            channel = ManagedChannelBuilder
                    .forAddress(request.getHost(), request.getPort())
                    .usePlaintext()
                    .build();
            
            HealthGrpc.HealthBlockingStub healthStub = HealthGrpc.newBlockingStub(channel);
            
            // Build health check request
            HealthCheckRequest.Builder healthRequestBuilder = HealthCheckRequest.newBuilder();
            if (!request.getHealthCheckEndpoint().isBlank()) {
                healthRequestBuilder.setService(request.getHealthCheckEndpoint());
            }
            
            HealthCheckResponse response = healthStub
                    .withDeadlineAfter(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .check(healthRequestBuilder.build());
            
            if (response.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                LOG.debug("gRPC health check successful for {}:{}", request.getHost(), request.getPort());
                return ValidationResult.valid();
            } else {
                return ValidationResult.warning(
                    String.format("gRPC health check returned non-SERVING status: %s", response.getStatus())
                );
            }
        } catch (StatusRuntimeException e) {
            return ValidationResult.warning(
                String.format("gRPC health check failed: %s", e.getStatus())
            );
        } catch (Exception e) {
            return ValidationResult.warning(
                String.format("gRPC health check failed: %s", e.getMessage())
            );
        } finally {
            if (channel != null) {
                channel.shutdown();
                try {
                    if (!channel.awaitTermination(1, TimeUnit.SECONDS)) {
                        channel.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    channel.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}