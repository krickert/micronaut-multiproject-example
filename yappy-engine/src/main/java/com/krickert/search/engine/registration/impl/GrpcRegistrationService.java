package com.krickert.search.engine.registration.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.engine.registration.ModuleRegistrationService;
import com.krickert.search.engine.registration.ModuleRegistrationValidator;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.model.agent.ImmutableRegCheck;
import org.kiwiproject.consul.model.agent.ImmutableRegistration;
import org.kiwiproject.consul.model.agent.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * gRPC implementation of module registration service with validation.
 * This service handles module registration requests from the yappy-registration-cli
 * and other gRPC clients using the RegisterModuleRequest/Response API.
 */
@Singleton
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class GrpcRegistrationService implements ModuleRegistrationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(GrpcRegistrationService.class);
    private static final String YAPPY_MODULE_TAG = "yappy-module";
    private static final String YAPPY_IMPLEMENTATION_ID_TAG_PREFIX = "yappy-module-implementation-id=";
    private static final String YAPPY_CONFIG_DIGEST_TAG_PREFIX = "yappy-config-digest=";
    private static final String YAPPY_MODULE_VERSION_TAG_PREFIX = "yappy-module-version=";
    
    private final ConsulBusinessOperationsService consulBusinessOpsService;
    private final ModuleRegistrationValidator validator;
    private final ObjectMapper digestObjectMapper;
    
    @Inject
    public GrpcRegistrationService(
            ConsulBusinessOperationsService consulBusinessOpsService,
            ModuleRegistrationValidator validator,
            ObjectMapper objectMapper) {
        this.consulBusinessOpsService = consulBusinessOpsService;
        this.validator = validator;
        
        // ObjectMapper for canonical JSON serialization
        this.digestObjectMapper = new ObjectMapper();
        this.digestObjectMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.digestObjectMapper.configure(SerializationFeature.INDENT_OUTPUT, false);
    }
    
    @Override
    public Mono<RegisterModuleResponse> registerModule(RegisterModuleRequest request) {
        LOG.info("Processing module registration for implementationId: {}, serviceName: {}",
                request.getImplementationId(), request.getInstanceServiceName());
        
        return validator.validate(request)
                .flatMap(validationResult -> {
                    if (!validationResult.isValid()) {
                        LOG.warn("Module registration validation failed: {}", validationResult.errorMessage());
                        return Mono.just(RegisterModuleResponse.newBuilder()
                                .setSuccess(false)
                                .setMessage("Validation failed: " + validationResult.errorMessage())
                                .build());
                    }
                    
                    // Log warnings if any
                    if (validationResult.errorMessage() != null && 
                        validationResult.level() == ModuleRegistrationValidator.ValidationLevel.WARNING) {
                        LOG.warn("Module registration validation warning: {}", validationResult.errorMessage());
                    }
                    
                    return performRegistration(request);
                })
                .onErrorResume(error -> {
                    LOG.error("Module registration failed with unexpected error", error);
                    return Mono.just(RegisterModuleResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Registration failed: " + error.getMessage())
                            .build());
                });
    }
    
    private Mono<RegisterModuleResponse> performRegistration(RegisterModuleRequest request) {
        return Mono.fromCallable(() -> {
            RegisterModuleResponse.Builder responseBuilder = RegisterModuleResponse.newBuilder();
            
            // Calculate config digest
            String calculatedDigest;
            String canonicalJson;
            try {
                Object parsedJsonAsObject = digestObjectMapper.readValue(
                        request.getInstanceCustomConfigJson(), Object.class);
                canonicalJson = digestObjectMapper.writeValueAsString(parsedJsonAsObject);
                calculatedDigest = generateMd5Digest(canonicalJson);
                
                responseBuilder.setCalculatedConfigDigest(calculatedDigest);
                responseBuilder.setCanonicalConfigJsonBase64(
                        Base64.getEncoder().encodeToString(canonicalJson.getBytes(StandardCharsets.UTF_8)));
                
                LOG.debug("Calculated MD5 digest '{}' for implementationId '{}'",
                        calculatedDigest, request.getImplementationId());
            } catch (Exception e) {
                throw new RuntimeException("Failed to process config JSON", e);
            }
            
            // Generate service ID
            String serviceId = generateServiceId(request);
            responseBuilder.setRegisteredServiceId(serviceId);
            
            // Build Consul registration
            Registration registration = buildConsulRegistration(request, serviceId, calculatedDigest);
            
            return Mono.just(responseBuilder)
                    .flatMap(builder -> 
                        consulBusinessOpsService.registerService(registration)
                            .then(Mono.fromCallable(() -> {
                                LOG.info("Module instance registered successfully with Consul. Service ID: {}", serviceId);
                                return builder
                                        .setSuccess(true)
                                        .setMessage("Module registered successfully")
                                        .build();
                            }))
                    );
        })
        .flatMap(responseMono -> responseMono);
    }
    
    private String generateServiceId(RegisterModuleRequest request) {
        String instanceIdHint = request.getInstanceIdHint();
        if (instanceIdHint != null && !instanceIdHint.isBlank()) {
            return instanceIdHint + "-" + UUID.randomUUID().toString().substring(0, 8);
        } else {
            return request.getImplementationId() + "-" + UUID.randomUUID().toString();
        }
    }
    
    private Registration buildConsulRegistration(RegisterModuleRequest request, String serviceId, String configDigest) {
        ImmutableRegistration.Builder registrationBuilder = ImmutableRegistration.builder()
                .id(serviceId)
                .name(request.getInstanceServiceName())
                .address(request.getHost())
                .port(request.getPort());
        
        // Configure health check
        ImmutableRegCheck.Builder checkBuilder = ImmutableRegCheck.builder();
        configureHealthCheck(request, registrationBuilder, checkBuilder);
        registrationBuilder.check(checkBuilder.build());
        
        // Add tags
        List<String> tags = buildTags(request, configDigest);
        registrationBuilder.tags(tags);
        
        return registrationBuilder.build();
    }
    
    private void configureHealthCheck(RegisterModuleRequest request, 
                                      ImmutableRegistration.Builder registrationBuilder,
                                      ImmutableRegCheck.Builder checkBuilder) {
        long checkIntervalSeconds = 10;
        long checkTimeoutSeconds = 5;
        long deregisterCriticalServiceAfterSeconds = 60;
        
        switch (request.getHealthCheckType()) {
            case HTTP:
                String httpCheckUrl = String.format("http://%s:%d%s",
                        request.getHost(), request.getPort(), 
                        request.getHealthCheckEndpoint().isBlank() ? "/health" : request.getHealthCheckEndpoint());
                checkBuilder.http(httpCheckUrl)
                        .interval(checkIntervalSeconds + "s")
                        .timeout(checkTimeoutSeconds + "s");
                break;
                
            case GRPC:
                String grpcCheckAddress = request.getHost() + ":" + request.getPort();
                checkBuilder.grpc(grpcCheckAddress)
                        .interval(checkIntervalSeconds + "s")
                        .timeout(checkTimeoutSeconds + "s");
                if (!request.getHealthCheckEndpoint().isBlank()) {
                    registrationBuilder.addTags("grpc-health-service-name=" + request.getHealthCheckEndpoint());
                }
                break;
                
            case TCP:
                String tcpCheckAddress = request.getHost() + ":" + request.getPort();
                checkBuilder.tcp(tcpCheckAddress)
                        .interval(checkIntervalSeconds + "s")
                        .timeout(checkTimeoutSeconds + "s");
                break;
                
            case TTL:
                long defaultTtlIntervalSeconds = 15;
                checkBuilder.ttl(defaultTtlIntervalSeconds + "s");
                break;
        }
        
        checkBuilder.deregisterCriticalServiceAfter(deregisterCriticalServiceAfterSeconds + "s");
    }
    
    private List<String> buildTags(RegisterModuleRequest request, String configDigest) {
        List<String> tags = new ArrayList<>();
        tags.add(YAPPY_MODULE_TAG + "=true");
        tags.add(YAPPY_IMPLEMENTATION_ID_TAG_PREFIX + request.getImplementationId());
        tags.add(YAPPY_CONFIG_DIGEST_TAG_PREFIX + configDigest);
        
        if (request.hasModuleSoftwareVersion() && !request.getModuleSoftwareVersion().isBlank()) {
            tags.add(YAPPY_MODULE_VERSION_TAG_PREFIX + request.getModuleSoftwareVersion());
        }
        
        request.getAdditionalTagsMap().forEach((key, value) -> tags.add(key + "=" + value));
        return tags;
    }
    
    private String generateMd5Digest(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digestBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digestBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    @Override
    public Mono<Void> deregisterModule(String serviceId) {
        LOG.info("Deregistering module with service ID: {}", serviceId);
        return consulBusinessOpsService.deregisterService(serviceId)
                .doOnSuccess(v -> LOG.info("Module {} deregistered successfully", serviceId))
                .doOnError(error -> LOG.error("Failed to deregister module {}", serviceId, error));
    }
    
    @Override
    public Mono<Boolean> isModuleHealthy(String serviceId) {
        // For now, just check if the service exists in the agent
        // In the future, we could enhance this to check actual health status
        return consulBusinessOpsService.getAgentServiceDetails(serviceId)
                .map(optionalService -> {
                    boolean exists = optionalService.isPresent();
                    LOG.debug("Module {} health check: {}", serviceId, exists ? "exists" : "not found");
                    return exists;
                })
                .onErrorResume(error -> {
                    LOG.error("Error checking health for module {}", serviceId, error);
                    return Mono.just(false);
                });
    }
}